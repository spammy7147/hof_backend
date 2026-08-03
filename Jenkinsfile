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
        DEPLOY_TARGET = 'spammy@192.168.50.202'
        DEPLOY_HOST_IP = '192.168.50.202'
        SSH_KNOWN_HOSTS_FILE = "${WORKSPACE}/.jenkins/known_hosts"
        IMAGE_REPOSITORY = 'hof-backend'
        CONTAINER_NAME = 'hof-backend'
        HOST_PORT = '8080'
        CONTAINER_PORT = '8080'
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
                    env.REMOTE_ENV_FILE = "/tmp/hof-backend-env-${BUILD_NUMBER}"
                    env.REMOTE_FIREBASE_FILE = "/tmp/hof-firebase-${BUILD_NUMBER}.json"
                }
            }
        }

        stage('Preflight') {
            steps {
                sh '''
                    set -eu
                    command -v docker
                    command -v bash
                    command -v gzip
                    command -v ssh
                    command -v scp
                    command -v ssh-keygen
                    docker info >/dev/null
                    test -x ./gradlew
                    test -r "$SSH_KNOWN_HOSTS_FILE"
                    ssh-keygen -F "$DEPLOY_HOST_IP" -f "$SSH_KNOWN_HOSTS_FILE" >/dev/null
                '''
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: "${SSH_CREDENTIAL_ID}",
                        keyFileVariable: 'SSH_KEY_FILE',
                    ),
                ]) {
                    sh '''
                        set -eu
                        ssh -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                          -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                          "$DEPLOY_TARGET" \
                          'command -v bash >/dev/null && command -v docker >/dev/null && command -v gunzip >/dev/null && command -v curl >/dev/null && command -v grep >/dev/null && command -v seq >/dev/null && docker info >/dev/null'
                    '''
                }
            }
        }

        stage('Test') {
            steps {
                // Run the complete test suite and package the exact tested classes.
                // The persistent daemon, incremental compilation, and build cache
                // make subsequent deployments avoid recompiling unchanged inputs.
                sh './gradlew test bootJar --build-cache --console=plain'
            }
        }

        stage('Build Image') {
            steps {
                sh '''
                    set -eu
                    docker build \
                      --file Dockerfile.runtime \
                      --label "org.opencontainers.image.revision=$GIT_REVISION" \
                      --label "app.jenkins.build=$BUILD_NUMBER" \
                      --tag "$IMAGE" \
                      .
                '''
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
                        docker save "$IMAGE" | gzip | \
                          ssh -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                            -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                            "$DEPLOY_TARGET" 'gunzip | docker load'
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
                ]) {
                        sh(script: '''#!/usr/bin/env bash
                            set -Eeuo pipefail
                            scp -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes \
                              -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                              "$HOF_ENV_FILE" "$DEPLOY_TARGET:$REMOTE_ENV_FILE"
                            scp -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes \
                              -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                              "$HOF_FIREBASE_FILE" "$DEPLOY_TARGET:$REMOTE_FIREBASE_FILE"
                            ssh -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                              -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                              "$DEPLOY_TARGET" \
                              "IMAGE='$IMAGE' IMAGE_REPOSITORY='$IMAGE_REPOSITORY' CONTAINER_NAME='$CONTAINER_NAME' DEPLOY_HOST_IP='$DEPLOY_HOST_IP' HOST_PORT='$HOST_PORT' CONTAINER_PORT='$CONTAINER_PORT' REMOTE_ENV_FILE='$REMOTE_ENV_FILE' REMOTE_FIREBASE_FILE='$REMOTE_FIREBASE_FILE' BUILD_NUMBER='$BUILD_NUMBER' bash -s" <<'REMOTE_SCRIPT'
                            set -Eeuo pipefail

                            rollback_name="${CONTAINER_NAME}-rollback"
                            had_previous=0
                            previous_image=''
                            previous_image_id=''
                            previous_secret_path=''
                            secret_dir="$HOME/.config/hof/secrets"
                            secret_path="$secret_dir/firebase-service-account-${BUILD_NUMBER}.json"

                            cleanup_transfers() {
                                rm -f -- "$REMOTE_ENV_FILE" "$REMOTE_FIREBASE_FILE" "${secret_path}.tmp"
                            }

                            restore_previous() {
                                docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
                                if [ "$had_previous" -eq 1 ]; then
                                    docker rename "$rollback_name" "$CONTAINER_NAME"
                                    docker start "$CONTAINER_NAME" >/dev/null
                                fi
                            }

                            trap cleanup_transfers EXIT
                            chmod 600 "$REMOTE_ENV_FILE"
                            test -s "$REMOTE_FIREBASE_FILE"
                            install -d -m 700 "$secret_dir"
                            install -m 600 "$REMOTE_FIREBASE_FILE" "${secret_path}.tmp"
                            mv -f "${secret_path}.tmp" "$secret_path"

                            if docker container inspect "$rollback_name" >/dev/null 2>&1; then
                                if docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
                                    docker rm -f "$rollback_name" >/dev/null
                                else
                                    docker rename "$rollback_name" "$CONTAINER_NAME"
                                    docker start "$CONTAINER_NAME" >/dev/null
                                fi
                            fi

                            if docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
                                had_previous=1
                                previous_image="$(docker container inspect --format '{{.Config.Image}}' "$CONTAINER_NAME")"
                                previous_image_id="$(docker container inspect --format '{{.Image}}' "$CONTAINER_NAME")"
                                previous_secret_path="$(docker container inspect --format '{{range .Mounts}}{{if eq .Destination "/run/secrets/firebase-service-account.json"}}{{.Source}}{{end}}{{end}}' "$CONTAINER_NAME")"
                                docker stop "$CONTAINER_NAME" >/dev/null
                                docker rename "$CONTAINER_NAME" "$rollback_name"
                            fi

                            if ! docker run -d \
                                --name "$CONTAINER_NAME" \
                                --publish "$DEPLOY_HOST_IP:$HOST_PORT:$CONTAINER_PORT" \
                                --env-file "$REMOTE_ENV_FILE" \
                                --mount "type=bind,src=$secret_path,dst=/run/secrets/firebase-service-account.json,readonly" \
                                --env "GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-service-account.json" \
                                --restart unless-stopped \
                                "$IMAGE" >/dev/null; then
                                restore_previous
                                rm -f -- "$secret_path"
                                exit 1
                            fi

                            healthy=0
                            for attempt in $(seq 1 30); do
                                if curl --fail --silent \
                                    "http://${DEPLOY_HOST_IP}:${HOST_PORT}/actuator/health" | \
                                    grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
                                    healthy=1
                                    break
                                fi
                                sleep 2
                            done

                            if [ "$healthy" -ne 1 ]; then
                                docker logs --tail 100 "$CONTAINER_NAME" || true
                                restore_previous
                                rm -f -- "$secret_path"
                                exit 1
                            fi

                            if [ "$had_previous" -eq 1 ]; then
                                docker rm "$rollback_name" >/dev/null
                                docker image tag "$previous_image_id" "${IMAGE_REPOSITORY}:rollback"
                                if [ "$previous_image" != "${IMAGE_REPOSITORY}:rollback" ]; then
                                    docker image rm "$previous_image" >/dev/null 2>&1 || true
                                fi
                            fi
                            if [ -n "$previous_secret_path" ] && [ "$previous_secret_path" != "$secret_path" ]; then
                                case "$previous_secret_path" in
                                    "$secret_dir"/firebase-service-account-*.json) rm -f -- "$previous_secret_path" ;;
                                    *) echo "Refusing to remove unexpected previous Firebase secret path" >&2 ;;
                                esac
                            fi
                            docker image prune -f >/dev/null
REMOTE_SCRIPT
                        '''.stripIndent())
                }
            }
        }
    }

    post {
        always {
            script {
                if (env.IMAGE?.trim()) {
                    sh 'docker image rm "$IMAGE" >/dev/null 2>&1 || true'
                }
                if (env.REMOTE_ENV_FILE?.trim() || env.REMOTE_FIREBASE_FILE?.trim()) {
                    withCredentials([
                        sshUserPrivateKey(
                            credentialsId: "${SSH_CREDENTIAL_ID}",
                            keyFileVariable: 'SSH_KEY_FILE',
                        ),
                    ]) {
                        sh '''
                            ssh -i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes \
                              -o UserKnownHostsFile="$SSH_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes \
                              "$DEPLOY_TARGET" \
                              "rm -f -- '$REMOTE_ENV_FILE' '$REMOTE_FIREBASE_FILE'" >/dev/null 2>&1 || true
                        '''
                    }
                }
            }
        }
        success {
            echo "HOF backend deployed successfully: ${env.IMAGE}"
        }
        failure {
            echo 'HOF backend deployment failed. Check the failed stage and the remote container logs printed above.'
        }
    }
}
