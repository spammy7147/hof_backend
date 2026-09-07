#!/usr/bin/env python3
"""Single-instance HOF replacement, called by Jenkins over SSH (stdlib only)."""
import json
import fcntl
import math
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import tempfile
import uuid


class DeploymentError(Exception):
    def __init__(self, reason, exit_code=1):
        self.exit_code = exit_code
        super().__init__(reason)


class Interrupted(Exception):
    def __init__(self, signum):
        self.exit_code = 128 + signum
        super().__init__('signal_' + signal.Signals(signum).name)


class DeploymentBusy(DeploymentError):
    def __init__(self):
        super().__init__('server_deployment_lock_held', 75)


class UnsafeState(DeploymentError):
    def __init__(self, reason):
        super().__init__(reason, 77)


def atomic_json(path, value):
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, prefix=path.name + '.', delete=False) as temporary:
        try:
            json.dump(value, temporary)
            temporary.flush()
            os.fsync(temporary.fileno())
            temporary.close()
            os.replace(temporary.name, path)
            directory = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            Path(temporary.name).unlink(missing_ok=True)


def command(args, seconds, lock_fd=None):
    # Kill the client process group on cancellation/timeout; Docker state is
    # subsequently inspected because stopping a client does not undo its RPC.
    deadline = time.monotonic() + seconds
    process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               text=True, start_new_session=True,
                               pass_fds=() if lock_fd is None else (lock_fd,))
    try:
        output, _ = process.communicate(timeout=max(0, deadline - time.monotonic()))
    except BaseException:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.communicate(timeout=1)
        raise
    if process.returncode:
        # Arguments and Docker stderr can contain environment/credential data.
        raise DeploymentError(args[0] + '_failed', process.returncode if process.returncode > 0 else 128 - process.returncode)
    return output.strip()


def report(result, **fields):
    print(json.dumps({'result': result, **fields}), flush=True)


def transfer_paths(values):
    if not isinstance(values, list) or len(values) != 3 or any(not isinstance(value, str) for value in values):
        raise UnsafeState('invalid_transfer_paths')
    paths = [Path(value) for value in values]
    parent = paths[0].parent
    if (parent.parent != Path('/tmp') or not re.fullmatch(r'hof-deploy\.[A-Za-z0-9_]+', parent.name)
            or parent.is_symlink() or any(path.parent != parent for path in paths)
            or {path.name for path in paths} != {'backend.env', 'firebase.json', 'release.env'}):
        raise UnsafeState('invalid_transfer_paths')
    return paths


