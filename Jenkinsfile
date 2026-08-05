// Self-hosted CI on the local k3s Jenkins — the counterpart to .github/workflows/ci.yml.
// Build work runs in an on-demand agent pod (Kubernetes plugin): a JDK-21 container for Gradle plus a
// Docker-in-Docker sidecar so Testcontainers AND `bootBuildImage` have a Docker daemon (same as the
// GitHub runner had). Trigger is SCM polling — a laptop Jenkins can't receive GitHub webhooks.
//
// Two Jenkins credentials are required (add them in the UI, see deploy/k3s-local/README.md):
//   - 'ghcr'     : username + PAT (write:packages) to push images to GHCR
//   - 'git-push' : an SSH private key allowed to PUSH to main (writes the GitOps image-tag bump)
//
// RAM: this pod is sized to survive an 8 GB VM that is already running the platform, Argo and the Jenkins
// controller — a build with the full test suite took the node NotReady and knocked every service offline.
// Two things keep it inside its budget: TEST_TASKS defaults to the gateway suite only (no Keycloak/
// Postgres/SeaweedFS in dind), and the build JVM's heap is capped so Gradle stops sizing itself off the
// node. Both are reversible on a bigger host. The full story: docs/runbooks/ci-jenkins.md.
pipeline {
  agent {
    kubernetes {
      defaultContainer 'build'
      yaml '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: build
      image: eclipse-temurin:21-jdk
      command: ["sleep"]
      args: ["infinity"]
      env:
        - { name: DOCKER_HOST, value: "tcp://localhost:2375" }
        - { name: TESTCONTAINERS_RYUK_DISABLED, value: "true" }
        # Cap the build JVM. Left unbounded, Gradle sized its heap off the NODE's memory rather than the
        # container limit and reached ~1.7 GB before a single test ran — that is what took the kubelet
        # down (docs/runbooks/ci-jenkins.md). --max-workers is capped in the stage for the same reason.
        - { name: GRADLE_OPTS, value: "-Xmx640m -XX:MaxMetaspaceSize=256m" }
      resources:
        requests: { memory: "1Gi", cpu: "500m" }
        limits:   { memory: "1600Mi" }
    - name: dind
      image: docker:27-dind
      securityContext: { privileged: true }
      env:
        - { name: DOCKER_TLS_CERTDIR, value: "" }
      # Budget moved here from `build`: the narrowed test run only starts valkey:8-alpine, but
      # bootBuildImage runs the whole Paketo lifecycle inside this daemon and 1 GB was not enough.
      resources:
        requests: { memory: "512Mi" }
        limits:   { memory: "1800Mi" }
'''
    }
  }
  options { disableConcurrentBuilds(); timeout(time: 40, unit: 'MINUTES') }
  triggers { pollSCM('H/5 * * * *') }
  parameters {
    // Narrowed by default so a build actually completes on the 8 GB k3s VM. The modulith's `:test`
    // starts Keycloak (~540 MB), Postgres and SeaweedFS as Testcontainers INSIDE dind; the gateway
    // suite starts only valkey:8-alpine, so it still proves the daemon works without the footprint.
    // Run everything with TEST_TASKS=":test :gateway:app:test" — on a host with the RAM to spare.
    // Full context and the incident this came from: docs/runbooks/ci-jenkins.md.
    string(name: 'TEST_TASKS', defaultValue: ':gateway:app:test',
           description: 'Gradle test tasks to run. Default is narrowed for the local VM.')
  }
  environment { IMAGE_BASE = 'ghcr.io/paul-ayesiga/enterprise-modulith-template' }

  stages {
    stage('Test') {
      steps { sh "./gradlew --no-daemon --max-workers=2 ${params.TEST_TASKS}" }
      post { always { junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml' } }
    }

    stage('Build & push images') {
      when { branch 'main' }
      steps {
        withCredentials([usernamePassword(credentialsId: 'ghcr', usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_PAT')]) {
          sh '''
            echo "$GHCR_PAT" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
            ./gradlew --no-daemon bootBuildImage --imageName "$IMAGE_BASE/modulith:${GIT_COMMIT}" --publishImage
            ./gradlew --no-daemon :gateway:app:bootBuildImage --imageName "$IMAGE_BASE/gateway:${GIT_COMMIT}" --publishImage
          '''
        }
      }
    }

    stage('GitOps bump') {
      when { branch 'main' }
      steps {
        withCredentials([sshUserPrivateKey(credentialsId: 'git-push', keyFileVariable: 'SSH_KEY')]) {
          sh '''
            f=deploy/helm/smsone/values-prod.yaml
            sed -i -E "s|^(  imageTag: ).*# gitops-bump.*$|\\1${GIT_COMMIT} # gitops-bump: CI overwrites this with the built commit SHA|" "$f"
            git config user.name  "jenkins"
            git config user.email "jenkins@smsone.local"
            git add "$f"
            if git diff --cached --quiet; then echo "image tag unchanged"; exit 0; fi
            export GIT_SSH_COMMAND="ssh -i $SSH_KEY -o StrictHostKeyChecking=no"
            git commit -m "chore(gitops): deploy ${GIT_COMMIT} [skip ci]"
            git push origin HEAD:main
          '''
        }
      }
    }
  }
  post { always { cleanWs() } }
}
