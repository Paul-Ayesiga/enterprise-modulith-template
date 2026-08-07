// Self-hosted CI on the local k3s Jenkins — the counterpart to .github/workflows/ci.yml.
// Build work runs in an on-demand agent pod (Kubernetes plugin): a JDK-21 container for Gradle plus a
// Docker-in-Docker sidecar so Testcontainers AND `bootBuildImage` have a Docker daemon (same as the
// GitHub runner had). Trigger is SCM polling — a laptop Jenkins can't receive GitHub webhooks.
//
// Two Jenkins credentials are required (add them in the UI, see deploy/k3s-local/README.md):
//   - 'ghcr'     : username + PAT (write:packages) to push images to GHCR
//   - 'git-push' : an SSH private key allowed to PUSH to main (writes the GitOps image-tag bump)
//
// THIS NODE HAS TWO CEILINGS, NOT ONE — 8 GB of RAM and 27 GB of disk. Both have taken the cluster
// down, they fail identically from the outside (pods evicted, build aborted), and they need different
// fixes. Memory is the intuitive explanation and this file used to give it for both; the correction is
// below, written out rather than quietly swapped, because the wrong answer is the one a reader will
// reach for again.
//
// CEILING 1 — MEMORY (8 GB), shared with the platform, Argo and the Jenkins controller. A build with
// the full test suite took the node NotReady and knocked every service offline. Two things hold it:
//   1. the build JVM's heap is capped, so Gradle stops sizing itself off the node;
//   2. TEST_TASKS defaults to the gateway suite only (no Keycloak/Postgres/SeaweedFS inside dind).
//
// CEILING 2 — DISK (27 GB), and this is the one that actually hurt.
// CORRECTION: builds #41 and #42 aborted in `Build & push images`, and that was recorded here as
// memory pressure. It was NOT. Build #43 settled it — BUILD_IMAGES was already false so it built no
// images at all, node memory peaked at 3949Mi (53%, nowhere near pressure), and pods were STILL
// evicted. The kubelet names the real cause:
//   Warning FreeDiskSpaceFailed node/gopher — Insufficient free disk space on the node's image
//   filesystem (88% of 26.4 GiB used). Failed to free sufficient space by deleting unused images.
// At failure: 22G of 27G used, 3.5G free, of which 8.9G was this pipeline's own jenkins-dind-cache
// PVC — declared 6Gi, but local-path enforces no quota, so the declaration is documentation only.
//
// THE CASCADE is why disk outranks memory here. With the image filesystem full the kubelet's image
// garbage collector ran and deleted `smsone/modulith:dev`. That image is ctr-imported into containerd
// and the deployment runs it with imagePullPolicy: Never, so nothing in-cluster could re-fetch it: the
// modulith went ErrImageNeverPull and stayed down until the image was rebuilt on the Mac and
// re-streamed in (scripts/k3s-images.sh, ~9 minutes). Disk exhaustion does not merely stall CI on this
// cluster — it destroys the platform's own images. The gateway survived on luck of GC ordering. That
// hazard belongs to the ctr-import/Never combination and outlives anything this file can do.
// So, third:
//   3. BUILD_IMAGES defaults to OFF. It is the heaviest stage AND the one that fills the disk.
// This file now defends the disk in three places: Preflight refuses to start when the filesystem is
// already close to the eviction line, the image stage deletes what it created instead of pruning only
// what was already dangling, and every disk reading is printed so a passing build still shows how
// close it came. All of it is reversible on a bigger host. Full story: docs/runbooks/ci-jenkins.md.
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
        # Points Gradle at the PVC mounted below rather than the pod's ephemeral filesystem.
        - { name: GRADLE_USER_HOME, value: "/gradle-cache" }
      volumeMounts:
        - { name: gradle-cache, mountPath: /gradle-cache }
      resources:
        # Back to 2 Gi: this container runs the Gradle JVM *and* the forked test JVM. Trimming it to
        # 1600 Mi to fund dind is what OOMKilled build #3. The two containers peak in different stages
        # (JVMs during Test, the buildpack lifecycle during Build), so the limits may sum above what the
        # node could serve at once — it is the requests that must fit, and they do.
        requests: { memory: "1Gi", cpu: "500m" }
        limits:   { memory: "2Gi" }
    # The JDK image ships neither `docker` nor `git`. Checkout works because the Jenkins git plugin does
    # it over its own channel, but the GitOps bump shells out to git and died on "git: not found". This
    # container exists solely for that stage; it shares the workspace volume, so it sees the same files.
    - name: git
      image: alpine/git:latest
      command: ["sleep"]
      args: ["infinity"]
      resources:
        requests: { memory: "32Mi", cpu: "50m" }
        limits:   { memory: "128Mi" }
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
      volumeMounts:
        # Persists the Paketo builder/run images across builds, instead of re-pulling ~1 GB every run
        # over a link that has dropped it before. CORRECTION: this comment also used to claim it
        # persisted "the buildpack cache volumes (including the JRE)". It did not, and that is the
        # 8.9 G. Paketo derives cache volume names from the image name, which carries the per-commit
        # tag, so every build minted a fresh pack-cache-<digest>.{build,launch} pair, restored from
        # nothing, and left the old pair behind forever — caches are deliberately never deleted, which
        # is correct only when the name repeats. Persistent plus never-repeating equals immortal.
        # Whatever the build files do about that, the post block below deletes every volume Paketo
        # names for ITSELF, so what this PVC keeps is the builder/run images plus any cache the build
        # files name explicitly — a bounded set either way.
        - { name: dind-cache, mountPath: /var/lib/docker }
  volumes:
    # Survives the pod, so dependencies are downloaded once rather than every build. Declared in
    # deploy/k3s-local/jenkins/jenkins.yaml; delete the PVC to force a cold rebuild.
    - name: gradle-cache
      persistentVolumeClaim:
        claimName: jenkins-gradle-cache
    - name: dind-cache
      persistentVolumeClaim:
        claimName: jenkins-dind-cache
