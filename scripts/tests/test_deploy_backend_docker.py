"""Opt-in local Docker smoke: HOF_DEPLOY_DOCKER_TESTS=1 python3 -m unittest ..."""
import json
import os
from pathlib import Path
import socket
import signal
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
import uuid

SCRIPT = Path(__file__).resolve().parents[1] / 'deploy_backend.py'
SERVER = '''from http.server import BaseHTTPRequestHandler, HTTPServer
import json, os, signal, time
def shutdown(signum, frame):
    time.sleep(float(os.environ.get("TEST_STOP_DELAY", "0")))
    os._exit(0)
signal.signal(signal.SIGTERM, shutdown)
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(json.dumps({"status": os.environ.get("TEST_HEALTH", "UP"),
            "components": {"nested": {"status": "UP"}}}).encode())
HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
'''


@unittest.skipUnless(os.environ.get('HOF_DEPLOY_DOCKER_TESTS') == '1', 'explicit local Docker smoke only')
class RealDockerDeploymentTest(unittest.TestCase):
    @classmethod
    def docker(cls, *args):
        return subprocess.check_output(['docker', *args], text=True, timeout=60).strip()

    @classmethod
    def setUpClass(cls):
        cls.label = 'hof-deployment-smoke-' + uuid.uuid4().hex
        cls.image = cls.label + ':test'
        cls.next_image = cls.label + ':next'
        with tempfile.TemporaryDirectory(prefix=cls.label) as directory:
            context = Path(directory)
            (context / 'server.py').write_text(SERVER)
            # A stopped fixture container uses the pre-pulled immutable image ID.
            base = cls.docker('image', 'inspect', 'python:3.12-alpine', '--format', '{{.Id}}')
            fixture = cls.docker('create', '--name', cls.label + '-fixture', base)
            try:
                cls.docker('cp', str(context / 'server.py'), fixture + ':/server.py')
                cls.docker('commit', '--change', 'CMD ["python3", "/server.py"]', fixture, cls.image)
                cls.docker('commit', '--change', 'CMD ["python3", "/server.py"]',
                           '--change', 'LABEL app.test.release=next', fixture, cls.next_image)
            finally:
                cls.docker('rm', fixture)

    @classmethod
    def tearDownClass(cls):
        cls.docker('image', 'rm', cls.image, cls.next_image)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix=self.label)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.name = self.label + '-' + uuid.uuid4().hex[:8]
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            self.port = probe.getsockname()[1]
        self.url = 'http://127.0.0.1:' + str(self.port) + '/actuator/health'
        self.release = self.root / 'releases'
        self.release.mkdir()
        self.old_secret = self.root / 'previous.json'
        self.old_secret.write_text('{"test":true}')
        self.stop_delay = {'test_real_pending_stop_finishes_before_restoration': '3',
                          'test_real_killed_parent_keeps_stop_lock_and_resumes_after_response': '3',
                          'test_real_forced_stop_finishes_before_restoration': '30',
                          'test_real_unconfirmed_stop_never_restarts_previous_container': '30'}.get(self._testMethodName, '0')
        self.old = self.docker('run', '-d', '--name', self.name,
            '--label', 'app.test=' + self.label, '--publish', '127.0.0.1:' + str(self.port) + ':8080',
            '--env', 'TEST_STOP_DELAY=' + self.stop_delay,
            '--mount', 'type=bind,src=' + str(self.old_secret) + ',dst=/run/secrets/firebase-service-account.json,readonly',
            self.image)
        self.env = {**os.environ, 'IMAGE': self.image, 'IMAGE_REPOSITORY': self.label,
            'IMAGE_ID': self.docker('image', 'inspect', self.image, '--format', '{{.Id}}'),
            'CONTAINER_NAME': self.name, 'BACKEND_BIND_ADDRESS': '127.0.0.1',
            'HOST_PORT': str(self.port), 'CONTAINER_PORT': '8080', 'BUILD_NUMBER': 'smoke',
            'SERVER_FORWARD_HEADERS_STRATEGY': 'NONE', 'PUBLIC_HEALTH_URL': self.url,
            'HOF_AUTH_ALLOWED_ORIGIN_PATTERNS': 'chrome-extension://*',
            'RELEASE_HOST_DIR': str(self.release), 'RELEASE_CONTAINER_DIR': '/var/lib/hof/releases',
            'DEPLOY_SECRET_DIR': str(self.root / 'secrets'), 'DEPLOY_HEALTH_SECONDS': '4',
            'DEPLOY_STATE_DIR': str(self.root / 'deployment-state'),
        }
        transfers = tempfile.TemporaryDirectory(prefix='hof-deploy.', dir='/tmp')
        self.addCleanup(transfers.cleanup)
        for key, filename, content in [('REMOTE_ENV_FILE', 'backend.env', 'TEST_HEALTH=UP\n'),
                             ('REMOTE_FIREBASE_FILE', 'firebase.json', '{"test":true}'),
                             ('REMOTE_RELEASE_ENV_FILE', 'release.env', 'HOF_RELEASE_PUBLISH_TOKEN=smoke-only\n')]:
            path = Path(transfers.name) / filename
            path.write_text(content)
            self.env[key] = str(path)
        self.addCleanup(self.remove_containers)
        self.wait_healthy()

    def remove_containers(self):
        ids = self.docker('container', 'ls', '--all', '--filter', 'name=^/' + self.name, '--format', '{{.ID}}').splitlines()
        if ids:
            self.docker('rm', '--force', *ids)
        tags = self.docker('image', 'ls', '--format', '{{.Repository}}:{{.Tag}}').splitlines()
        if self.label + ':rollback' in tags:
            self.docker('image', 'rm', self.label + ':rollback')

    def wait_healthy(self):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(self.url, timeout=1) as response:
                    if json.load(response)['status'] == 'UP':
                        return
            except (OSError, ValueError):
                pass
            time.sleep(0.1)
        self.fail('Disposable HTTP service did not become healthy')

    def deploy(self):
        return subprocess.run([sys.executable, str(SCRIPT)], env=self.env,
                              capture_output=True, text=True, timeout=45)

    def test_real_unhealthy_replacement_restores_identical_old_container(self):
        Path(self.env['REMOTE_ENV_FILE']).write_text('TEST_HEALTH=DOWN\n')
        result = self.deploy()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('rollback_healthy', result.stdout)
        current = json.loads(self.docker('container', 'inspect', self.name))[0]
        self.assertEqual(self.old, current['Id'])
        self.assertTrue(current['State']['Running'])
        self.assertTrue(self.old_secret.exists())
        self.wait_healthy()

    def test_real_pending_stop_finishes_before_restoration(self):
        self.env['DEPLOY_COMMAND_SECONDS'] = '1'
        started = time.monotonic()
        result = self.deploy()
        self.assertEqual(124, result.returncode, result.stdout + result.stderr)
        self.assertIn('rollback_healthy', result.stdout)
        self.assertGreaterEqual(time.monotonic() - started, 3)
        current = json.loads(self.docker('container', 'inspect', self.name))[0]
        self.assertEqual(self.old, current['Id'])
        self.assertTrue(current['State']['Running'])
        time.sleep(1)
        self.wait_healthy()

    def test_real_forced_stop_finishes_before_restoration(self):
        self.env['DEPLOY_COMMAND_SECONDS'] = '1'
        self.env['DEPLOY_HEALTH_SECONDS'] = '15'
        since = str(time.time())
        result = self.deploy()
        self.assertEqual(124, result.returncode, result.stdout + result.stderr)
        self.assertIn('rollback_healthy', result.stdout)
        events = self.docker('events', '--since', since, '--until', str(time.time()),
                             '--filter', 'container=' + self.old, '--format', '{{.Action}}').splitlines()
        # The daemon publishes die/stop on different paths; both must precede restart.
        self.assertLess(events.index('die'), events.index('start'), events)
        self.assertLess(events.index('stop'), events.index('start'), events)
        self.wait_healthy()

    def test_real_unconfirmed_stop_never_restarts_previous_container(self):
        self.env['DEPLOY_COMMAND_SECONDS'] = '1'
        self.env['DEPLOY_HEALTH_SECONDS'] = '1'
        since = str(time.time())
        result = self.deploy()
        self.assertEqual(124, result.returncode, result.stdout + result.stderr)
        self.assertIn('stop_outcome_unknown', result.stdout)
        self.assertNotIn('rollback_healthy', result.stdout)
        events = self.docker('events', '--since', since, '--until', str(time.time()),
                             '--filter', 'container=' + self.old, '--format', '{{.Action}}').splitlines()
        self.assertNotIn('start', events)

    def test_real_success_preserves_mount_and_logging_contract(self):
        result = self.deploy()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        current = json.loads(self.docker('container', 'inspect', self.name))[0]
        self.assertNotEqual(self.old, current['Id'])
        self.assertEqual(self.name, current['Config']['Labels'].get('com.docker.compose.project'))
        self.assertEqual('backend', current['Config']['Labels'].get('com.docker.compose.service'))
        self.assertEqual({'Type': 'json-file', 'Config': {'max-file': '5', 'max-size': '10m'}}, current['HostConfig']['LogConfig'])
        self.assertEqual('unless-stopped', current['HostConfig']['RestartPolicy']['Name'])
        mounts = {m['Destination']: m for m in current['Mounts']}
        secret = mounts['/run/secrets/firebase-service-account.json']
        self.assertFalse(secret['RW'])
        self.assertEqual(0o600, Path(secret['Source']).stat().st_mode & 0o777)
        self.assertEqual(str(self.release), mounts['/var/lib/hof/releases']['Source'])
        self.assertFalse(mounts['/var/lib/hof/releases']['RW'])
        self.wait_healthy()

    def next_request(self, content='TEST_HEALTH=UP\n'):
        transfers = tempfile.TemporaryDirectory(prefix='hof-deploy.', dir='/tmp')
        self.addCleanup(transfers.cleanup)
        for key, filename, value in [('REMOTE_ENV_FILE', 'backend.env', content),
                                    ('REMOTE_FIREBASE_FILE', 'firebase.json', '{"test":"next"}'),
                                    ('REMOTE_RELEASE_ENV_FILE', 'release.env', 'HOF_RELEASE_PUBLISH_TOKEN=next-only\n')]:
            path = Path(transfers.name) / filename
            path.write_text(value)
            self.env[key] = str(path)
        self.env['BUILD_NUMBER'] += '-next'
        self.env['IMAGE'] = self.next_image
        self.env['IMAGE_ID'] = self.docker('image', 'inspect', self.next_image, '--format', '{{.Id}}')

    def test_real_compose_repeat_failure_restores_prior_release_configuration(self):
        Path(self.env['REMOTE_ENV_FILE']).write_text('TEST_HEALTH=UP\nTEST_LITERAL=$UNCHANGED"quoted"\n')
        first = self.deploy()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        previous = json.loads(self.docker('inspect', self.name))[0]
        self.assertEqual(self.name, previous['Config']['Labels'].get('com.docker.compose.project'))
        self.next_request('TEST_HEALTH=DOWN\nTEST_LITERAL=new-value\n')
        result = self.deploy()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('rollback_healthy', result.stdout)
        restored = json.loads(self.docker('inspect', self.name))[0]
        self.assertNotEqual(previous['Id'], restored['Id'])
        self.assertEqual(previous['Image'], restored['Image'])
        self.assertCountEqual(previous['Config']['Env'], restored['Config']['Env'])
        self.assertCountEqual(previous['Mounts'], restored['Mounts'])
        self.assertEqual(previous['HostConfig']['LogConfig'], restored['HostConfig']['LogConfig'])
        self.assertIn('TEST_LITERAL=$UNCHANGED"quoted"', restored['Config']['Env'])
        self.assertTrue(Path(restored['Config']['Labels']['com.docker.compose.project.config_files']).is_file())
        self.wait_healthy()
        self.next_request()
        repeated = self.deploy()
        self.assertEqual(0, repeated.returncode, repeated.stdout + repeated.stderr)
        self.assertEqual(self.env['IMAGE_ID'], self.docker('inspect', self.name, '--format', '{{.Image}}'))
        self.wait_healthy()

    def test_real_killed_compose_replacement_restores_prior_release(self):
        first = self.deploy()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        previous = json.loads(self.docker('inspect', self.name))[0]
        self.next_request()
        shim_dir = self.root / 'bin'
        shim_dir.mkdir()
        shim = shim_dir / 'docker'
        shim.write_text('#!' + sys.executable + '\n' + '''import os, pathlib, signal, subprocess, sys
args = sys.argv[1:]
code = subprocess.call([os.environ['REAL_DOCKER'], *args])
receipt = pathlib.Path(os.environ['TEST_KILL_RECEIPT'])
if code == 0 and args[0] == 'compose' and 'create' in args and not receipt.exists():
    receipt.write_text('created before parent termination')
    os.kill(os.getppid(), signal.SIGKILL)
sys.exit(code)
''')
        shim.chmod(0o700)
        self.env.update(PATH=str(shim_dir) + os.pathsep + os.environ['PATH'],
                        REAL_DOCKER=shutil.which('docker'), TEST_KILL_RECEIPT=str(self.root / 'killed'))
        killed = self.deploy()
        self.assertEqual(-signal.SIGKILL, killed.returncode, killed.stdout + killed.stderr)
        resumed = self.deploy()
        self.assertEqual(1, resumed.returncode, resumed.stdout + resumed.stderr)
        self.assertIn('rollback_healthy', resumed.stdout)
        restored = json.loads(self.docker('inspect', self.name))[0]
        self.assertNotEqual(previous['Id'], restored['Id'])
        self.assertEqual(previous['Image'], restored['Image'])
        self.assertCountEqual(previous['Config']['Env'], restored['Config']['Env'])
        self.assertCountEqual(previous['Mounts'], restored['Mounts'])
        self.wait_healthy()

    def test_real_killed_parent_keeps_stop_lock_and_resumes_after_response(self):
        self.env['DEPLOY_HEALTH_SECONDS'] = '6'
        first = subprocess.Popen([sys.executable, str(SCRIPT)], env=self.env,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(lambda: first.kill() if first.poll() is None else None)
        directory = Path(self.env['DEPLOY_STATE_DIR'])
        deadline = time.monotonic() + 10
        while not list(directory.glob('*-stop-*.json')):
            self.assertLess(time.monotonic(), deadline)
            time.sleep(0.02)
        first.kill()
        first.communicate(timeout=3)
        self.assertEqual(-signal.SIGKILL, first.returncode)
        second = self.deploy()
        self.assertEqual(75, second.returncode, second.stdout + second.stderr)
        receipt = next(directory.glob('*-stop-*.json'))
        while json.loads(receipt.read_text())['exit_code'] is None:
            self.assertLess(time.monotonic(), deadline)
            time.sleep(0.1)
        result = self.deploy()
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn('rollback_healthy', result.stdout)
        current = json.loads(self.docker('container', 'inspect', self.name))[0]
        self.assertEqual(self.old, current['Id'])
        self.wait_healthy()
        repeated = self.deploy()
        self.assertEqual(1, repeated.returncode, repeated.stdout + repeated.stderr)
        self.wait_healthy()


if __name__ == '__main__':
    unittest.main()
