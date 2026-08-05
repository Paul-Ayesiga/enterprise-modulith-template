// Self-hosted CI on the local k3s Jenkins — the counterpart to .github/workflows/ci.yml.
// Build work runs in an on-demand agent pod (Kubernetes plugin): a JDK-21 container for Gradle plus a
// Docker-in-Docker sidecar so Testcontainers AND `bootBuildImage` have a Docker daemon (same as the
// GitHub runner had). Trigger is SCM polling — a laptop Jenkins can't receive GitHub webhooks.
//
// Two Jenkins credentials are required (add them in the UI, see deploy/k3s-local/README.md):
//   - 'ghcr'     : username + PAT (write:packages) to push images to GHCR
//   - 'git-push' : an SSH private key allowed to PUSH to main (writes the GitOps image-tag bump)
//
// RAM: the agent pod wants ~2 GB; on an 8 GB VM alongside the app + Argo that's tight — give the VM more
// memory, or point this at a beefier external agent, before leaning on it for every push.
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
      resources:
        requests: { memory: "1400Mi", cpu: "500m" }
        limits:   { memory: "2Gi" }
    - name: dind
      image: docker:27-dind
      securityContext: { privileged: true }
      env:
        - { name: DOCKER_TLS_CERTDIR, value: "" }
      resources:
        requests: { memory: "512Mi" }
        limits:   { memory: "1Gi" }
'''
    }
  }
  options { disableConcurrentBuilds(); timeout(time: 40, unit: 'MINUTES') }
  triggers { pollSCM('H/5 * * * *') }
  environment { IMAGE_BASE = 'ghcr.io/paul-ayesiga/enterprise-modulith-template' }

  stages {
    stage('Test') {
      steps { sh './gradlew --no-daemon :test :gateway:app:test' }
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
