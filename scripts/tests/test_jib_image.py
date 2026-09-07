"""Opt-in verification of the built production tar and an isolated JVM startup."""
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(os.environ.get('HOF_JIB_IMAGE_TESTS') == '1', 'explicit built Jib image check only')
class JibImageTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('HOF_JIB_POSTGRES_TESTS') == '1', 'explicit PostgreSQL upgrade check only')
    def test_postgres_51_upgrades_to_56_and_starts_with_existing_schema(self):
        image_id = (ROOT / 'build/jib-image.id').read_text().strip()
        name = 'hof-pg-upgrade-' + uuid.uuid4().hex

        def docker(*args, timeout=90):
            return subprocess.check_output(['docker', *args], text=True, errors='replace', timeout=timeout).strip()

        def sql(query):
            return docker('exec', name + '-db', 'psql', '-X', '-U', 'postgres', '-d', 'fixture', '-At', '-v', 'ON_ERROR_STOP=1', '-c', query)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            baseline = root / 'baseline'
            baseline.mkdir()
            for migration in (ROOT / 'src/main/resources/db/migration').glob('V*__*.sql'):
                if int(migration.name[1:].split('__', 1)[0]) <= 51:
                    shutil.copyfile(migration, baseline / migration.name)
            source = root / 'Baseline.java'
            source.write_text('''import org.flywaydb.core.Flyway;
public class Baseline {
    public static void main(String[] args) {
        Flyway.configure().dataSource(args[0], "postgres", "")
            .locations("filesystem:/baseline").load().migrate();
    }
}
''')
            flyway = next(path for path in (Path.home() / '.gradle/caches/modules-2/files-2.1/org.flywaydb/flyway-core').glob('*/*/*.jar')
                          if not path.name.endswith('-sources.jar'))
            subprocess.run(['javac', '--release', '21', '-cp', str(flyway), str(source)], check=True, timeout=30)
            docker('load', '--input', str(ROOT / 'build/jib-image.tar'))
            with tarfile.open(ROOT / 'build/jib-image.tar') as archive:
                tag = json.load(archive.extractfile('manifest.json'))[0]['RepoTags'][0]
            loaded_id = docker('image', 'inspect', tag, '--format', '{{.Id}}')
            self.assertIn(loaded_id, (image_id, (ROOT / 'build/jib-image.digest').read_text().strip()))
            image_id = loaded_id
            docker('network', 'create', '--internal', name)
            try:
                docker('run', '-d', '--pull=never', '--name', name + '-db', '--network', name, '--network-alias', 'database',
                       '-e', 'POSTGRES_DB=fixture', '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', 'postgres:16-alpine')
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    ready = subprocess.run(['docker', 'exec', name + '-db', 'pg_isready', '-U', 'postgres', '-d', 'fixture'], capture_output=True)
                    if ready.returncode == 0:
                        break
                    time.sleep(1)
                self.assertEqual(0, ready.returncode)
                url = 'jdbc:postgresql://database:5432/fixture'
                docker('run', '--rm', '--platform', 'linux/amd64', '--network', name, '--entrypoint', 'java',
                       '--mount', 'type=bind,src=' + directory + ',dst=/check,readonly',
                       '--mount', 'type=bind,src=' + str(baseline) + ',dst=/baseline,readonly',
                       image_id, '-cp', '/check:/app/libs/*', 'Baseline', url)
                self.assertEqual('51', sql('select max(version::int) from flyway_schema_history where success'))
                self.assertEqual('', sql("select to_regclass('public.character_recovery_originals')"))
                def start_app(application_image):
                    docker('run', '-d', '--name', name + '-app', '--platform', 'linux/amd64', '--network', name,
                           '--mount', 'type=bind,src=' + str(ROOT / 'src/test/resources/application-test.properties') + ',dst=/smoke/application-test.properties,readonly',
                           application_image, '--spring.profiles.active=test', '--spring.config.additional-location=file:/smoke/',
                           '--spring.datasource.url=' + url, '--spring.datasource.driver-class-name=org.postgresql.Driver',
                           '--spring.datasource.username=postgres', '--management.health.redis.enabled=false', '--management.health.kafka.enabled=false',
                           '--spring.kafka.listener.auto-startup=false', '--hof.automation-convergence.automation-posts-enabled=false')

                rollback_image = os.environ.get('HOF_JIB_ROLLBACK_IMAGE')
                start_app(image_id)
                for attempt in range(3 if rollback_image else 2):
                    deadline = time.monotonic() + 120
                    response = ''
                    while time.monotonic() < deadline:
                        self.assertEqual('true', docker('inspect', '--format', '{{.State.Running}}', name + '-app'), docker('logs', name + '-app'))
                        result = subprocess.run(['docker', 'exec', name + '-app', 'bash', '-c',
                            "exec 3<>/dev/tcp/127.0.0.1/8080; printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3; cat <&3"],
                            capture_output=True, text=True, errors='replace', timeout=6)
                        response = result.stdout
                        if '200 ' in response and '"status":"UP"' in response:
                            break
                        time.sleep(1)
                    self.assertIn('200 ', response, docker('logs', name + '-app'))
                    self.assertIn('"status":"UP"', response)
                    self.assertEqual('56|56', sql('select max(version::int), count(*) from flyway_schema_history where success'))
                    self.assertIn('Initialized JPA EntityManagerFactory', docker('logs', name + '-app'))
                    if attempt == 0:
                        docker('restart', name + '-app')
                    elif attempt == 1 and rollback_image:
                        docker('rm', '--force', name + '-app')
                        start_app(rollback_image)
            finally:
                for suffix in ('-app', '-db'):
                    subprocess.run(['docker', 'rm', '--force', name + suffix], capture_output=True)
                docker('network', 'rm', name)

    def test_production_layers_and_isolated_spring_startup(self):
        image_id = (ROOT / 'build/jib-image.id').read_text().strip()
        with tarfile.open(ROOT / 'build/jib-image.tar') as archive:
            manifest = json.load(archive.extractfile('manifest.json'))[0]
            config_bytes = archive.extractfile(manifest['Config']).read()
            self.assertEqual(image_id, 'sha256:' + hashlib.sha256(config_bytes).hexdigest())
            config = json.loads(config_bytes)
            self.assertEqual(('amd64', 'linux'), (config['architecture'], config['os']))
            runtime = config['config']
            self.assertEqual('/app', runtime['WorkingDir'])
            self.assertIn('8080/tcp', runtime['ExposedPorts'])
            self.assertIn('JAVA_TOOL_OPTIONS=-Djdk.httpclient.keepalive.timeout=4', runtime['Env'])
            self.assertIn('JAVA_VERSION=jdk-21.', '\n'.join(runtime['Env']))
            self.assertEqual('app.spammy.hof.HofApplicationKt', runtime['Entrypoint'][-1])
            self.assertTrue(runtime['Labels']['org.opencontainers.image.revision'])
            self.assertTrue(runtime['Labels']['app.jenkins.build'])
            names = set()
            for layer in manifest['Layers']:
                with tarfile.open(fileobj=io.BytesIO(archive.extractfile(layer).read())) as content:
                    names.update(member.name.lstrip('/') for member in content.getmembers())
            self.assertIn('app/classes/app/spammy/hof/HofApplicationKt.class', names)
            self.assertTrue(any(name.endswith('/QHofAccountEntity.class') for name in names))
            migrations = {path.name for path in (ROOT / 'src/main/resources/db/migration').glob('*.sql')}
            self.assertEqual(migrations, {Path(name).name for name in names if '/db/migration/' in name and name.endswith('.sql')})
            self.assertFalse(any('spring-boot-devtools' in name for name in names))
            self.assertFalse(any(Path(name).name in ('.env', 'backend.env', 'firebase.json', 'application-test.properties') for name in names))

        def docker(*args, timeout=60):
            return subprocess.check_output(['docker', *args], text=True, errors='replace', timeout=timeout).strip()

        with (ROOT / 'build/jib-image.tar').open('rb') as archive:
            truncated = subprocess.run(['docker', 'load'], input=archive.read(65536), capture_output=True, timeout=30)
        self.assertNotEqual(0, truncated.returncode, 'Docker must reject an incomplete production archive')
        docker('load', '--input', str(ROOT / 'build/jib-image.tar'))
        loaded_id = docker('image', 'inspect', manifest['RepoTags'][0], '--format', '{{.Id}}')
        self.assertIn(loaded_id, (image_id, (ROOT / 'build/jib-image.digest').read_text().strip()))
        image_id = loaded_id
        name = 'hof-jib-smoke-' + uuid.uuid4().hex
        docker('run', '-d', '--name', name, '--platform', 'linux/amd64', '--network', 'none',
               '--mount', 'type=bind,src=' + str(ROOT / 'src/test/resources/application-test.properties') + ',dst=/smoke/application-test.properties,readonly',
               image_id, '--spring.profiles.active=test', '--spring.config.additional-location=file:/smoke/',
               '--management.health.redis.enabled=false', '--management.health.kafka.enabled=false',
               '--spring.kafka.listener.auto-startup=false', '--hof.automation-convergence.automation-posts-enabled=false')
        try:
            deadline = time.monotonic() + 120
            response = ''
            while time.monotonic() < deadline:
                if docker('inspect', '--format', '{{.State.Running}}', name) != 'true':
                    self.fail(docker('logs', name))
                result = subprocess.run(['docker', 'exec', name, 'bash', '-c',
                    "exec 3<>/dev/tcp/127.0.0.1/8080; printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3; cat <&3"],
                    capture_output=True, text=True, errors='replace', timeout=6)
                response = result.stdout
                if '200 ' in response and '"status":"UP"' in response:
                    break
                time.sleep(1)
            self.assertIn('200 ', response, docker('logs', name))
            self.assertIn('"status":"UP"', response)
            logs = docker('logs', name)
            self.assertIn('Started HofApplicationKt', logs)
            self.assertIn('Successfully', logs)
            self.assertIn('Initialized JPA EntityManagerFactory', logs)
        finally:
            docker('rm', '--force', name)
