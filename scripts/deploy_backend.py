#!/usr/bin/env python3
"""Single-instance HOF replacement, called by Jenkins over SSH (stdlib only)."""
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import uuid


class DeploymentError(Exception):
    def __init__(self, reason, exit_code=1):
        self.exit_code = exit_code
        super().__init__(reason)


class Interrupted(Exception):
    def __init__(self, signum):
        self.exit_code = 128 + signum
        super().__init__('signal_' + signal.Signals(signum).name)


class PendingStop(DeploymentError):
    def __init__(self, process, cause):
        self.process = process
        code = 124 if isinstance(cause, subprocess.TimeoutExpired) else getattr(cause, 'exit_code', 1)
        super().__init__('stop_timeout' if code == 124 else str(cause), code)


def command(args, seconds):
    # Kill the client process group on cancellation/timeout; Docker state is
    # subsequently inspected because stopping a client does not undo its RPC.
    deadline = time.monotonic() + seconds
    process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               text=True, start_new_session=True)
    try:
        output, _ = process.communicate(timeout=max(0, deadline - time.monotonic()))
    except BaseException as error:
        # Stop continues in the daemon even if its client disconnects. Keep this
        # exact call alive so restoration can wait for the actual stop response.
        if args[:2] == ['docker', 'stop']:
            raise PendingStop(process, error) from None
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


