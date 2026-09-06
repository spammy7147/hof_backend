import json
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
            'IMAGE': 'hof-test:new', 'IMAGE_REPOSITORY': 'hof-test', 'CONTAINER_NAME': 'hof-test',
            'BACKEND_BIND_ADDRESS': '127.0.0.1', 'HOST_PORT': '18080', 'CONTAINER_PORT': '8080',
            'SERVER_FORWARD_HEADERS_STRATEGY': 'NONE', 'PUBLIC_HEALTH_URL': 'https://test.invalid/health',
            'HOF_AUTH_ALLOWED_ORIGIN_PATTERNS': 'chrome-extension://*',
            'RELEASE_HOST_DIR': str(self.root / 'releases'), 'RELEASE_CONTAINER_DIR': '/var/lib/hof/releases',
            'BUILD_NUMBER': '42', 'DEPLOY_HEALTH_SECONDS': '1', 'DEPLOY_COMMAND_SECONDS': '1',
        }
        for name in ('REMOTE_ENV_FILE', 'REMOTE_FIREBASE_FILE', 'REMOTE_RELEASE_ENV_FILE'):
            path = self.root / name
            path.write_text('SENSITIVE_TEST_VALUE=do-not-log-this\n')
            path.chmod(0o600)
            self.env[name] = str(path)

    def state(self):
        return json.loads(self.state_path.read_text())

    def configure(self, **changes):
        self.state_path.write_text(json.dumps({**self.state(), **changes}))

    def deploy(self):
        result = subprocess.run([sys.executable, str(SCRIPT)], env=self.env,
                                capture_output=True, text=True, timeout=10)
        self.assertNotIn('do-not-log-this', result.stdout + result.stderr)
        return result

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

    def test_docker_create_failure_leaves_previous_service_and_secret_unchanged(self):
        self.configure(fault='create')
        before = self.state()['containers']
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.state()['containers'])
        self.assertTrue(self.old_secret.exists())

    def test_docker_timeout_after_stop_restores_previous_service(self):
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

    def test_first_deployment_failure_never_claims_successful_restore(self):
        self.configure(containers=[], fault='start')
        result = self.deploy()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('no_previous_version', result.stdout)
        self.assertNotIn('rollback_healthy', result.stdout)
        self.assertEqual([], self.state()['containers'])


if __name__ == '__main__':
    unittest.main()