class Deployment:
    def __init__(self):
        self.env = dict(os.environ)
        required = ('IMAGE', 'IMAGE_ID', 'IMAGE_REPOSITORY', 'CONTAINER_NAME', 'BACKEND_BIND_ADDRESS',
                    'HOST_PORT', 'CONTAINER_PORT', 'SERVER_FORWARD_HEADERS_STRATEGY',
                    'PUBLIC_HEALTH_URL', 'HOF_AUTH_ALLOWED_ORIGIN_PATTERNS', 'RELEASE_HOST_DIR',
                    'RELEASE_CONTAINER_DIR', 'REMOTE_ENV_FILE', 'REMOTE_FIREBASE_FILE',
                    'REMOTE_RELEASE_ENV_FILE', 'BUILD_NUMBER')
        if any(not self.env.get(key) for key in required):
            raise DeploymentError('missing_deployment_configuration')
        self.name = self.env['CONTAINER_NAME']
        self.build = self.env['BUILD_NUMBER']
        if not all(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', value) for value in (self.name, self.build)):
            raise DeploymentError('invalid_deployment_name')
        self.id = self.build + '-' + uuid.uuid4().hex
        self.candidate = self.name + '-candidate-' + self.id
        self.backup = self.name + '-rollback-' + self.id
        self.secret_dir = Path(self.env.get('DEPLOY_SECRET_DIR', Path.home() / '.config/hof/secrets'))
        self.secret = self.secret_dir / ('firebase-service-account-' + self.id + '.json')
        self.command_seconds = self.seconds('DEPLOY_COMMAND_SECONDS', 30)
        self.health_seconds = self.seconds('DEPLOY_HEALTH_SECONDS', 120)
        self.previous = None
        self.release = None
        self.new = None
        self.image_id = None
        self.touched_previous = False
        self.committed = False
        self.preflight_complete = False
        self.phase = 'preflight'
        self.stop_child = None
        self.stop_confirmed = False
        self.state_dir = Path(self.env.get('DEPLOY_STATE_DIR', Path.home() / '.local/state/hof-deploy')).resolve()
        self.lock_file = None
        self.lock_acquired = False
        self.journal_started = False
        self.manage = False
        self.finished = False
        self.resume_code = 0
        self.cleanup_incoming = True
        self.recovering_prior = False
        self.incoming_transfers = [Path(self.env[key]) for key in
                                  ('REMOTE_ENV_FILE', 'REMOTE_FIREBASE_FILE', 'REMOTE_RELEASE_ENV_FILE')]
        self.transfer_paths = self.incoming_transfers
        boot_path = Path('/proc/sys/kernel/random/boot_id')
        self.boot_id = boot_path.read_text().strip() if boot_path.exists() else None
        self.saved_boot_id = self.boot_id

    @property
    def journal(self):
        return self.state_dir / (self.name + '.json')

    def checkpoint(self, phase=None):
        if phase:
            self.phase = phase
        previous = ({key: self.previous[key] for key in ('Id', 'Image', 'Name', 'Release', 'Health')
                     if key in self.previous} if self.previous else None)
        # Never persist a complete Docker inspect: Config.Env contains secrets.
        value = {'version': 2, 'id': self.id, 'build': self.build, 'name': self.name,
                 'release': str(self.release) if self.release else None,
                 'image_ref': self.env['IMAGE'], 'image_id': self.image_id, 'previous': previous,
                 'new': self.new, 'secret': str(self.secret), 'phase': self.phase,
                 'touched_previous': self.touched_previous, 'stop_confirmed': self.stop_confirmed,
                 'committed': self.committed, 'finished': self.finished,
                 'boot_id': self.saved_boot_id,
                 'transfers': [str(path) for path in self.transfer_paths],
                 'health': {key: self.env[key] for key in ('BACKEND_BIND_ADDRESS', 'HOST_PORT', 'PUBLIC_HEALTH_URL', 'IMAGE_REPOSITORY')}}
        atomic_json(self.journal, value)
        self.journal_started = True

    def find_candidate(self):
        if self.new:
            return self.find(container_id=self.new)
        candidate = self.find(self.name if self.release else self.candidate)
        if candidate and (candidate['Config'].get('Labels') or {}).get('app.hof.deployment') == self.id:
            return candidate
        return None

    def validate_ownership(self):
        candidate = self.find_candidate()
        if candidate:
            if ((candidate['Config'].get('Labels') or {}).get('app.hof.deployment') != self.id
                    or candidate['Image'] != self.image_id
                    or candidate['Name'] not in ('/' + self.name, '/' + self.candidate)):
                raise UnsafeState('candidate_ownership_mismatch')
            self.new = candidate['Id']
        elif self.committed:
            raise UnsafeState('committed_container_missing')
        if self.previous:
            previous = self.find(container_id=self.previous['Id'])
            if previous is None and self.previous.get('Release'):
                restored = self.find(self.name)
                if restored and self.matches_release(restored, Path(self.previous['Release'])):
                    previous = restored
                    self.previous['Id'] = restored['Id']
            if previous is None and not self.committed and not self.previous.get('Release'):
                raise UnsafeState('previous_container_missing')
            if previous and (previous['Image'] != self.previous['Image']
                             or previous['Name'] not in ('/' + self.name, '/' + self.backup)):
                raise UnsafeState('previous_ownership_mismatch')
            if previous and previous['State']['Running'] and candidate and candidate['State']['Running']:
                raise UnsafeState('both_versions_running')
        occupant = self.find(self.name)
        allowed = {self.new, self.previous['Id'] if self.previous else None}
        if occupant and occupant['Id'] not in allowed:
            raise UnsafeState('service_name_ownership_mismatch')
        return candidate

    def resume(self):
        if not self.journal.exists():
            return False
        try:
            saved = json.loads(self.journal.read_text())
            if saved['version'] not in (1, 2) or saved['name'] != self.name or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', saved['id']):
                raise ValueError()
            if any(type(saved[key]) is not bool for key in ('finished', 'committed', 'touched_previous', 'stop_confirmed')):
                raise ValueError()
            if set(saved['health']) != {'BACKEND_BIND_ADDRESS', 'HOST_PORT', 'PUBLIC_HEALTH_URL', 'IMAGE_REPOSITORY'}:
                raise ValueError()
            same_request = saved['build'] == self.build and saved['image_ref'] == self.env['IMAGE']
            paths = transfer_paths(saved['transfers'])
            if saved['finished'] and not same_request:
                self.verify_finished(saved)
                # A crash may occur after the terminal marker but before transfer cleanup.
                self.remove_transfers(paths)
                return False
            self.id, self.build = saved['id'], saved['build']
            self.candidate = self.name + '-candidate-' + self.id
            self.backup = self.name + '-rollback-' + self.id
            self.secret = self.secret_dir / ('firebase-service-account-' + self.id + '.json')
            if saved['secret'] != str(self.secret):
                raise ValueError()
            self.env.update(saved['health'])
            self.env['IMAGE'] = saved['image_ref']
            self.image_id, self.new, self.previous = saved['image_id'], saved['new'], saved['previous']
            self.release = self.validate_release_path(saved['release']) if saved.get('release') else None
            if self.previous and self.previous.get('Release'):
                self.validate_release_path(self.previous['Release'])
            self.phase = saved['phase']
            self.touched_previous, self.stop_confirmed = saved['touched_previous'], saved['stop_confirmed']
            self.committed, self.finished = saved['committed'], saved['finished']
            self.saved_boot_id = saved['boot_id']
            self.transfer_paths = paths
            self.journal_started = True
            self.preflight_complete = True
            candidate = self.validate_ownership()
        except (ValueError, KeyError, TypeError, OSError) as error:
            raise UnsafeState('invalid_deployment_journal') from error
        self.manage = True
        self.cleanup_incoming = same_request
        self.recovering_prior = not same_request
        if self.committed:
            if candidate['Name'] != '/' + self.name or not all(self.health(self.new, self.image_id)):
                raise UnsafeState('committed_service_unhealthy')
            report('already_deployed', deployment_id=self.id, container_id=self.new)
        elif candidate and candidate['State']['Running'] and candidate['Name'] == '/' + self.name:
            if not all(self.health(self.new, self.image_id)):
                raise DeploymentError('resumed_candidate_unhealthy')
            self.committed = True
            self.checkpoint('committed')
            report('deployed', deployment_id=self.id, container_id=self.new, resumed=True)
        else:
            self.resume_code = 1
        if not same_request:
            self.resume_code = 76
        return True

    def verify_finished(self, saved):
        current = self.find(self.name)
        if saved['committed']:
            valid = (current and current['Id'] == saved['new'] and current['Image'] == saved['image_id']
                     and (current['Config'].get('Labels') or {}).get('app.hof.deployment') == saved['id'])
            if saved['previous'] and self.find(container_id=saved['previous']['Id']):
                raise UnsafeState('finished_previous_container_still_present')
        elif saved['previous']:
            valid = (current and current['Id'] == saved['previous']['Id'] and
                     current['Image'] == saved['previous']['Image'])
        else:
            valid = current is None
        if not valid or self.find(self.name + '-candidate-' + saved['id']):
            raise UnsafeState('finished_service_ownership_mismatch')
        if not saved['committed'] and saved['new'] and self.find(container_id=saved['new']):
            raise UnsafeState('finished_candidate_still_present')

    def seconds(self, key, default):
        value = float(self.env.get(key, default))
        if not math.isfinite(value) or value <= 0:
            raise DeploymentError('invalid_timeout')
        return value

    def docker(self, *args, seconds=None):
        return command(['docker', *args], self.command_seconds if seconds is None else seconds,
                       self.lock_file.fileno())

    @property
    def stop_receipt(self):
        return self.state_dir / (self.name + '-stop-' + self.id + '.json')

    def wait_stop(self, seconds):
        deadline = time.monotonic() + seconds
        while True:
            if self.stop_receipt.exists():
                receipt = json.loads(self.stop_receipt.read_text())
                if receipt['id'] != self.id or receipt['previous'] != self.previous['Id']:
                    raise UnsafeState('stop_receipt_mismatch')
                if receipt['exit_code'] is not None:
                    if receipt['exit_code'] != 0:
                        raise DeploymentError('stop_outcome_unknown')
                    self.stop_confirmed = True
                    if self.stop_child:
                        os.waitpid(self.stop_child, os.WNOHANG)
                        self.stop_child = None
                    return
            if time.monotonic() >= deadline:
                raise DeploymentError('stop_timeout', 124)
            time.sleep(min(0.05, max(0, deadline - time.monotonic())))

    def stop_previous(self):
        # Docker stop outlives a disconnected client. This small child keeps the
        # same OS lock and records that exact response even if SSH/the parent dies.
        self.stop_child = os.fork()
        if self.stop_child == 0:
            try:
                os.setsid()
                for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
                    signal.signal(signum, signal.SIG_IGN)
                with open(os.devnull, 'r+b', buffering=0) as sink:
                    for descriptor in (0, 1, 2):
                        os.dup2(sink.fileno(), descriptor)
                receipt = {'id': self.id, 'previous': self.previous['Id'], 'exit_code': None}
                atomic_json(self.stop_receipt, receipt)
                try:
                    # Pass the configured grace explicitly: a disconnected client
                    # must not shorten Docker's stop request to its RPC deadline.
                    grace = self.previous['Config'].get('StopTimeout', 10) if self.previous.get('Release') else 10
                    self.docker('stop', '--time', str(grace), self.previous['Id'],
                                seconds=self.command_seconds + self.health_seconds)
                    receipt['exit_code'] = 0
                except Exception as error:
                    receipt['exit_code'] = getattr(error, 'exit_code', 1)
                atomic_json(self.stop_receipt, receipt)
            finally:
                os._exit(0)
        self.wait_stop(self.command_seconds)

    def reconcile_stop(self):
        if not self.touched_previous or self.stop_confirmed:
            return
        if self.stop_child:
            try:
                self.wait_stop(self.health_seconds)
            except DeploymentError:
                raise DeploymentError('stop_outcome_unknown') from None
        elif self.saved_boot_id and self.boot_id and self.saved_boot_id != self.boot_id:
            # A daemon request from the previous OS boot cannot stop a new task.
            self.stop_confirmed = True
        elif not self.stop_receipt.exists() and self.phase == 'stop_previous':
            # Lock acquired again, but child never persisted its intent or sent RPC.
            self.touched_previous = False
        else:
            self.wait_stop(0)

    def inspect(self, container_id, seconds=None):
        return json.loads(self.docker('container', 'inspect', container_id, seconds=seconds))[0]

    def find(self, name=None, container_id=None):
        selector = 'id=' + container_id if container_id else 'name=^/' + re.escape(name) + '$'
        ids = self.docker('container', 'ls', '--all', '--filter', selector,
                          '--format', '{{.ID}}', '--no-trunc').splitlines()
        if len(ids) > 1:
            raise DeploymentError('ambiguous_container')
        return self.inspect(ids[0]) if ids else None

    def preflight(self):
        self.manage = True
        self.phase = 'preflight'
        self.transfer_paths = transfer_paths([str(path) for path in self.incoming_transfers])
        for executable in ('docker', 'curl'):
            if not shutil.which(executable):
                raise DeploymentError('missing_' + executable)
        self.image_id = json.loads(self.docker('image', 'inspect', self.env['IMAGE']))[0]['Id']
        if self.image_id != self.env['IMAGE_ID']:
            raise DeploymentError('loaded_image_id_mismatch')
        for key in ('REMOTE_ENV_FILE', 'REMOTE_FIREBASE_FILE', 'REMOTE_RELEASE_ENV_FILE'):
            path = Path(self.env[key])
            if not path.is_file() or path.stat().st_size == 0 or path.is_symlink():
                raise DeploymentError('missing_secret_file')
            path.chmod(0o600)
        self.previous = self.find(self.name)
        if not self.previous and self.find(self.name + '-rollback'):
            raise DeploymentError('unowned_legacy_rollback_requires_inspection')
        if self.previous:
            if not self.previous['State']['Running']:
                raise DeploymentError('previous_service_is_stopped')
            for mount in self.previous['Mounts']:
                if mount['Destination'] == '/run/secrets/firebase-service-account.json':
                    if not Path(mount['Source']).is_file():
                        raise DeploymentError('previous_credential_missing')
            labels = self.previous['Config'].get('Labels') or {}
            if labels.get('com.docker.compose.project'):
                if not self.current_release.is_symlink():
                    raise UnsafeState('previous_compose_release_missing')
                previous_release = self.validate_release_path(str(self.current_release.resolve()))
                if not self.matches_release(self.previous, previous_release):
                    raise UnsafeState('previous_compose_release_mismatch')
                self.previous['Release'] = str(previous_release)
                self.previous['Health'] = json.loads((previous_release / 'health.json').read_text())
        self.secret_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.secret_dir.chmod(0o700)
        with self.secret.open('xb') as target:
            self.secret.chmod(0o600)
            with Path(self.env['REMOTE_FIREBASE_FILE']).open('rb') as source:
                shutil.copyfileobj(source, target)
        Path(self.env['RELEASE_HOST_DIR']).mkdir(parents=True, exist_ok=True, mode=0o750)
        self.prepare_release()
        self.preflight_complete = True
        self.checkpoint('prepared')

    @property
    def current_release(self):
        return self.state_dir / (self.name + '-current')

    def validate_release_path(self, value):
        path = Path(value)
        if (path.parent != self.state_dir / (self.name + '-releases') or path.is_symlink()
                or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', path.name)
                or not (path / 'compose.json').is_file()):
            raise UnsafeState('invalid_compose_release_path')
        return path

    def matches_release(self, container, release):
        service = json.loads((release / 'compose.json').read_text())['services']['backend']
        labels = container['Config'].get('Labels') or {}
        return (container['Image'] == service['image']
                and labels.get('app.hof.deployment') == service['labels']['app.hof.deployment']
                and labels.get('com.docker.compose.project') == self.name
                and labels.get('com.docker.compose.service') == 'backend')

    def compose(self, release, *args):
        return self.docker('compose', '--project-name', self.name, '--project-directory', str(release),
                           '--env-file', '/dev/null', '--file', str(release / 'compose.json'), *args)

    def prepare_release(self):
        self.release = self.state_dir / (self.name + '-releases') / self.id
        self.release.mkdir(parents=True, mode=0o700)
        for variable, filename in (('REMOTE_ENV_FILE', 'backend.env'), ('REMOTE_RELEASE_ENV_FILE', 'release.env')):
            with (self.release / filename).open('xb') as target, Path(self.env[variable]).open('rb') as source:
                os.chmod(target.name, 0o600)
                shutil.copyfileobj(source, target)
                target.flush()
                os.fsync(target.fileno())
        service = {
            'image': self.image_id, 'pull_policy': 'never', 'container_name': self.name,
            'labels': {'app.hof.deployment': self.id},
            'network_mode': 'bridge',
            'ports': ['{BACKEND_BIND_ADDRESS}:{HOST_PORT}:{CONTAINER_PORT}'.format(**self.env)],
            'env_file': [{'path': str(self.release / name), 'format': 'raw'} for name in ('backend.env', 'release.env')],
            'environment': {
                'GOOGLE_APPLICATION_CREDENTIALS': '/run/secrets/firebase-service-account.json',
                'HOF_RELEASE_STORAGE_ROOT': self.env['RELEASE_CONTAINER_DIR'],
                'SERVER_FORWARD_HEADERS_STRATEGY': self.env['SERVER_FORWARD_HEADERS_STRATEGY'],
                'HOF_AUTH_ALLOWED_ORIGIN_PATTERNS': self.env['HOF_AUTH_ALLOWED_ORIGIN_PATTERNS'],
            },
            'volumes': [
                {'type': 'bind', 'source': source, 'target': target, 'read_only': True,
                 'bind': {'create_host_path': False}}
                for source, target in ((str(self.secret), '/run/secrets/firebase-service-account.json'),
                                       (self.env['RELEASE_HOST_DIR'], self.env['RELEASE_CONTAINER_DIR']))
            ],
            'restart': 'unless-stopped', 'stop_grace_period': '10s',
            'logging': {'driver': 'json-file', 'options': {'max-size': '10m', 'max-file': '5'}},
        }
        atomic_json(self.release / 'compose.json', {'services': {'backend': service}})
        atomic_json(self.release / 'health.json', {key: self.env[key] for key in
                    ('BACKEND_BIND_ADDRESS', 'HOST_PORT', 'PUBLIC_HEALTH_URL')})
        self.compose(self.release, 'config', '--quiet')

    def publish_release(self, release):
        temporary = self.current_release.with_name(self.current_release.name + '-' + self.id)
        temporary.unlink(missing_ok=True)
        temporary.symlink_to(release, target_is_directory=True)
        os.replace(temporary, self.current_release)

    def acquire_lock(self):
        self.phase = 'locking'
        self.state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.state_dir.chmod(0o700)
        path = self.state_dir / (self.name + '.lock')
        self.lock_file = path.open('a')
        path.chmod(0o600)
        try:
            fcntl.flock(self.lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise DeploymentBusy() from None
        self.lock_acquired = True

    def create(self):
        self.compose(self.release, 'create', '--no-build', '--pull', 'never', 'backend')
        candidate = self.find_candidate()
        if not candidate:
            raise UnsafeState('compose_candidate_missing')
        self.new = candidate['Id']

    def replace(self):
        # Compose settings and immutable release files are validated before stop.
        # The fixed project/service identity requires removing its old task first.
        if self.previous:
            self.touched_previous = True
            self.checkpoint('stop_previous')
            self.stop_previous()
            if self.inspect(self.previous['Id'])['State']['Running']:
                raise DeploymentError('previous_still_running')
            if self.previous.get('Release'):
                self.checkpoint('remove_previous')
                self.docker('rm', self.previous['Id'])
            else:
                self.checkpoint('rename_previous')
                self.docker('rename', self.previous['Id'], self.backup)
        self.checkpoint('create_candidate')
        self.create()
        self.checkpoint('candidate_created')
        self.checkpoint('start_candidate')
        self.docker('start', self.new)
        self.checkpoint('health')
        internal, public = self.health(self.new, self.image_id)
        if not (internal and public):
            raise DeploymentError('new_health_failed')
        self.committed = True
        self.checkpoint('committed')
        report('deployed', deployment_id=self.id, container_id=self.new, image_id=self.image_id)

    def probe(self, url, deadline, per_request):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        budget = min(remaining, per_request)
        try:
            body = command(['curl', '--fail', '--silent', '--connect-timeout', str(min(2, budget)),
                            '--max-time', str(budget), url], budget)
            value = json.loads(body)
            return isinstance(value, dict) and value.get('status') == 'UP'
        except (DeploymentError, subprocess.TimeoutExpired, ValueError):
            return False

    def health(self, container_id, image_id, settings=None):
        settings = self.env if settings is None else settings
        deadline = time.monotonic() + self.health_seconds
        internal = public = False
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                current = self.inspect(container_id, seconds=min(self.command_seconds, remaining))
                if not current['State']['Running'] or current['Image'] != image_id:
                    return False, False
            except (DeploymentError, subprocess.TimeoutExpired):
                return False, False
            internal = self.probe('http://{BACKEND_BIND_ADDRESS}:{HOST_PORT}/actuator/health'.format(**settings), deadline, 5)
            public = internal and self.probe(settings['PUBLIC_HEALTH_URL'], deadline, 10)
            if internal and public:
                return True, True
            time.sleep(max(0, min(2, deadline - time.monotonic())))
        return internal, public

    def rollback(self):
        if self.journal_started:
            self.validate_ownership()
        self.reconcile_stop()
        # A create RPC may have completed even when its client failed to return ID.
        candidate = self.find_candidate()
        if candidate:
            if (candidate['Config'].get('Labels') or {}).get('app.hof.deployment') != self.id or candidate['Image'] != self.image_id:
                raise DeploymentError('candidate_ownership_mismatch')
            try:
                self.docker('rm', '--force', candidate['Id'])
            except (DeploymentError, subprocess.TimeoutExpired):
                # A failed client reply does not prove the removal failed.
                if self.find(container_id=candidate['Id']):
                    raise DeploymentError('candidate_removal_unconfirmed') from None
        if not self.touched_previous:
            report('no_previous_version' if self.preflight_complete and not self.previous else 'previous_untouched')
        elif self.previous:
            if not self.stop_confirmed:
                raise DeploymentError('stop_outcome_unknown')
            occupant = self.find(self.name)
            if occupant and occupant['Id'] != self.previous['Id']:
                raise DeploymentError('service_name_ownership_mismatch')
            previous = self.find(container_id=self.previous['Id'])
            if self.previous.get('Release'):
                release = Path(self.previous['Release'])
                if not previous:
                    self.checkpoint('restore_previous')
                    self.compose(release, 'create', '--no-build', '--pull', 'never', 'backend')
                    previous = self.find(self.name)
                    if not previous or not self.matches_release(previous, release):
                        raise UnsafeState('restored_release_ownership_mismatch')
                    self.previous['Id'] = previous['Id']
                    self.checkpoint('previous_recreated')
                self.publish_release(release)
            if previous['Name'] != '/' + self.name:
                self.docker('rename', self.previous['Id'], self.name)
            try:
                self.docker('start', self.previous['Id'])
            except (DeploymentError, subprocess.TimeoutExpired):
                if not self.inspect(self.previous['Id'])['State']['Running']:
                    raise DeploymentError('previous_start_unconfirmed') from None
            internal, public = self.health(self.previous['Id'], self.previous['Image'], self.previous.get('Health'))
            report('rollback_healthy' if internal and public else 'rollback_unhealthy',
                   container_id=self.previous['Id'], internal=internal, public=public)
            if not (internal and public):
                return
        self.secret.unlink(missing_ok=True)
        if self.journal_started:
            self.finished = True
            self.checkpoint('rolled_back')

    def cleanup_committed(self):
        self.validate_ownership()
        self.checkpoint('committed')
        if self.previous:
            self.docker('image', 'tag', self.previous['Image'], self.env['IMAGE_REPOSITORY'] + ':rollback')
            if self.find(container_id=self.previous['Id']):
                self.docker('rm', self.previous['Id'])
        if self.release:
            self.publish_release(self.release)
        # Retain previous versioned credentials and images; never prune shared
        # Docker storage or guess which old secrets other containers still mount.
        self.finished = True
        self.checkpoint('finished')

    def remove_transfers(self, paths):
        parents = set()
        for path in paths:
            path.unlink(missing_ok=True)
            parents.add(path.parent)
        for parent in parents:
            if parent.parent == Path('/tmp') and re.fullmatch(r'hof-deploy\.[A-Za-z0-9_]+', parent.name):
                try:
                    parent.rmdir()
                except FileNotFoundError:
                    pass

    def cleanup_transfers(self):
        paths = set(self.transfer_paths)
        if self.cleanup_incoming:
            paths.update(self.incoming_transfers)
        self.remove_transfers(paths)


def main():
    deployment = None
    def interrupt(signum, _frame):
        raise Interrupted(signum)
    for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(signum, interrupt)
    exit_code = 0
    try:
        deployment = Deployment()
        deployment.acquire_lock()
        if deployment.resume():
            exit_code = deployment.resume_code
        else:
            deployment.preflight()
            deployment.replace()
    except Exception as error:
        if deployment and isinstance(error, UnsafeState):
            deployment.manage = False
        exit_code = getattr(error, 'exit_code', 1)
        result = ('deployment_busy' if isinstance(error, DeploymentBusy) else
                  'deployment_result_unknown' if isinstance(error, UnsafeState) else 'deployment_failed')
        report(result,
               reason=str(error) if isinstance(error, (DeploymentError, Interrupted)) else type(error).__name__,
               phase=deployment.phase if deployment else 'configuration', exit_code=exit_code,
               deployment_id=deployment.id if deployment else None, image_id=deployment.image_id if deployment else None)
    finally:
        # Further cancellation must not interrupt restoration halfway through.
        for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            signal.signal(signum, signal.SIG_IGN)
        if deployment and deployment.lock_acquired and deployment.manage:
            try:
                if deployment.committed:
                    deployment.cleanup_committed()
                else:
                    deployment.rollback()
            except Exception as error:
                report('cleanup_warning' if deployment.committed else 'rollback_failed',
                       reason=str(error) if isinstance(error, DeploymentError) else type(error).__name__)
            if deployment.recovering_prior:
                exit_code = 76 if deployment.finished else 77
                report('prior_deployment_recovered_retry_requested' if deployment.finished else 'deployment_result_unknown',
                       deployment_id=deployment.id)
            try:
                deployment.cleanup_transfers()
            except OSError:
                report('transfer_cleanup_warning')
        if deployment and deployment.lock_file:
            deployment.lock_file.close()
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