class Deployment:
    def __init__(self):
        self.env = os.environ
        required = ('IMAGE', 'IMAGE_REPOSITORY', 'CONTAINER_NAME', 'BACKEND_BIND_ADDRESS',
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
        self.new = None
        self.image_id = None
        self.touched_previous = False
        self.committed = False
        self.preflight_complete = False
        self.phase = 'preflight'
        self.pending_stop = None
        self.stop_confirmed = False

    def seconds(self, key, default):
        value = float(self.env.get(key, default))
        if not math.isfinite(value) or value <= 0:
            raise DeploymentError('invalid_timeout')
        return value

    def docker(self, *args, seconds=None):
        return command(['docker', *args], self.command_seconds if seconds is None else seconds)

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
        for executable in ('docker', 'curl'):
            if not shutil.which(executable):
                raise DeploymentError('missing_' + executable)
        self.image_id = json.loads(self.docker('image', 'inspect', self.env['IMAGE']))[0]['Id']
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
        self.secret_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.secret_dir.chmod(0o700)
        with self.secret.open('xb') as target:
            self.secret.chmod(0o600)
            with Path(self.env['REMOTE_FIREBASE_FILE']).open('rb') as source:
                shutil.copyfileobj(source, target)
        Path(self.env['RELEASE_HOST_DIR']).mkdir(parents=True, exist_ok=True, mode=0o750)
        self.preflight_complete = True

    def create(self):
        self.new = self.docker('create', '--name', self.candidate,
            '--label', 'app.hof.deployment=' + self.id,
            '--publish', '{BACKEND_BIND_ADDRESS}:{HOST_PORT}:{CONTAINER_PORT}'.format(**self.env),
            '--env-file', self.env['REMOTE_ENV_FILE'], '--env-file', self.env['REMOTE_RELEASE_ENV_FILE'],
            '--mount', 'type=bind,src=' + str(self.secret) + ',dst=/run/secrets/firebase-service-account.json,readonly',
            '--mount', 'type=bind,src={RELEASE_HOST_DIR},dst={RELEASE_CONTAINER_DIR},readonly'.format(**self.env),
            '--env', 'GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-service-account.json',
            '--env', 'HOF_RELEASE_STORAGE_ROOT=' + self.env['RELEASE_CONTAINER_DIR'],
            '--env', 'SERVER_FORWARD_HEADERS_STRATEGY=' + self.env['SERVER_FORWARD_HEADERS_STRATEGY'],
            '--env', 'HOF_AUTH_ALLOWED_ORIGIN_PATTERNS=' + self.env['HOF_AUTH_ALLOWED_ORIGIN_PATTERNS'],
            '--log-driver', 'json-file', '--log-opt', 'max-size=10m', '--log-opt', 'max-file=5',
            '--restart', 'unless-stopped', self.image_id)

    def replace(self):
        # Create validates Docker options/mounts while the previous service runs.
        # A created container cannot submit HOF actions until it is started.
        self.phase = 'create_candidate'
        self.create()
        if self.previous:
            self.touched_previous = True
            self.phase = 'stop_previous'
            self.docker('stop', '--time', '10', self.previous['Id'])
            self.stop_confirmed = True
            if self.inspect(self.previous['Id'])['State']['Running']:
                raise DeploymentError('previous_still_running')
            self.phase = 'rename_previous'
            self.docker('rename', self.previous['Id'], self.backup)
        self.phase = 'rename_candidate'
        self.docker('rename', self.new, self.name)
        self.phase = 'start_candidate'
        self.docker('start', self.new)
        self.phase = 'health'
        internal, public = self.health(self.new, self.image_id)
        if not (internal and public):
            raise DeploymentError('new_health_failed')
        self.committed = True
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

    def health(self, container_id, image_id):
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
            internal = self.probe('http://{BACKEND_BIND_ADDRESS}:{HOST_PORT}/actuator/health'.format(**self.env), deadline, 5)
            public = internal and self.probe(self.env['PUBLIC_HEALTH_URL'], deadline, 10)
            if internal and public:
                return True, True
            time.sleep(max(0, min(2, deadline - time.monotonic())))
        return internal, public

    def rollback(self):
        if self.pending_stop:
            try:
                self.pending_stop.communicate(timeout=self.health_seconds)
            except subprocess.TimeoutExpired:
                os.killpg(self.pending_stop.pid, signal.SIGKILL)
                self.pending_stop.communicate(timeout=1)
                raise DeploymentError('stop_outcome_unknown') from None
            if self.pending_stop.returncode:
                raise DeploymentError('stop_outcome_unknown')
            self.stop_confirmed = True
        # A create RPC may have completed even when its client failed to return ID.
        candidate = self.find(container_id=self.new) if self.new else self.find(self.candidate)
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
            previous = self.inspect(self.previous['Id'])
            if previous['Name'] != '/' + self.name:
                self.docker('rename', self.previous['Id'], self.name)
            try:
                self.docker('start', self.previous['Id'])
            except (DeploymentError, subprocess.TimeoutExpired):
                if not self.inspect(self.previous['Id'])['State']['Running']:
                    raise DeploymentError('previous_start_unconfirmed') from None
            internal, public = self.health(self.previous['Id'], self.previous['Image'])
            report('rollback_healthy' if internal and public else 'rollback_unhealthy',
                   container_id=self.previous['Id'], internal=internal, public=public)
        self.secret.unlink(missing_ok=True)

    def cleanup_committed(self):
        if self.previous:
            self.docker('image', 'tag', self.previous['Image'], self.env['IMAGE_REPOSITORY'] + ':rollback')
            self.docker('rm', self.previous['Id'])
        # Retain previous versioned credentials and images; never prune shared
        # Docker storage or guess which old secrets other containers still mount.

    def cleanup_transfers(self):
        for key in ('REMOTE_ENV_FILE', 'REMOTE_FIREBASE_FILE', 'REMOTE_RELEASE_ENV_FILE'):
            Path(self.env[key]).unlink(missing_ok=True)


def main():
    deployment = None
    def interrupt(signum, _frame):
        raise Interrupted(signum)
    for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(signum, interrupt)
    exit_code = 0
    try:
        deployment = Deployment()
        deployment.preflight()
        deployment.replace()
    except Exception as error:
        if deployment and isinstance(error, PendingStop):
            deployment.pending_stop = error.process
        exit_code = getattr(error, 'exit_code', 1)
        report('deployment_failed', reason=str(error) if isinstance(error, (DeploymentError, Interrupted)) else type(error).__name__,
               phase=deployment.phase if deployment else 'configuration', exit_code=exit_code,
               deployment_id=deployment.id if deployment else None, image_id=deployment.image_id if deployment else None)
    finally:
        # Further cancellation must not interrupt restoration halfway through.
        for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            signal.signal(signum, signal.SIG_IGN)
        if deployment:
            try:
                if deployment.committed:
                    deployment.cleanup_committed()
                else:
                    deployment.rollback()
            except Exception as error:
                report('cleanup_warning' if deployment.committed else 'rollback_failed',
                       reason=str(error) if isinstance(error, DeploymentError) else type(error).__name__)
            try:
                deployment.cleanup_transfers()
            except OSError:
                report('transfer_cleanup_warning')
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
