import json
import fcntl
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'deploy_backend.py'
FAKE = Path(__file__).with_name('fake_deploy_command.py')


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        for name in ('docker', 'curl'):
            shim = self.bin / name
            shim.write_text('#!' + sys.executable + '\n' + FAKE.read_text())
            shim.chmod(0o700)
        self.old_secret = self.root / 'old-firebase.json'
        self.old_secret.write_text('{"test":"previous credential"}')
        self.state_path = self.root / 'docker.json'
        self.state_path.write_text(json.dumps({'containers': [{
            'Id': 'old-id', 'Image': 'old-image-id', 'Name': '/hof-test',
            'Config': {'Labels': {}, 'Image': 'hof-test:old'}, 'State': {'Running': True},
            'Mounts': [{'Source': str(self.old_secret), 'Destination': '/run/secrets/firebase-service-account.json'}],
        }]}))
        self.env = {**os.environ, 'PATH': str(self.bin) + os.pathsep + os.environ['PATH'],
            'FAKE_DEPLOY_STATE': str(self.state_path), 'DEPLOY_SECRET_DIR': str(self.root / 'secrets'),
            'DEPLOY_STATE_DIR': str(self.root / 'deployment-state'),
            'IMAGE': 'hof-test:new', 'IMAGE_ID': 'new-image-id', 'IMAGE_REPOSITORY': 'hof-test', 'CONTAINER_NAME': 'hof-test',
            'BACKEND_BIND_ADDRESS': '127.0.0.1', 'HOST_PORT': '18080', 'CONTAINER_PORT': '8080',
            'SERVER_FORWARD_HEADERS_STRATEGY': 'NONE', 'PUBLIC_HEALTH_URL': 'https://test.invalid/health',
            'HOF_AUTH_ALLOWED_ORIGIN_PATTERNS': 'chrome-extension://*',
            'RELEASE_HOST_DIR': str(self.root / 'releases'), 'RELEASE_CONTAINER_DIR': '/var/lib/hof/releases',
            'BUILD_NUMBER': '42', 'DEPLOY_HEALTH_SECONDS': '1', 'DEPLOY_COMMAND_SECONDS': '2',
        }
        transfers = tempfile.TemporaryDirectory(prefix='hof-deploy.', dir='/tmp')
        self.addCleanup(transfers.cleanup)
        for name, filename in (('REMOTE_ENV_FILE', 'backend.env'), ('REMOTE_FIREBASE_FILE', 'firebase.json'),
                               ('REMOTE_RELEASE_ENV_FILE', 'release.env')):
            path = Path(transfers.name) / filename
            path.write_text('SENSITIVE_TEST_VALUE=do-not-log-this\n')
            path.chmod(0o600)
            self.env[name] = str(path)

    def state(self):
        return json.loads(self.state_path.read_text())

    def configure(self, **changes):
        self.state_path.write_text(json.dumps({**self.state(), **changes}))

    def start_deploy(self):
        return subprocess.Popen(['bash', '-c', 'export FAKE_DEPLOY_SUPERVISOR_PID=$$; exec "$@"',
                                 'deployment-test', sys.executable, str(SCRIPT)], env=self.env,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def deploy(self):
        process = self.start_deploy()
        stdout, stderr = process.communicate(timeout=10)
        result = subprocess.CompletedProcess(process.args, process.returncode, stdout, stderr)
        self.assertNotIn('do-not-log-this', result.stdout + result.stderr)
        return result

    def next_request(self):
        self.env['BUILD_NUMBER'] += '-next'
        transfers = tempfile.TemporaryDirectory(prefix='hof-deploy.', dir='/tmp')
        self.addCleanup(transfers.cleanup)
        for key in ('REMOTE_ENV_FILE', 'REMOTE_FIREBASE_FILE', 'REMOTE_RELEASE_ENV_FILE'):
            path = Path(transfers.name) / Path(self.env[key]).name
            path.write_text('next request credential')
            self.env[key] = str(path)

    def test_rename_failure_restores_same_previous_container_and_checks_health(self):
        self.configure(fault='rename')
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('rollback_healthy', result.stdout)
        self.assertEqual([('old-id', '/hof-test', True)], [
            (c['Id'], c['Name'], c['State']['Running']) for c in self.state()['containers']])
        self.assertTrue(self.old_secret.exists())

    def test_success_keeps_new_service_and_cleanup_failure_does_not_restore_old(self):
        self.configure(fault='cleanup')
        result = self.deploy()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('deployed', result.stdout)
        self.assertIn('cleanup_warning', result.stdout)
        current = next(c for c in self.state()['containers'] if c['Name'] == '/hof-test')
        self.assertEqual(('new-id', True), (current['Id'], current['State']['Running']))
        self.assertTrue(self.old_secret.exists())
        self.assertFalse(any(call[1:3] == ['image', 'prune'] for call in self.state()['calls']))

    def test_missing_image_does_not_stop_original_service(self):
        self.configure(fault='missing-image')
        before = self.state()['containers']
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.state()['containers'])

    def test_loaded_image_id_mismatch_does_not_touch_previous_service(self):
        self.env['IMAGE_ID'] = 'unexpected-image-id'
        self.env['IMAGE_DIGEST'] = 'unexpected-manifest-digest'
        before = self.state()['containers']
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('loaded_image_id_mismatch', result.stdout)
        self.assertEqual(before, self.state()['containers'])

    def test_containerd_manifest_identity_is_pinned_for_compose_and_health(self):
        self.env['IMAGE_ID'] = 'jib-config-id'
        self.env['IMAGE_DIGEST'] = 'new-image-id'
        result = self.deploy()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        current = next(c for c in self.state()['containers'] if c['Name'] == '/hof-test')
        self.assertEqual('new-image-id', current['Image'])
        compose = json.loads((self.root / 'deployment-state/hof-test-current/compose.json').read_text())
        self.assertEqual('new-image-id', compose['services']['backend']['image'])

    def test_docker_create_failure_leaves_previous_service_and_secret_unchanged(self):
        self.configure(fault='create')
        before = self.state()['containers']
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.state()['containers'])
        self.assertTrue(self.old_secret.exists())

    def test_docker_timeout_after_stop_restores_previous_service(self):
        self.env['DEPLOY_COMMAND_SECONDS'] = '1'
        self.configure(fault='hung-stop')
        started = time.monotonic()
        result = self.deploy()
        self.assertLess(time.monotonic() - started, 5)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('rollback_healthy', result.stdout)

    def test_lost_remove_response_continues_restore_after_confirming_candidate_absence(self):
        self.configure(fault='removed-response-lost')
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('rollback_healthy', result.stdout)
        self.assertEqual([('old-id', True)], [(c['Id'], c['State']['Running']) for c in self.state()['containers']])

    def test_intentionally_stopped_previous_service_is_not_started_on_failure(self):
        previous = self.state()['containers']
        previous[0]['State']['Running'] = False
        self.configure(containers=previous, fault='internal-health')
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(previous, self.state()['containers'])

    def test_old_service_health_failure_is_reported_without_an_infinite_retry(self):
        self.configure(fault='rollback-health')
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('rollback_unhealthy', result.stdout)
        self.assertNotIn('rollback_healthy', result.stdout)

    def test_success_preserves_mounted_secret_and_cleans_transfers(self):
        result = self.deploy()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(['new-id'], [c['Id'] for c in self.state()['containers']])
        mount = next(m for m in self.state()['containers'][0]['Mounts']
                     if m['Destination'] == '/run/secrets/firebase-service-account.json')
        secret = Path(mount['Source'])
        self.assertEqual(0o600, secret.stat().st_mode & 0o777)
        self.assertEqual(0o700, secret.parent.stat().st_mode & 0o777)
        self.assertIn('do-not-log-this', secret.read_text())
        self.assertTrue(self.old_secret.exists())
        for key in ('REMOTE_ENV_FILE', 'REMOTE_FIREBASE_FILE', 'REMOTE_RELEASE_ENV_FILE'):
            self.assertFalse(Path(self.env[key]).exists())

    def test_top_level_down_with_nested_up_restores_previous_version(self):
        self.configure(fault='internal-health')
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('rollback_healthy', result.stdout)
        self.assertEqual(['old-id'], [c['Id'] for c in self.state()['containers']])

    def test_public_failure_reports_previous_internal_health_separately(self):
        self.configure(fault='public-health')
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('"internal": true, "public": false', result.stdout)
        self.assertNotIn('rollback_healthy', result.stdout)
        self.assertTrue(self.state()['containers'][0]['State']['Running'])

    def test_hung_http_request_is_bounded_and_restores_previous_version(self):
        self.configure(fault='hung-health')
        started = time.monotonic()
        result = self.deploy()
        self.assertLess(time.monotonic() - started, 5)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('rollback_healthy', result.stdout)

    def test_each_handled_signal_preserves_cancellation_code_and_recovers(self):
        for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            with self.subTest(signal=signum):
                self.setUp()
                self.configure(fault='signal', signal=int(signum))
                result = self.deploy()
                self.assertEqual(128 + signum, result.returncode, result.stdout + result.stderr)
                self.assertIn('rollback_healthy', result.stdout)
                self.assertEqual(['old-id'], [c['Id'] for c in self.state()['containers']])

    def test_signals_during_stop_wait_for_that_command_before_restore(self):
        for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            with self.subTest(signal=signum):
                self.setUp()
                self.configure(fault='signal-stop', signal=int(signum))
                result = self.deploy()
                self.assertEqual(128 + signum, result.returncode, result.stdout + result.stderr)
                self.assertIn('rollback_healthy', result.stdout)
                self.assertEqual(['old-id'], [c['Id'] for c in self.state()['containers']])

    def test_docker_failure_preserves_exit_code_and_safe_stage(self):
        self.configure(fault='create', exit_code=23)
        result = self.deploy()
        self.assertEqual(23, result.returncode)
        failure = json.loads(result.stdout.splitlines()[0])
        self.assertEqual('create_candidate', failure['phase'])
        self.assertEqual('new-image-id', failure['image_id'])

    def test_other_deployment_lock_leaves_service_and_inflight_transfers_untouched(self):
        directory = Path(self.env['DEPLOY_STATE_DIR'])
        directory.mkdir()
        before = self.state()
        with (directory / 'hof-test.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.deploy()
        self.assertEqual(75, result.returncode, result.stdout + result.stderr)
        self.assertIn('deployment_busy', result.stdout)
        self.assertEqual(before, self.state())
        self.assertTrue(Path(self.env['REMOTE_ENV_FILE']).exists())

    def test_killed_before_commit_rechecks_existing_candidate_without_transfer_files(self):
        self.configure(kill_at='health')
        first = self.deploy()
        self.assertEqual(-signal.SIGKILL, first.returncode)
        for key in ('REMOTE_ENV_FILE', 'REMOTE_FIREBASE_FILE', 'REMOTE_RELEASE_ENV_FILE'):
            Path(self.env[key]).unlink(missing_ok=True)
        result = self.deploy()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(['new-id'], [c['Id'] for c in self.state()['containers']])
        self.assertEqual(1, self.state()['create_count'])
        journal = Path(self.env['DEPLOY_STATE_DIR']) / 'hof-test.json'
        self.assertNotIn('do-not-log-this', journal.read_text())
        self.assertTrue(json.loads(journal.read_text())['committed'])

    def test_killed_at_each_docker_boundary_resumes_without_duplicate_replacement(self):
        for point in ('create-before', 'create-after', 'stop-before', 'stop-after',
                      'rename-before', 'rename-after', 'start-before', 'start-after',
                      'image-tag-before', 'image-tag-after', 'rm-before', 'rm-after'):
            with self.subTest(point=point):
                self.setUp()
                self.configure(kill_at=point)
                first = self.deploy()
                self.assertEqual(-signal.SIGKILL, first.returncode, first.stdout + first.stderr)
                result = self.deploy()
                deadline = time.monotonic() + 3
                while result.returncode == 75 and time.monotonic() < deadline:
                    time.sleep(0.05)
                    result = self.deploy()
                self.assertIn(result.returncode, (0, 1), result.stdout + result.stderr)
                expected = 'new-id' if point in ('start-after', 'image-tag-before', 'image-tag-after', 'rm-before', 'rm-after') else 'old-id'
                self.assertEqual([(expected, '/hof-test', True)], [
                    (c['Id'], c['Name'], c['State']['Running']) for c in self.state()['containers']])
                before = self.state()['containers']
                repeated = self.deploy()
                self.assertEqual(result.returncode, repeated.returncode, repeated.stdout + repeated.stderr)
                self.assertEqual(before, self.state()['containers'])
                self.assertTrue(self.old_secret.exists())

    def test_first_deployment_failure_never_claims_successful_restore(self):
        self.configure(containers=[], fault='start')
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('no_previous_version', result.stdout)
        self.assertNotIn('rollback_healthy', result.stdout)
        self.assertEqual([], self.state()['containers'])

    def test_two_entry_points_replace_only_once(self):
        self.configure(fault='slow-health')
        self.env['DEPLOY_HEALTH_SECONDS'] = '3'
        first = self.start_deploy()
        self.addCleanup(lambda: first.kill() if first.poll() is None else None)
        deadline = time.monotonic() + 5
        while not any(call[0] == 'curl' for call in self.state().get('calls', [])):
            self.assertLess(time.monotonic(), deadline)
            time.sleep(0.02)
        second = self.deploy()
        self.assertEqual(75, second.returncode, second.stdout + second.stderr)
        stdout, stderr = first.communicate(timeout=5)
        self.assertEqual(0, first.returncode, stdout + stderr)
        self.assertEqual(1, self.state()['create_count'])

    def test_recovered_unhealthy_candidate_restores_previous_id(self):
        self.configure(kill_at='health', fault='internal-health')
        self.assertEqual(-signal.SIGKILL, self.deploy().returncode)
        result = self.deploy()
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn('rollback_healthy', result.stdout)
        self.assertEqual([('old-id', True)], [(c['Id'], c['State']['Running']) for c in self.state()['containers']])

    def test_mismatched_journal_or_labels_never_delete_or_start_containers(self):
        for mismatch in ('journal-id', 'label', 'previous-image', 'invalid-json'):
            with self.subTest(mismatch=mismatch):
                self.setUp()
                self.configure(kill_at='health')
                self.assertEqual(-signal.SIGKILL, self.deploy().returncode)
                journal = Path(self.env['DEPLOY_STATE_DIR']) / 'hof-test.json'
                saved = json.loads(journal.read_text())
                containers = self.state()['containers']
                if mismatch == 'label':
                    containers[-1]['Config']['Labels']['app.hof.deployment'] = 'another-deployment'
                elif mismatch == 'previous-image':
                    containers[0]['Image'] = 'unexpected-image'
                elif mismatch == 'journal-id':
                    saved['new'] = 'old-id'
                journal.write_text('invalid' if mismatch == 'invalid-json' else json.dumps(saved))
                self.configure(containers=containers, calls=[])
                result = self.deploy()
                self.assertEqual(77, result.returncode, result.stdout + result.stderr)
                self.assertIn('deployment_result_unknown', result.stdout)
                self.assertEqual(containers, self.state()['containers'])
                self.assertFalse(any(call[1] in ('rm', 'stop', 'start', 'rename', 'create') for call in self.state()['calls']))
                self.assertTrue(Path(self.env['REMOTE_ENV_FILE']).exists())

    def test_different_request_finishes_prior_cleanup_and_preserves_new_transfers(self):
        self.configure(kill_at='image-tag-before')
        self.assertEqual(-signal.SIGKILL, self.deploy().returncode)
        self.env['BUILD_NUMBER'] = '43'
        old_paths = [Path(self.env[key]) for key in ('REMOTE_ENV_FILE', 'REMOTE_FIREBASE_FILE', 'REMOTE_RELEASE_ENV_FILE')]
        transfers = tempfile.TemporaryDirectory(prefix='hof-deploy.', dir='/tmp')
        self.addCleanup(transfers.cleanup)
        incoming = Path(transfers.name)
        for key in ('REMOTE_ENV_FILE', 'REMOTE_FIREBASE_FILE', 'REMOTE_RELEASE_ENV_FILE'):
            path = incoming / Path(self.env[key]).name
            path.write_text('new request credentials')
            self.env[key] = str(path)
        result = self.deploy()
        self.assertEqual(76, result.returncode, result.stdout + result.stderr)
        self.assertIn('prior_deployment_recovered_retry_requested', result.stdout)
        self.assertTrue(Path(self.env['REMOTE_ENV_FILE']).exists())
        self.assertTrue(all(not path.exists() for path in old_paths))
        self.assertEqual(['new-id'], [c['Id'] for c in self.state()['containers']])

    def test_next_build_rejects_a_foreign_container_despite_completed_journal(self):
        self.assertEqual(0, self.deploy().returncode)
        containers = self.state()['containers']
        containers[0]['Id'] = 'foreign-id'
        self.configure(containers=containers, calls=[])
        self.env['BUILD_NUMBER'] = '43'
        result = self.deploy()
        self.assertEqual(77, result.returncode, result.stdout + result.stderr)
        self.assertIn('finished_service_ownership_mismatch', result.stdout)
        self.assertEqual(containers, self.state()['containers'])
        self.assertFalse(any(call[1] in ('rm', 'stop', 'start', 'rename', 'create') for call in self.state()['calls']))

    def test_corrupted_transfer_path_preserves_mounted_credentials_and_new_uploads(self):
        self.assertEqual(0, self.deploy().returncode)
        journal = Path(self.env['DEPLOY_STATE_DIR']) / 'hof-test.json'
        saved = json.loads(journal.read_text())
        secret = Path(saved['secret'])
        saved['transfers'][0] = str(secret)
        journal.write_text(json.dumps(saved))
        self.env['BUILD_NUMBER'] = '43'
        transfers = tempfile.TemporaryDirectory(prefix='hof-deploy.', dir='/tmp')
        self.addCleanup(transfers.cleanup)
        for key in ('REMOTE_ENV_FILE', 'REMOTE_FIREBASE_FILE', 'REMOTE_RELEASE_ENV_FILE'):
            path = Path(transfers.name) / Path(self.env[key]).name
            path.write_text('new request credential')
            self.env[key] = str(path)
        self.configure(calls=[])
        result = self.deploy()
        self.assertEqual(77, result.returncode, result.stdout + result.stderr)
        self.assertIn('invalid_transfer_paths', result.stdout)
        self.assertTrue(secret.exists())
        self.assertTrue(self.old_secret.exists())
        self.assertTrue(Path(self.env['REMOTE_ENV_FILE']).exists())
        self.assertFalse(any(call[1] in ('rm', 'stop', 'start', 'rename', 'create') for call in self.state()['calls']))

    def test_next_build_replaces_verified_completed_service(self):
        first = self.deploy()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        self.env['BUILD_NUMBER'] = '43'
        transfers = tempfile.TemporaryDirectory(prefix='hof-deploy.', dir='/tmp')
        self.addCleanup(transfers.cleanup)
        for key in ('REMOTE_ENV_FILE', 'REMOTE_FIREBASE_FILE', 'REMOTE_RELEASE_ENV_FILE'):
            path = Path(transfers.name) / Path(self.env[key]).name
            path.write_text('new request credential')
            self.env[key] = str(path)
        result = self.deploy()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([('new-id-2', True)], [(c['Id'], c['State']['Running']) for c in self.state()['containers']])
        self.assertEqual(2, self.state()['create_count'])

    def test_compose_replacement_kill_recovers_release_at_each_boundary(self):
        for point in ('rm-before', 'rm-after', 'create-before', 'create-after', 'start-before', 'start-after', 'health'):
            with self.subTest(point=point):
                self.setUp()
                first = self.deploy()
                self.assertEqual(0, first.returncode, first.stdout + first.stderr)
                previous = self.state()['containers'][0]
                self.next_request()
                self.configure(kill_at=point, killed=False, calls=[])
                killed = self.deploy()
                self.assertEqual(-signal.SIGKILL, killed.returncode, killed.stdout + killed.stderr)
                result = self.deploy()
                self.assertIn(result.returncode, (0, 1), result.stdout + result.stderr)
                current = self.state()['containers']
                self.assertEqual(1, len(current))
                self.assertTrue(current[0]['State']['Running'])
                labels = current[0]['Config']['Labels']
                if point not in ('start-after', 'health'):
                    self.assertEqual(previous['Config']['Labels'], labels)
                    self.assertEqual(previous['Image'], current[0]['Image'])
                    self.assertEqual(previous['Mounts'], current[0]['Mounts'])
                else:
                    self.assertNotEqual(previous['Config']['Labels']['app.hof.deployment'], labels['app.hof.deployment'])
                repeated = self.deploy()
                self.assertEqual(result.returncode, repeated.returncode, repeated.stdout + repeated.stderr)
                self.assertEqual(current, self.state()['containers'])

    def test_missing_previous_compose_definition_does_not_stop_service(self):
        self.assertEqual(0, self.deploy().returncode)
        previous = self.state()['containers']
        Path(previous[0]['Config']['Labels']['com.docker.compose.project.config_files']).unlink()
        self.next_request()
        result = self.deploy()
        self.assertEqual(77, result.returncode, result.stdout + result.stderr)
        self.assertEqual(previous, self.state()['containers'])

    def test_finished_rollback_next_build_rejects_changed_previous_identity(self):
        self.configure(fault='internal-health')
        first = self.deploy()
        self.assertEqual(1, first.returncode, first.stdout + first.stderr)
        self.assertIn('rollback_healthy', first.stdout)
        containers = self.state()['containers']
        containers[0]['Id'] = 'foreign-id'
        self.configure(containers=containers, calls=[])
        self.env['BUILD_NUMBER'] = '43'
        result = self.deploy()
        self.assertEqual(77, result.returncode, result.stdout + result.stderr)
        self.assertEqual(containers, self.state()['containers'])

    def test_unknown_stop_is_not_mistaken_for_completed_stop(self):
        self.configure(kill_at='rename-before')
        self.assertEqual(-signal.SIGKILL, self.deploy().returncode)
        directory = Path(self.env['DEPLOY_STATE_DIR'])
        journal = directory / 'hof-test.json'
        saved = json.loads(journal.read_text())
        saved['stop_confirmed'] = False
        saved['phase'] = 'stop_previous'
        journal.write_text(json.dumps(saved))
        receipt = next(directory.glob('*-stop-*.json'))
        receipt.write_text(json.dumps({'id': saved['id'], 'previous': 'old-id', 'exit_code': None}))
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('rollback_failed', result.stdout)
        self.assertFalse(any(call[:2] == ['docker', 'start'] for call in self.state()['calls']))

    def test_prior_boot_stop_cannot_prevent_restoration_after_server_restart(self):
        self.configure(kill_at='rename-before')
        self.assertEqual(-signal.SIGKILL, self.deploy().returncode)
        directory = Path(self.env['DEPLOY_STATE_DIR'])
        journal = directory / 'hof-test.json'
        saved = json.loads(journal.read_text())
        saved.update(stop_confirmed=False, phase='stop_previous', boot_id='before-reboot')
        journal.write_text(json.dumps(saved))
        receipt = next(directory.glob('*-stop-*.json'))
        receipt.write_text(json.dumps({'id': saved['id'], 'previous': 'old-id', 'exit_code': None}))
        # Replace only the host's read-only boot identity; run the actual entry point.
        script = '''import pathlib, runpy, sys
from unittest.mock import patch
exists, read = pathlib.Path.exists, pathlib.Path.read_text
boot = '/proc/sys/kernel/random/boot_id'
with patch.object(pathlib.Path, 'exists', lambda p: True if str(p) == boot else exists(p)), patch.object(pathlib.Path, 'read_text', lambda p: 'after-reboot' if str(p) == boot else read(p)):
    runpy.run_path(sys.argv[1], run_name='__main__')
'''
        result = subprocess.run([sys.executable, '-B', '-c', script, str(SCRIPT)], env=self.env,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn('rollback_healthy', result.stdout)
        self.assertEqual([('old-id', True)], [(c['Id'], c['State']['Running']) for c in self.state()['containers']])


if __name__ == '__main__':
    unittest.main()