'''
    }
  }
  options { disableConcurrentBuilds(); timeout(time: 40, unit: 'MINUTES') }
  // No triggers block on purpose. This is a MULTIBRANCH job, and jcasc.yaml already gives the folder a
  // 5-minute periodicFolderTrigger that scans and starts branch builds. Declaring pollSCM here as well
  // meant two independent trigger sources firing on the same commit — which is why one GitOps bump
  // produced two builds (#25 and #26) instead of one. Re-add a trigger here only if the folder scan is
  // removed, not alongside it.
  parameters {
    // Narrowed by default so a build actually completes on the 8 GB k3s VM. The modulith's `:test`
    // starts Keycloak (~540 MB), Postgres and SeaweedFS as Testcontainers INSIDE dind; the gateway
    // suite starts only valkey:8-alpine, so it still proves the daemon works without the footprint.
    // Run everything with TEST_TASKS=":test :gateway:app:test" — on a host with the RAM to spare.
    // Full context and the incident this came from: docs/runbooks/ci-jenkins.md.
    string(name: 'TEST_TASKS', defaultValue: ':gateway:app:test',
           description: 'Gradle test tasks to run. Default is narrowed for the local VM.')
    // Off by default, and this note used to say why in terms of RAM: "the next-heaviest thing", the
    // stage that "exhausts the node". Build #43 disproved that reading — no images built, memory at
    // 53%, pods evicted anyway (see the correction in the file header). The stage keeps the default
    // OFF regardless, for two reasons that are worth keeping apart because only one of them was true
    // as written:
    //   - STILL TRUE, and it is about weight: narrowing the tests worked (#41's Test stage passed in
    //     1min 57s) and simply moved the ceiling here. The Paketo lifecycle runs inside dind and
    //     builds TWO images on a node already hosting the platform, Keycloak, Argo CD and this
    //     controller. #41 and #42 both aborted in this stage, 2min 3s in. It is genuinely the heaviest
    //     work the pipeline does, and the first place a squeezed node gives way.
    //   - THE PART THAT WAS MISSING, and it is the failure that actually hurt: on this node this stage
    //     is also the thing that FILLS THE DISK. Every run leaves per-commit tagged images and Paketo
    //     cache volumes in a PVC that local-path never bounds; ~16 such builds put the image
    //     filesystem at 88%, evicted Keycloak, this controller and ELEVEN argocd-repo-server replicas,
    //     and cost the modulith its ctr-imported :dev image. The evictions cleared on their own in
    //     ~17 minutes and the platform pods stayed up — the deleted image did not clear on its own.
    // The stage now cleans up after itself and Preflight refuses to start on a nearly-full disk, but
    // neither makes it cheap: those bound the damage, they do not shrink the build. Turn this on where
    // there is room on BOTH axes, or build images elsewhere — Argo CD deploys whatever tag is
    // committed either way, so the GitOps half is unaffected by leaving it off.
    booleanParam(name: 'BUILD_IMAGES', defaultValue: false,
                 description: 'Build and push the container images, then bump the GitOps tag. '
                            + 'Leave OFF on the 8 GB / 27 GB k3s VM: it is the heaviest stage in the '
                            + 'pipeline (#41 and #42 aborted here) and it is what fills the node disk, '
                            + 'which is what the evictions were — #43 built no images, sat at 53% '
                            + 'memory, and was evicted anyway. See docs/runbooks/ci-jenkins.md.')
  }
  environment {
    IMAGE_BASE = 'ghcr.io/paul-ayesiga/enterprise-modulith-template'
    // Percent-used at which Preflight refuses to start (see the guard for why this number).
    DISK_GUARD_MAX_PCT = '75'
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
    // Breaks the self-feeding poll loop. The GitOps stage pushes a bump to main, pollSCM sees a new
    // commit and builds it, that build pushes another bump — builds #20-#23 were each triggered by the
    // previous one's commit, forever. `[skip ci]` in the message does NOT stop this: that is a GitHub
    // Actions convention and Jenkins' pollSCM has never honoured it. So the pipeline checks for itself.
    stage('Preflight') {
      steps {
        container('git') {
          script {
            sh 'git config --global --add safe.directory "$PWD"'
            // Identify the bump by WHO wrote it and its subject line — not by searching the whole
            // message for "[skip ci]". That first attempt matched any commit that merely *mentioned*
            // the marker: the very commit documenting this guard contained the string in a sentence,
            // so build #27 skipped every stage and reported SUCCESS having built nothing. A guard that
            // silently no-ops real builds is worse than the loop it was written to stop.
            // The author is deterministic — the GitOps stage below sets it explicitly before committing.
            String author = sh(script: 'git log -1 --pretty=%ae', returnStdout: true).trim()
            String subject = sh(script: 'git log -1 --pretty=%s', returnStdout: true).trim()
            env.SELF_TRIGGERED =
                (author == 'jenkins@smsone.local' && subject.startsWith('chore(gitops):')) ? 'true' : 'false'
            echo "HEAD author=${author} subject=${subject} -> selfTriggered=${env.SELF_TRIGGERED}"
            if (env.SELF_TRIGGERED == 'true') {
              echo 'HEAD is this pipeline\'s own GitOps bump — skipping every stage so the loop ends here.'
            }
          }
        }
        // DISK GUARD — the other half of Preflight, and it exists because the alternative to failing
        // here is failing the cluster. This VM has ONE disk and everything shares it: containerd's
        // image store, every local-path PVC, the platform's own data. dind's /var/lib/docker IS a
        // local-path directory on the node, so `df` in this container reads the very filesystem the
        // kubelet watches and evicts on. It is `df` and not `docker system df` deliberately — no
        // docker daemon is required, so the check is valid even while dockerd is still coming up.
        //
        // WHY 75%. The kubelet's hard eviction is imagefs.available<15%, i.e. 85% used; the 88% in the
        // header is what that looks like from the other side. One BUILD_IMAGES run adds on the order
        // of a gigabyte of images and cache volumes, so ten points of headroom is about two builds'
        // worth of slack — enough to refuse the build BEFORE the one that crosses the line instead of
        // diagnosing it afterwards. Raise it only alongside a bigger disk.
        //
        // The numbers are printed on every run, pass or fail, so a green build still records how close
        // it came — the 88% build did not announce itself either.
        container('dind') {
          script {
            // Exit 9, not 1, so "over threshold" is distinguishable from "df could not read the path"
            // — df also exits 1, and a broken meter must not be reported to the user as a full disk.
            int guard = sh(returnStatus: true, script: '''
              df -h /var/lib/docker || true
              # Field indices are counted from the END so a wrapped long device name cannot shift them:
              # NF = mountpoint, NF-1 = Use%, NF-2 = available KiB.
              df -k /var/lib/docker | awk -v max="$DISK_GUARD_MAX_PCT" 'END {
                pct = $(NF-1); sub(/%/, "", pct); pct = pct + 0
                printf "disk guard: node image filesystem %d%% used, %.1f GiB free — this build fails at %d%%, the kubelet evicts at 85%%\\n", pct, $(NF-2) / 1048576, max
                if (pct >= max) { exit 9 }
              }'
            ''')
            if (guard == 9 && env.SELF_TRIGGERED == 'false') {
              error("DISK GUARD: the node's image filesystem is at or above ${env.DISK_GUARD_MAX_PCT}% used "
                  + '(exact figures on the line above). Refusing to start work that would push it into the '
                  + "kubelet's eviction range (imagefs.available<15%). At 88% used this cost more than a "
                  + 'build: Keycloak, the Jenkins controller and eleven argocd-repo-server replicas were '
                  + 'evicted, and the image garbage collector deleted the ctr-imported smsone/modulith:dev '
                  + '— which, running under imagePullPolicy: Never, nothing in-cluster can re-fetch. '
                  + 'Reclaim space before rerunning; the breakdown and the recovery are in '
                  + 'docs/runbooks/ci-jenkins.md.')
            } else if (guard == 9) {
              echo('Disk is over the guard, but HEAD is this pipeline\'s own GitOps bump and every stage '
                 + 'is skipped anyway — recorded, not failed. The next real commit will stop here.')
            } else if (guard != 0) {
              echo("Disk guard could not read /var/lib/docker (exit ${guard}) — continuing without it. A "
                 + 'broken meter should not block every build, but this build is running unguarded.')
            }
          }
        }
      }
    }
    stage('Test') {
      when { environment name: 'SELF_TRIGGERED', value: 'false' }
      steps { sh "./gradlew --no-daemon --max-workers=2 $GRADLE_JVM ${params.TEST_TASKS}" }
      post { always { junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml' } }
    }

    stage('Build & push images') {
      when {
        allOf {
          branch 'main'
          environment name: 'SELF_TRIGGERED', value: 'false'
          expression { params.BUILD_IMAGES }
        }
      }
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
            # --publishImage is a per-TASK option: written once at the end it bound only to the task it
            # followed, so build #12 pushed the gateway and left the modulith built-but-unpublished.
            # It has to be repeated after each task.
            ./gradlew --no-daemon --no-parallel $GRADLE_JVM_IMAGE \
              -PimageBase="$IMAGE_BASE" -PimageTag="${GIT_COMMIT}" \
              :bootBuildImage --publishImage \
              :gateway:app:bootBuildImage --publishImage
          '''
        }
      }
      post {
        always {
          // Housekeeping, rewritten. The PVC is persistent AND unenforced by local-path, so left alone
          // it grows until it fills the VM disk — that part was right. What was wrong is the claim that
          // "dangling (untagged) layers are the part that accumulates". Nothing this stage produces is
          // ever dangling: the app images carry $GIT_COMMIT, Paketo's ephemeral builder is tagged
          // pack.local/builder/<rand>:latest, and the cache volumes are not images at all — `docker
          // image prune` cannot touch a volume. So the one line that used to live here reclaimed almost
          // nothing while the PVC grew to 8.9 G. Remove things BY NAME instead.
          // Never fail the build over housekeeping: every line is `|| true`, and the df at the end
          // leaves the outcome in the log whatever happened above it.
          container('dind') {
            sh '''
              # 1. The two images this build just published. They are in GHCR, which is the point of
              #    pushing them; the local copies buy nothing, because k3s-local runs smsone/*:dev via
              #    ctr import, not these. Tagged, therefore invisible to any prune.
              docker image rm -f "$IMAGE_BASE/modulith:$GIT_COMMIT" "$IMAGE_BASE/gateway:$GIT_COMMIT" || true

              # 2. Everything Paketo names for ITSELF — and that is exactly the disposable set. The
              #    lifecycle's pack-layers-*/pack-app-* volumes are deleted on a clean exit but not by a
              #    killed JVM, and #41/#42 were aborts. The pack-cache-*.{build,launch} pair is
              #    deliberately never deleted, which is correct for a cache whose name repeats and fatal
              #    for one keyed on the per-commit image tag. A cache the build files name explicitly
              #    does NOT match `pack-` and is left alone — that is the whole reason to match on the
              #    prefix rather than enumerate: whatever is reusable is named, whatever is named
              #    survives, and only Paketo's own throwaways are collected. Today that means the four
              #    smsone-{modulith,gateway}-{build,launch} volumes pinned in the build files live and
              #    everything else here dies; the rule holds without editing this line if those change.
              for v in $(docker volume ls -q | grep '^pack-' || true); do docker volume rm -f "$v" || true; done
              # ...and the ephemeral builder image, tagged and so likewise prune-immune. Normally removed
              # in a `finally`, which an aborted build never reaches.
              for i in $(docker image ls -q 'pack.local/builder/*' || true); do docker image rm -f "$i" || true; done

              # 3. Keep the prune. It was never the mechanism behind the growth, but it is cheap and it
              #    does reclaim one real thing: the task pulls the builder with PullPolicy.ALWAYS, so
              #    when builder-noble-java-tiny:latest moves upstream the superseded image goes dangling.
              #    Same idea for the layer build cache.
              docker image prune -f || true
              docker builder prune -f || true

              # NOT `docker system prune -af --volumes`, however tempting after an incident like this.
              # `-a` removes every image not used by a RUNNING container, and between builds nothing runs
              # in dind — so it would take the Paketo builder and run images, the ~1 GB this PVC exists to
              # avoid re-pulling over a link that has already timed builds out ("Read timed out", #10 and
              # #15). `--volumes` would then delete the named caches that make the next build fast. It
              # turns the cache into a no-op with extra steps.
              df -h /var/lib/docker | tail -1
            '''
          }
        }
      }
    }

    // Gated on BUILD_IMAGES too, and that is not tidiness: this stage writes the image TAG that Argo CD
    // then deploys. Left ungated it would bump the tag to a commit whose images were never built, and
    // Argo would faithfully roll the cluster onto an ImagePullBackOff. The two stages are one unit — a
    // tag is only safe to publish once the thing it names exists.
    stage('GitOps bump') {
      when {
        allOf {
          branch 'main'
          environment name: 'SELF_TRIGGERED', value: 'false'
          expression { params.BUILD_IMAGES }
        }
      }
      steps {
        // Runs in the `git` container: the default `build` container is a bare JDK image with no git.
        container('git') {
        withCredentials([sshUserPrivateKey(credentialsId: 'git-push', keyFileVariable: 'SSH_KEY')]) {
          sh '''
            # The workspace arrives from the git plugin's own checkout, which leaves no usable remote or
            # user identity in this container — set both before committing.
            git config --global --add safe.directory "$PWD"
            git config user.name  "jenkins"
            git config user.email "jenkins@smsone.local"
            export GIT_SSH_COMMAND="ssh -i $SSH_KEY -o StrictHostKeyChecking=no"
            f=deploy/helm/smsone/values-prod.yaml

            # Build the bump on top of whatever main is NOW, not on the revision this build checked out
            # seven minutes ago. Committing onto the stale checkout meant any human push during the build
            # made this fail with "! [rejected] (fetch first)" — AFTER both images had been published, so
            # a wholly successful build reported FAILURE (build #28). Re-applying the one-line edit to the
            # current head is safe: nothing else in the pipeline touches this file.
            for attempt in 1 2 3; do
              git fetch --quiet origin main
              git checkout --quiet -B gitops-bump origin/main
              sed -i -E "s|^(  imageTag: ).*# gitops-bump.*$|\\1${GIT_COMMIT} # gitops-bump: CI overwrites this with the built commit SHA|" "$f"
              git add "$f"
              if git diff --cached --quiet; then echo "image tag unchanged"; exit 0; fi
              git commit --quiet -m "chore(gitops): deploy ${GIT_COMMIT} [skip ci]"
              if git push origin HEAD:main; then exit 0; fi
              echo "push rejected — main moved mid-build; retrying on the new head (attempt ${attempt}/3)"
            done
            echo "gitops bump could not be pushed after 3 attempts" >&2
            exit 1
          '''
        }
        }
      }
    }
  }
  post { always { cleanWs() } }
}
