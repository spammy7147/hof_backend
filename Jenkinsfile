pipeline {
    agent {
        label 'spammy-builder'
    }

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
    }

    environment {
        REPO_URL = 'https://github.com/spammy7147/hof_backend.git'
        REPO_BRANCH = 'master'
        GITHUB_CREDENTIAL_ID = 'SPAMMY-github-token'
        SSH_CREDENTIAL_ID = 'hof-deploy-ssh'
        ENV_FILE_CREDENTIAL_ID = 'hof-spammy-backend-env'
        FIREBASE_CREDENTIAL_ID = 'hof-spammy-fcm'
        PUBLISH_TOKEN_CREDENTIAL_ID = 'hof-spammy-publish-token'
        DEPLOY_TARGET = 'spammy@192.168.50.202'
        DEPLOY_HOST_IP = '192.168.50.202'
        BACKEND_BIND_ADDRESS = '192.168.50.202'
        TRUSTED_INGRESS_MODE = 'disabled'
        TRUSTED_PROXY_SOURCE_CIDR = ''
        SERVER_FORWARD_HEADERS_STRATEGY = 'NONE'
        PUBLIC_HEALTH_URL = 'https://api-hof.spammy.app/actuator/health'
        HOF_AUTH_ALLOWED_ORIGIN_PATTERNS = 'chrome-extension://*'
        SSH_KNOWN_HOSTS_FILE = "${WORKSPACE}/.jenkins/known_hosts"
        IMAGE_REPOSITORY = 'hof-backend'
        CONTAINER_NAME = 'hof-backend'
        HOST_PORT = '8080'
        CONTAINER_PORT = '8080'
        RELEASE_HOST_DIR = '/home/spammy/hof/releases'
        RELEASE_CONTAINER_DIR = '/var/lib/hof/releases'
        GRADLE_USER_HOME = '/home/jenkins/workspace/.gradle-cache/hof-backend'
    }

    stages {
        stage('Checkout') {
            steps {
                git branch: "${REPO_BRANCH}",
                    credentialsId: "${GITHUB_CREDENTIAL_ID}",
                    url: "${REPO_URL}"
                // Keep Gradle's project-local incremental state between deployments.
                // `git checkout -f` refreshes tracked files; this removes only stale,
                // non-ignored files and leaves .gradle/, .kotlin/, and build/ intact.
                sh 'git clean -ffd'
                script {
                    env.GIT_REVISION = sh(
                        script: 'git rev-parse HEAD',
                        returnStdout: true,
                    ).trim()
                    env.GIT_SHORT = sh(
                        script: 'git rev-parse --short=12 HEAD',
                        returnStdout: true,
                    ).trim()
                    env.IMAGE_TAG = "${BUILD_NUMBER}-${env.GIT_SHORT}"
                    env.IMAGE = "${IMAGE_REPOSITORY}:${env.IMAGE_TAG}"
                }
            }
        }

        stage('Preflight') {
            steps {
                sh '''
                    set -eu
                    command -v bash
                    command -v python3
                    command -v gzip
                    command -v ssh
                    command -v scp
                    command -v ssh-keygen
                    command -v curl
                    test -x ./gradlew
                    test -r "$SSH_KNOWN_HOSTS_FILE"
                    ssh-keygen -F "$DEPLOY_HOST_IP" -f "$SSH_KNOWN_HOSTS_FILE" >/dev/null
                '''
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: "${SSH_CREDENTIAL_ID}",
                        keyFileVariable: 'SSH_KEY_FILE',
                    ),
                    file(
                        credentialsId: "${ENV_FILE_CREDENTIAL_ID}",
                        variable: 'HOF_ENV_FILE',
                    ),
                ]) {
                    sh '''#!/usr/bin/env bash
                        set -Eeuo pipefail
                        case "$SERVER_FORWARD_HEADERS_STRATEGY" in
                            NONE|FRAMEWORK) ;;
                            *) echo 'SERVER_FORWARD_HEADERS_STRATEGY must be NONE or FRAMEWORK.' >&2; exit 1 ;;
                        esac
                        case "$TRUSTED_INGRESS_MODE" in
                            disabled)
                                if [ "$SERVER_FORWARD_HEADERS_STRATEGY" != 'NONE' ]; then
                                    echo 'Forwarded headers require a verified trusted ingress.' >&2
                                    exit 1
                                fi
                                ;;
                            loopback)
                                case "$BACKEND_BIND_ADDRESS" in
                                    127.0.0.1) ;;
                                    *) echo 'Loopback ingress mode requires a loopback backend bind.' >&2; exit 1 ;;
                                esac
                                ;;
                            firewall-verified)
                                test -n "$TRUSTED_PROXY_SOURCE_CIDR" || {
                                    echo 'Firewall ingress mode requires TRUSTED_PROXY_SOURCE_CIDR.' >&2
                                    exit 1
                                }
                                if curl --fail --silent --show-error --max-time 3 \
                                    "http://${DEPLOY_HOST_IP}:${HOST_PORT}/actuator/health" >/dev/null 2>&1; then
                                    echo 'Direct backend health is still reachable from the Jenkins agent.' >&2
                                    exit 1
                                fi
                                ;;
                            *) echo 'TRUSTED_INGRESS_MODE must be disabled, loopback, or firewall-verified.' >&2; exit 1 ;;
                        esac
                        if [ "$SERVER_FORWARD_HEADERS_STRATEGY" = 'FRAMEWORK' ]; then
                            [ "$TRUSTED_INGRESS_MODE" != 'disabled' ]
                            if grep -Eq '^HOF_AUTH_REFRESH_COOKIE_SECURE=true\r?$' "$HOF_ENV_FILE" && \
                                ! grep -Eq '^HOF_AUTH_REQUIRE_HTTPS=true\r?$' "$HOF_ENV_FILE"; then
                                echo 'Secure Cookie cutover requires HTTPS enforcement first.' >&2
                                exit 1
                            fi
                        else
                            if grep -Eq '^HOF_AUTH_REQUIRE_HTTPS=true\r?$|^HOF_AUTH_REFRESH_COOKIE_SECURE=true\r?$' "$HOF_ENV_FILE"; then
                                echo 'HTTPS enforcement and Secure Cookie require verified forwarded-header handling.' >&2
                                exit 1
                            fi
                        fi
                        ssh -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                          -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                          "$DEPLOY_TARGET" \
                          'command -v bash >/dev/null && command -v docker >/dev/null && command -v gunzip >/dev/null && command -v curl >/dev/null && command -v python3 >/dev/null && docker info >/dev/null && docker compose version >/dev/null'
                    '''
                }
            }
        }

        stage('Test') {
            steps {
                // Run the complete test suite and package the exact tested classes.
                // The persistent daemon, incremental compilation, and build cache
                // make subsequent deployments avoid recompiling unchanged inputs.
                sh './gradlew test --build-cache --console=plain'
            }
        }

        stage('Build Image') {
            steps {
                sh '''
                    set -eu
                    ./gradlew jibBuildTar --no-configuration-cache --build-cache --console=plain \
                      -Djib.to.image="$IMAGE" \
                      -Djib.container.labels="org.opencontainers.image.revision=$GIT_REVISION,app.jenkins.build=$BUILD_NUMBER" \
                      -Djib.baseImageCache="$GRADLE_USER_HOME/jib/base" \
                      -Djib.applicationCache="$GRADLE_USER_HOME/jib/application"
                '''
                script {
                    env.IMAGE_ID = readFile('build/jib-image.id').trim()
                    env.IMAGE_DIGEST = readFile('build/jib-image.digest').trim()
                    if (!(env.IMAGE_ID ==~ /sha256:[a-f0-9]{64}/) ||
                        !(env.IMAGE_DIGEST ==~ /sha256:[a-f0-9]{64}/)) {
                        error('Jib did not produce valid config and manifest digests.')
                    }
                }
            }
        }

        stage('Transfer Image') {
            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: "${SSH_CREDENTIAL_ID}",
                        keyFileVariable: 'SSH_KEY_FILE',
                    ),
                ]) {
                    sh '''#!/usr/bin/env bash
                        set -Eeuo pipefail
                        gzip -c build/jib-image.tar | \
                          ssh -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                            -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                            "$DEPLOY_TARGET" 'bash -o pipefail -c "gunzip | docker load"'
                        remote_image_id=$(ssh -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                            -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                            "$DEPLOY_TARGET" "docker image inspect --format '{{.Id}}' '$IMAGE'")
                        # Classic Docker exposes the config ID; containerd exposes the manifest digest.
                        [ "$remote_image_id" = "$IMAGE_ID" ] || [ "$remote_image_id" = "$IMAGE_DIGEST" ] || {
                            echo 'Transferred image ID mismatch.' >&2
                            exit 1
                        }
                    '''
                }
            }
        }

        stage('Deploy') {
            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: "${SSH_CREDENTIAL_ID}",
                        keyFileVariable: 'SSH_KEY_FILE',
                    ),
                    file(
                        credentialsId: "${ENV_FILE_CREDENTIAL_ID}",
                        variable: 'HOF_ENV_FILE',
                    ),
                    file(
                        credentialsId: "${FIREBASE_CREDENTIAL_ID}",
                        variable: 'HOF_FIREBASE_FILE',
                    ),
                    string(
                        credentialsId: "${PUBLISH_TOKEN_CREDENTIAL_ID}",
                        variable: 'HOF_RELEASE_PUBLISH_TOKEN',
                    ),
                ]) {
                        sh(script: '''#!/usr/bin/env bash
                            set -Eeuo pipefail
                            # Each invocation uploads into a private, unique directory.
                            # A retry cannot overwrite credentials used by a surviving deployment.
                            transfer_dir=$(ssh -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                              -o ConnectTimeout=10 -o ServerAliveInterval=10 -o ServerAliveCountMax=3 \
                              -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                              "$DEPLOY_TARGET" 'umask 077; mktemp -d /tmp/hof-deploy.XXXXXXXXXX')
                            [[ "$transfer_dir" =~ ^/tmp/hof-deploy[.][A-Za-z0-9]+$ ]]
                            REMOTE_ENV_FILE="$transfer_dir/backend.env"
                            REMOTE_FIREBASE_FILE="$transfer_dir/firebase.json"
                            REMOTE_RELEASE_ENV_FILE="$transfer_dir/release.env"
                            remote_started=0
                            cleanup_upload() {
                              if [ "$remote_started" -eq 0 ]; then
                                ssh -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                                  -o ConnectTimeout=10 -o ServerAliveInterval=10 -o ServerAliveCountMax=3 \
                                  -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                                  "$DEPLOY_TARGET" \
                                  "rm -f -- '$REMOTE_ENV_FILE' '$REMOTE_FIREBASE_FILE' '$REMOTE_RELEASE_ENV_FILE'; rmdir -- '$transfer_dir'" >/dev/null 2>&1 || true
                              fi
                            }
                            trap cleanup_upload EXIT
                            scp -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                              -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                              "$HOF_ENV_FILE" "$DEPLOY_TARGET:$REMOTE_ENV_FILE"
                            scp -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                              -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                              "$HOF_FIREBASE_FILE" "$DEPLOY_TARGET:$REMOTE_FIREBASE_FILE"
                            case "$HOF_RELEASE_PUBLISH_TOKEN" in
                                *$'\n'*|*$'\r'*) echo 'Release publish token must be a single line.' >&2; exit 1 ;;
                            esac
                            printf 'HOF_RELEASE_PUBLISH_TOKEN=%s\n' "$HOF_RELEASE_PUBLISH_TOKEN" | \
                              ssh -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                                -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                                "$DEPLOY_TARGET" \
                                "umask 077; cat > '$REMOTE_RELEASE_ENV_FILE'"
                            remote_started=1
                            for attempt in 1 2 3; do
                              if ssh -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                              -o ConnectTimeout=10 -o ServerAliveInterval=10 -o ServerAliveCountMax=3 \
                              -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                              "$DEPLOY_TARGET" \
                              "IMAGE='$IMAGE' IMAGE_ID='$IMAGE_ID' IMAGE_DIGEST='$IMAGE_DIGEST' IMAGE_REPOSITORY='$IMAGE_REPOSITORY' CONTAINER_NAME='$CONTAINER_NAME' BACKEND_BIND_ADDRESS='$BACKEND_BIND_ADDRESS' HOST_PORT='$HOST_PORT' CONTAINER_PORT='$CONTAINER_PORT' SERVER_FORWARD_HEADERS_STRATEGY='$SERVER_FORWARD_HEADERS_STRATEGY' PUBLIC_HEALTH_URL='$PUBLIC_HEALTH_URL' HOF_AUTH_ALLOWED_ORIGIN_PATTERNS='$HOF_AUTH_ALLOWED_ORIGIN_PATTERNS' RELEASE_HOST_DIR='$RELEASE_HOST_DIR' RELEASE_CONTAINER_DIR='$RELEASE_CONTAINER_DIR' REMOTE_ENV_FILE='$REMOTE_ENV_FILE' REMOTE_FIREBASE_FILE='$REMOTE_FIREBASE_FILE' REMOTE_RELEASE_ENV_FILE='$REMOTE_RELEASE_ENV_FILE' BUILD_NUMBER='$BUILD_NUMBER' python3 -" < scripts/deploy_backend.py; then
                                exit 0
                              else
                                deploy_code=$?
                              fi
                              case "$deploy_code" in
                                75|76|255) echo "Deployment result pending (remote exit $deploy_code); rechecking under the server lock." ;;
                                *) exit "$deploy_code" ;;
                              esac
                              if [ "$attempt" -lt 3 ]; then sleep 2; fi
                            done
                            echo '{"result":"deployment_result_unknown","reason":"remote_execution_or_connection_pending"}'
                            exit 77
                        '''.stripIndent())
                }
            }
        }
    }

    post {
        always {
            script {
                sh 'rm -f -- build/jib-image.tar build/jib-image.id build/jib-image.digest build/jib-image.json'
                // The server entry point owns transfer cleanup under its lock.
                // Cancellation/SSH loss is not evidence that remote use has ended.
            }
        }
        success {
            echo "HOF backend deployed successfully: ${env.IMAGE}"
        }
        failure {
            echo 'HOF backend job did not confirm deployment success. Check the structured remote result: failure, rollback, or result unknown.'
        }
    }
}
