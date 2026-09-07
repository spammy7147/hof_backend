"""Execute the actual Jenkins image transfer, including both pipefail boundaries."""
import io
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TRANSFER = (ROOT / 'Jenkinsfile').read_text().split("stage('Transfer Image')", 1)[1].split("'''", 2)[1]
FAKE = '''import io, json, os, pathlib, subprocess, sys, tarfile
name, args = pathlib.Path(sys.argv[0]).name, sys.argv[1:]
fault = os.environ['TRANSFER_FAULT']
if name == 'docker':
    if args[0] == 'load':
        data = sys.stdin.buffer.read()
        pathlib.Path('received').write_bytes(data)
        try:
            with tarfile.open(fileobj=io.BytesIO(data)) as archive:
                for member in archive.getmembers():
                    if member.isfile(): archive.extractfile(member).read()
        except tarfile.TarError:
            sys.exit(22)
        sys.exit(23 if fault == 'load' else 0)
    if args[:2] == ['image', 'inspect']:
        print('wrong-image' if fault == 'mismatch' else os.environ['IMAGE_ID'])
        sys.exit(0)
    sys.exit(99)
if name == 'gzip':
    if fault == 'gzip': sys.exit(24)
    if fault == 'corrupt-gzip':
        sys.stdout.buffer.write(b'not a gzip stream')
        sys.exit(0)
    os.execv(os.environ['REAL_GZIP'], ['gzip', *args])
if name == 'ssh':
    if fault == 'ssh': sys.exit(255)
    sys.exit(subprocess.call(['bash', '-c', args[-1]]))
sys.exit(99)
'''


class ImageTransportTest(unittest.TestCase):
    def transfer(self, fault=''):
        import shutil
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'build').mkdir()
            stream = io.BytesIO()
            with tarfile.open(fileobj=stream, mode='w') as archive:
                member = tarfile.TarInfo('fixture-layer')
                member.size = 32768
                archive.addfile(member, io.BytesIO(b'x' * member.size))
            payload = stream.getvalue()
            if fault == 'truncated-tar': payload = payload[:1024]
            if fault != 'missing-tar':
                (root / 'build/jib-image.tar').write_bytes(payload)
            for name in ('ssh', 'docker', 'gzip'):
                executable = root / name
                executable.write_text('#!' + sys.executable + '\n' + FAKE)
                executable.chmod(0o700)
            env = {**os.environ, 'PATH': str(root) + os.pathsep + os.environ['PATH'],
                   'REAL_GZIP': shutil.which('gzip'), 'TRANSFER_FAULT': fault,
                   'IMAGE': 'hof-test:42-revision', 'IMAGE_ID': 'sha256:' + 'a' * 64,
                   'SSH_KEY_FILE': 'test-key', 'SSH_KNOWN_HOSTS_FILE': 'known-hosts',
                   'DEPLOY_TARGET': 'test@invalid'}
            result = subprocess.run(['bash', '-c', TRANSFER], env=env, cwd=root,
                                    capture_output=True, text=True, timeout=10)
            if not fault:
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(payload, (root / 'received').read_bytes())
            else:
                self.assertNotEqual(0, result.returncode, fault)
                if fault == 'mismatch':
                    self.assertIn('image ID mismatch', result.stdout + result.stderr)

    def test_loaded_id_must_match_local_jib_image_id(self):
        self.transfer()
        self.transfer('mismatch')

    def test_compression_transport_load_and_missing_tar_fail_the_stage(self):
        for fault in ('gzip', 'corrupt-gzip', 'ssh', 'load', 'missing-tar', 'truncated-tar'):
            with self.subTest(fault=fault):
                self.transfer(fault)
