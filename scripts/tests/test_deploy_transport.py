"""Execute Jenkins' deploy shell against an SSH/SCP boundary, never a server."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / 'Jenkinsfile').read_text()
DEPLOY = SOURCE.split("stage('Deploy')", 1)[1].split("'''", 2)[1]
FAKE = '''import json, os, pathlib, sys
path = pathlib.Path(os.environ['TRANSPORT_STATE'])
state = json.loads(path.read_text())
name, args = pathlib.Path(sys.argv[0]).name, sys.argv[1:]
state['calls'].append([name, *args])
code = 0
if name == 'scp' and state.get('fail_scp') and sum(call[0] == 'scp' for call in state['calls']) == 2:
    code = 23
if name == 'ssh' and 'mktemp -d' in args[-1]:
    print('/tmp/hof-deploy.Test123')
elif name == 'ssh' and args[-1].endswith('python3 -'):
    state['payloads'].append(sys.stdin.read())
    code = state['codes'].pop(0)
elif name == 'ssh':
    sys.stdin.read()
path.write_text(json.dumps(state))
sys.exit(code)
'''


class DeploymentTransportTest(unittest.TestCase):
    def run_transport(self, codes, fail_scp=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / 'transport.json'
            state.write_text(json.dumps({'codes': codes, 'calls': [], 'payloads': [], 'fail_scp': fail_scp}))
            for name in ('ssh', 'scp', 'sleep'):
                executable = root / name
                executable.write_text('#!' + sys.executable + '\n' + FAKE)
                executable.chmod(0o700)
            keys = ('SSH_KEY_FILE', 'SSH_KNOWN_HOSTS_FILE', 'DEPLOY_TARGET', 'HOF_ENV_FILE',
                    'HOF_FIREBASE_FILE', 'IMAGE', 'IMAGE_ID', 'IMAGE_DIGEST', 'IMAGE_REPOSITORY', 'CONTAINER_NAME',
                    'BACKEND_BIND_ADDRESS', 'HOST_PORT', 'CONTAINER_PORT', 'SERVER_FORWARD_HEADERS_STRATEGY',
                    'PUBLIC_HEALTH_URL', 'HOF_AUTH_ALLOWED_ORIGIN_PATTERNS', 'RELEASE_HOST_DIR',
                    'RELEASE_CONTAINER_DIR', 'BUILD_NUMBER')
            env = {**os.environ, **dict.fromkeys(keys, 'test-only'),
                   'HOF_RELEASE_PUBLISH_TOKEN': 'never-log-this-token', 'TRANSPORT_STATE': str(state),
                   'PATH': str(root) + os.pathsep + os.environ['PATH']}
            result = subprocess.run(['bash', '-c', DEPLOY], env=env, cwd=ROOT,
                                    stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=10)
            self.assertNotIn('never-log-this-token', result.stdout + result.stderr)
            observed = json.loads(state.read_text())
            self.assertTrue(all(payload == (ROOT / 'scripts/deploy_backend.py').read_text()
                                for payload in observed['payloads']))
            self.assertEqual(fail_scp, any('rm -f' in call[-1] for call in observed['calls']))
            return result, observed

    def test_disconnect_then_busy_rechecks_the_same_deployment_until_confirmed(self):
        result, state = self.run_transport([255, 75, 0])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        commands = [call[-1] for call in state['calls'] if call[-1].endswith('python3 -')]
        self.assertEqual(3, len(commands))
        self.assertEqual(1, len(set(commands)))
        self.assertIn("IMAGE_DIGEST='test-only'", commands[0])

    def test_exhausted_rechecks_report_unknown_instead_of_confirmed_failure(self):
        result, state = self.run_transport([255, 75, 255])
        self.assertEqual(77, result.returncode, result.stdout + result.stderr)
        self.assertIn('deployment_result_unknown', result.stdout)
        self.assertEqual(3, len(state['payloads']))

    def test_confirmed_remote_failure_keeps_its_code_without_replacement_retry(self):
        result, state = self.run_transport([23])
        self.assertEqual(23, result.returncode, result.stdout + result.stderr)
        self.assertEqual(1, len(state['payloads']))

    def test_recovered_prior_request_retries_current_request(self):
        result, state = self.run_transport([76, 0])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(2, len(state['payloads']))

    def test_failed_upload_cleans_only_its_private_directory_before_remote_start(self):
        result, state = self.run_transport([], fail_scp=True)
        self.assertEqual(23, result.returncode, result.stdout + result.stderr)
        self.assertEqual([], state['payloads'])
        cleanup = state['calls'][-1]
        self.assertEqual('ssh', cleanup[0])
        self.assertIn("rmdir -- '/tmp/hof-deploy.Test123'", cleanup[-1])
