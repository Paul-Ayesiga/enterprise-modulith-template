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
        # Every gradlew run is TWO JVMs: this launcher, plus the single-use daemon it forks (--no-daemon
        # prevents a *reused* daemon, not a forked one). -Dorg.gradle.jvmargs caps only the daemon; left
        # alone the launcher takes ~25% of the container limit — ~512 MB it never needs, since it does
        # nothing but forward to the daemon. Adding that to the daemon's heap and the page cache left by
        # the previous image build is what OOMKilled this container between the two bootBuildImage calls.
        - { name: GRADLE_OPTS, value: "-Xmx256m" }
      resources:
        # Back to 2 Gi: this container runs the Gradle JVM *and* the forked test JVM. Trimming it to
        # 1600 Mi to fund dind is what OOMKilled build #3. The two containers peak in different stages
        # (JVMs during Test, the buildpack lifecycle during Build), so the limits may sum above what the
        # node could serve at once — it is the requests that must fit, and they do.
        requests: { memory: "1Gi", cpu: "500m" }
        limits:   { memory: "2Gi" }
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
  environment {
    IMAGE_BASE = 'ghcr.io/paul-ayesiga/enterprise-modulith-template'
    // gradle.properties pins `org.gradle.jvmargs=-Xmx2g`, sized for a laptop. GRADLE_OPTS does NOT
    // override it: even under --no-daemon Gradle forks a single-use daemon with those args, so build #3
    // ran a 2 GB heap inside a 1.6 GB container and was OOMKilled mid-test. This is the override that
    // actually takes, and it must be passed to every gradlew call, not set as an env var.
    GRADLE_JVM = '-Dorg.gradle.jvmargs=-Xmx768m'
    // Image builds need far less daemon heap than tests do: the Paketo lifecycle runs inside dind, and
    // Gradle only orchestrates it. Keeping this low is what leaves room for the second invocation.
    GRADLE_JVM_IMAGE = '-Dorg.gradle.jvmargs=-Xmx512m'
  }

  stages {
    stage('Test') {
      steps { sh "./gradlew --no-daemon --max-workers=2 $GRADLE_JVM ${params.TEST_TASKS}" }
      post { always { junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml' } }
    }

    stage('Build & push images') {
      when { branch 'main' }
      steps {
        withCredentials([usernamePassword(credentialsId: 'ghcr', usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_PAT')]) {
          sh '''
            # No `docker login` here: this container is a JDK image with a dind sidecar, so there is a
            # Docker daemon on DOCKER_HOST but no docker binary — the login died with "docker: not found".
            # It was never the right mechanism anyway; --publishImage authenticates with the credentials
            # configured on the bootBuildImage task, which read GHCR_USER/GHCR_PAT straight from here.
            # The leading colon is load-bearing. Unqualified, `bootBuildImage` matches the task in EVERY
            # project, so this one line built the modulith AND the gateway — both under the modulith's
            # --imageName. Paketo derives its cache volume names from the image name, so the two builds
            # shared one /launch-cache and (org.gradle.parallel=true) raced it: "failed to export:
            # caching layer ... /launch-cache/staging/...tar: no such file or directory". The gateway
            # then published itself to GHCR wearing the modulith's tag.
            # ONE invocation for both images, and --no-parallel so they run one after the other inside it.
            # Two separate gradlew runs were OOMKilled at the seam every time: the second JVM pair started
            # while the first image build's memory was still resident. Names come from -PimageBase and
            # -PimageTag (see the build files) because --imageName cannot differ per task in one run.
            ./gradlew --no-daemon --no-parallel $GRADLE_JVM_IMAGE \
              -PimageBase="$IMAGE_BASE" -PimageTag="${GIT_COMMIT}" \
              :bootBuildImage :gateway:app:bootBuildImage --publishImage
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
