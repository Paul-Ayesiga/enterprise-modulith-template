# Runbook — self-hosted Jenkins on local k3s

**Scope** — signing in, the two CI credentials, and the two capacity limits that have each taken the
whole cluster down: memory, and disk. Not alert-driven: this is the operator's page for the controller
installed by `make k3s-jenkins` (manifests in `deploy/k3s-local/jenkins/`, pipeline in the root
`Jenkinsfile`).

**There is no setup wizard.** Plugins come from `plugins.txt` and the entire controller config — admin
user, Kubernetes cloud, credentials, the multibranch job — comes from `jcasc.yaml`, applied at boot by
the Configuration-as-Code plugin. `jcasc.yaml` is the source of truth: anything it configures is
rewritten from that file on every restart, so change it in Git, never in the UI.

## Signing in

User `admin`. The password is generated at install and lives in the `jenkins-secrets` Secret:

    kubectl -n jenkins get secret jenkins-secrets -o jsonpath='{.data.JENKINS_ADMIN_PASSWORD}' | base64 -d; echo

`/var/jenkins_home/secrets/initialAdminPassword` still exists in the PVC. It is a leftover from the
pre-JCasC install, it authenticates nothing, and it will keep looking like the answer. Ignore it.

UI at http://jenkins.smsone.local, or `kubectl -n jenkins port-forward svc/jenkins 8080:8080`.

## The two CI credentials

Both live in `jenkins-secrets` and are interpolated into `jcasc.yaml` at boot. Neither is in Git.

| id | What it is | Used by |
|---|---|---|
| `git-push` | SSH private key, write-enabled deploy key | Private-repo checkout **and** the GitOps tag bump |
| `ghcr` | GitHub username + PAT with `write:packages` | Pushing images in *Build & push images* |

### git-push — configured

Deploy key **`jenkins-ci (k3s, write)`** on the repo, read-write. Private half kept at
`~/.ssh/smsone_jenkins_deploy` on the Mac; the same bytes are in the Secret. Verify the key still
authenticates:

    ssh -i ~/.ssh/smsone_jenkins_deploy -o IdentitiesOnly=yes -T git@github.com

Expect `Hi Paul-Ayesiga/enterprise-modulith-template! You've successfully authenticated`. To rotate,
generate a new pair, `gh repo deploy-key add <pub> --title "…" --allow-write`, then patch and restart
as below. Note the repo also carries `argocd-local (read-only)` — do not confuse the two.

### ghcr — outstanding

Create a classic PAT with **only** `write:packages` at https://github.com/settings/tokens, then patch it
in. `read -rsp` keeps it off the screen and out of shell history — never paste a token onto a command
line, and never into a chat:

    read -rsp "GHCR PAT: " PAT && kubectl -n jenkins patch secret jenkins-secrets \
      -p "{\"data\":{\"GHCR_USER\":\"$(printf 'Paul-Ayesiga' | base64)\",\"GHCR_PAT\":\"$(printf '%s' "$PAT" | base64)\"}}" \
      && unset PAT && echo && echo patched

Until it is set the value is the literal string `unset`, and *Build & push images* fails at
`docker login ghcr.io`. Everything before that stage still runs.

### After changing either secret

JCasC only reads the Secret at boot, so the credential does not change until the controller restarts:

    kubectl -n jenkins rollout restart deploy/jenkins
    kubectl -n jenkins rollout status deploy/jenkins --timeout=300s

Patch the Secret rather than deleting and re-running `make k3s-jenkins` — deleting it regenerates the
admin password too (unless you pass `JENKINS_ADMIN_PASSWORD=…`).

## Two outages, and how to tell them apart

A build has taken this cluster down twice, for two unrelated reasons, and the second was misdiagnosed
as a recurrence of the first because from the outside they are the same event.

**What both look like.** Pods evicted across the node. Jenkins and Keycloak unreachable —
`ERR_CONNECTION_REFUSED` in the browser. The build ends **ABORTED**, not FAILED. Nobody touches
anything and the node goes `Ready` again on its own inside ~17 minutes, which is long enough to
convince you that whatever you did in minute 12 was the fix.

**What separates them, in one command each.**

| | Outage 1 — memory | Outage 2 — disk |
|---|---|---|
| `kubectl top node` | >90% | **53%** (3949Mi) — the tell |
| `df -h /` | unremarkable | **22G of 27G, 3.5G free** |
| Node condition | `MemoryPressure` → `NodeNotReady` | `DiskPressure` → `FreeDiskSpaceFailed` |
| Trigger | any build; the agent hits ~1.7 GB during Gradle's task graph | any build — **or none**: #43 built no images and still evicted |
| Recovers fully on its own | yes | **no** — it deletes images that cannot be re-pulled |

The single most useful habit: **`kubectl top node` is not the answer, it is half the answer.** A node at
53% memory that is evicting pods is telling you to go look at the disk.

### First five minutes

From the VM — the Mac's `kubectl` often cannot reach `:6443` while the node is under pressure:

    ssh gopher@192.168.64.5

    kubectl get nodes                                            # Ready / NotReady
    kubectl describe node gopher | grep -A6 '^Conditions'        # MemoryPressure vs DiskPressure
    kubectl top node                                             # >90% = outage 1. ~50% = keep going.
    df -h /                                                      # >85% = outage 2. Check this every time.
    kubectl get events -A --sort-by=.lastTimestamp | tail -30    # NodeNotReady vs FreeDiskSpaceFailed
    kubectl -n smsone get pods                                   # ErrImageNeverPull = image GC ate an image

`ErrImageNeverPull` on a platform pod means you are in outage 2 and it will not self-heal — go straight
to *Recovery — the disk path*, because the image has to be rebuilt on the Mac before anything else
matters.

## Outage 1, memory — the build agent does not fit on this VM

**This is the important section.** A build has already taken the entire cluster down once through memory
alone. Everything below is accurate for *that* failure. It is not the whole story any more — the second
outage, in the next section, produced the same symptoms with memory at 53% — so read both before you
decide which one you are in.

The agent pod is the Jenkinsfile's `build` container (1.4 GB request / 2 GB limit) plus a `dind`
sidecar (0.5 GB / 1 GB). Against an 8 GB VM that already runs the platform, Argo CD and the Jenkins
controller, that is more than the node can spare — and the tests make it worse, because the suite
starts real Testcontainers (Postgres, Keycloak, SeaweedFS) *inside* dind, against dind's 1 GB cap.
Keycloak alone idles at ~538 MB.

What happened, so you recognise it: the agent reached ~1.7 GB while Gradle was still calculating the
task graph — before a single test ran. The node ran out of memory, the kubelet lost its heartbeat, and
`kubectl` showed the tell-tale events

    Warning  NodeNotReady  pod/enterprise-modulith-template-main-2-…  Node is not ready
    Warning  NodeNotReady  pod/jenkins-…                              Node is not ready

Every pod on the node was marked for eviction, so Jenkins, Argo CD and the app all went unreachable at
once — `ERR_CONNECTION_REFUSED` in the browser. The cluster recovered on its own within a few minutes,
but the agent pod survived the restart and kept climbing, and `pollSCM` plus the 5-minute folder scan
would have started another build straight into the same wall.

Scaling `modulith.replicas` to 1 in `values-local.yaml` bought ~1.5 GB and was still not enough.

### What was changed to make a build fit

Three things, all in the `Jenkinsfile` and all reversible on a bigger host:

- **`TEST_TASKS` defaults to `:gateway:app:test`.** The modulith's `:test` is what hurts — it starts
  `postgres:18.4-alpine`, `quay.io/keycloak/keycloak:26.7.0` and `chrislusf/seaweedfs:4.40` inside
  dind. The gateway suite starts only `valkey:8-alpine`, so dind is still genuinely exercised. Run the
  full suite by setting the build parameter to `:test :gateway:app:test`.
- **The build JVM is capped — via `-Dorg.gradle.jvmargs=-Xmx768m` on every `gradlew` call.** The
  mechanism matters, because the obvious version does not work: `gradle.properties` pins
  `org.gradle.jvmargs=-Xmx2g`, and **`GRADLE_OPTS` does not override it**. Even under `--no-daemon`
  Gradle forks a single-use daemon with the `gradle.properties` args ("To honour the JVM settings for
  this build a single-use Daemon process will be forked"), so a first attempt at this ran a 2 GB heap
  inside a 1.6 GB container and the container was `OOMKilled` mid-test. It must be a command-line
  property, and it must be on the image-build invocations too, not only the test one.
- **`dind` raised to 1800 MB, `build` left at 2 GB.** `bootBuildImage` runs the whole Paketo lifecycle
  inside the daemon and 1 GB was short. Do *not* fund that by shrinking `build` — that container holds
  the Gradle JVM and the forked test JVM, and starving it is the OOMKill above. The two peak in
  different stages, so their limits may sum above what the node could serve simultaneously; only the
  requests have to fit, and they do.

If the `build` container is `OOMKilled`, expect the build to end as **ABORTED**, not FAILED, with
`AgentOfflineException` in the post steps and no archived test results — the agent dies before Jenkins
can collect them. Any test failures printed just before it are suspect: a JVM being strangled fails
timing-sensitive tests (cache TTLs, blocklist windows) that pass cleanly with headroom. Fix the memory
first, then judge the tests.

The image-build stage remains the heaviest part of the pipeline, and it is still the stage to suspect
first. But **do not assume memory.** "Build one image rather than both" is the fix for *this* section
only; it does nothing for the disk failure, which is triggered by the same stage and looks the same from
the outside. Run `df -h /` before you act on that instinct.

## Outage 2, disk — the dind cache filled the node and the kubelet ate our images

**This one is worse than outage 1**, because memory pressure ends when the process dies and disk
pressure ends by destroying things you cannot get back in-cluster.

What was measured, in order:

- **#41** — Test stage **passed** in 1min 57s, then the build aborted in *Build & push images* after
  2min 3s. Keycloak, the Jenkins controller and **eleven** `argocd-repo-server` replicas evicted.
- **#42** — aborted the same way. At this point it reads exactly like outage 1 recurring.
- **#43** — run after `BUILD_IMAGES` was defaulted to `false`, so it **built no images at all**. Node
  memory peaked at **3949Mi = 53%**, nowhere near pressure, and pods were **still evicted**.

Build #43 is the proof. There is no memory story that explains a node evicting pods at 53% while
building nothing. The kubelet had already named the real cause:

    Warning  FreeDiskSpaceFailed  node/gopher
      Insufficient free disk space on the node's image filesystem (88% of 26.4 GiB used).
      Failed to free sufficient space by deleting unused images (freed 385820817 bytes).

Disk at failure: **22G of 27G used, 3.5G free.** All of it in one place:

    8.9G  jenkins-dind-cache      <-- declared 6Gi
    747M  jenkins-gradle-cache
    403M  jenkins-home
     79M  postgres-data
    284K  seaweedfs-data
          (/var/lib/rancher/k3s/storage)

### Why a 6Gi PVC was 8.9G

**`local-path` does not enforce quotas.** The `storage: 6Gi` in
`deploy/k3s-local/jenkins/jenkins.yaml` is documentation, not a limit — the volume is a directory on
the node, so dind writes until the *node* is full, and then everything on the node pays. That caution is
written into the manifest itself; #41 is what it looks like when it comes true.

The only housekeeping the pipeline had **at the time** was `docker image prune -f`, and it was aimed at
the wrong object class. Two things it cannot reach:

- **The per-commit app images.** `Jenkinsfile` passes `-PimageTag="${GIT_COMMIT}"` — the full 40-char
  SHA — so each build produces `…/modulith:<sha>` and `…/gateway:<sha>`. `--publishImage` does *not*
  keep them out of the local daemon: the Paketo exporter runs with `-daemon`, writes into
  `/var/lib/docker`, and the push reads *from* there. Tagged images are never dangling, so `prune`
  skips them forever.
- **Volumes. Which is where the mass actually is.** `prune` operates on images; there is no
  `docker volume` command anywhere in the repo. Spring Boot 4.1.0 names its buildpack cache volumes
  `pack-cache-<sha256(image name)[0:12]>.{build,launch}`, and the image name it hashes **includes the
  tag** — which is the commit SHA. So every commit gets four brand-new cache volumes, and
  `Lifecycle.close()` deliberately never deletes `pack-cache-*` (that is what makes it a cache). Give a
  cache a name that never repeats and "persistent" becomes "immortal". The `.launch` volume is a full
  independent copy of that build's launch layers; the images at least share base layers.

  The second half of that bug is that the dind PVC never delivered what it was added for. Its stated
  job is to persist "the buildpack cache volumes (including the JRE) across builds" — the builder and
  run images were reused, the caches were **not, not once**. Every build restored from empty.

Window: the dind PVC landed in `4cdebae` (2026-08-06), image builds became opt-in in `8adc4ea`
(2026-08-07). ~16 image-building builds between them — builds ≈#28–#42 — so roughly 64 permanent cache
volumes. Aborted builds leave more: `pack-layers-*` / `pack-app-*` orphans and a `pack.local/builder/*`
image that a hard-killed JVM never got to clean up.

The `Jenkinsfile` now sweeps all of that in a `post` block that survives an aborted stage — the per-SHA
images, every `pack-*` volume, the ephemeral builders — and logs `df -h /var/lib/docker` on the way out.
That sweep is safe *because* of the pinning below: the live caches are named `smsone-*`, so a blanket
`grep '^pack-'` can never eat one.

**What bounds it** — pinning the cache names so they stop varying with the commit. This is in
`build.gradle.kts` now:

    tasks.bootBuildImage {
        buildCache  { volume { name.set("smsone-modulith-build")  } }
        launchCache { volume { name.set("smsone-modulith-launch") } }
    }

and the same block in `gateway/app/build.gradle.kts` with `smsone-gateway-build` /
`smsone-gateway-launch`. Four volumes total instead of four per build — and the restorer finally hits,
so the stage gets faster and its peak footprint drops. If you ever see `pack-cache-*` volumes reappear
in dind, that pinning has been lost.

**The two projects must use different names.** Sharing one launch cache between modulith and gateway is
a failure this repo has already had, recorded in the comment above the image-build stage: the two builds
raced one `/launch-cache` and died on `caching layer … no such file or directory`. Even serialised they
would evict each other's layers on every run.

**Do not "fix" this with `docker system prune -af --volumes`.** Between builds nothing is running in
dind, so `-a` deletes `paketobuildpacks/builder-noble-java-tiny:latest` and its run image — ~1 GB
re-fetched on every build, over the same uplink that already killed builds #10 and #15 with
"Read timed out" — and `--volumes` deletes the caches you just made worth keeping.

*Not yet measured:* the per-build volume **count** is proven from the Boot 4.1.0 sources, but nobody has
seen the **sizes** inside dind — the PVC was deleted during recovery, so that evidence is gone. Run this
in the dind container after each of the next three image builds; the sizes should plateau, not climb:

    docker system df -v | grep pack-cache

### The cascade — what makes this unrecoverable in-cluster

With the image filesystem full, the kubelet's **image garbage collector** ran, and it does not know
which images are precious. It deleted **`smsone/modulith:dev`**.

That image was never pulled from anywhere. `scripts/k3s-images.sh` builds it on the Mac and streams it
into containerd via `k3s ctr images import`, and the chart runs it with `imagePullPolicy: Never`
(`values-local.yaml`). So once GC removes it, **nothing in the cluster can put it back.** The modulith
went `ErrImageNeverPull` and stayed down — not until pressure cleared, but until the image was rebuilt
on the Mac and re-streamed in, about 9 minutes. The gateway survived purely on GC ordering; there is no
mechanism protecting it.

This is the part to remember at 2am: **disk exhaustion here does not just stall CI, it deletes the
platform's own images.** Outage 1 self-heals completely. Outage 2 leaves the node healthy and the
platform still broken, which is exactly the state that makes you think the disk was never the problem.

### Recovery — the disk path

**1. Confirm it is disk, and find it.**

    df -h /
    sudo du -sh /var/lib/rancher/k3s/storage/* | sort -h

**2. Delete the dind cache PVC.** It is a cache; there is nothing in it worth keeping. Kill the agent
first or the delete hangs on the volume's finalizer:

    kubectl -n jenkins delete pod -l jenkins=slave --wait=false
    kubectl -n jenkins delete pvc jenkins-dind-cache
    df -h /

Measured: **87% → 52%, 13G free.** Keycloak and the Jenkins controller reschedule themselves from here
and the platform pods never went down — so if you stop at this step everything *looks* fixed.

**Recreate the claim before the next build**, or the agent pod sits `Pending` on an unbound volume —
the Jenkinsfile's pod template mounts it by `claimName: jenkins-dind-cache`:

    kubectl apply -f deploy/k3s-local/jenkins/jenkins.yaml

The first build after this is cold: the Paketo builder and run images are re-pulled, ~1 GB. Expect it to
be slow, and expect it to be the build most likely to hit the "Read timed out" failure from #10/#15.

**3. Find out what the image GC ate.** This is the step that gets skipped:

    kubectl -n smsone get pods                       # ErrImageNeverPull?
    sudo k3s ctr images ls -q | grep smsone          # expect smsone/{modulith,gateway}:dev

**4. Rebuild and re-import from the Mac** — *not* from the VM. ~9 minutes, ~1.6 GB over SSH:

    make k3s-images                       # or: scripts/k3s-images.sh
    SKIP_BUILD=1 scripts/k3s-images.sh    # re-import only, if smsone-*:local are still current

There is no per-image path; it does both. Then re-check `k3s ctr images ls`.

**5. Restart the deployment.**

    kubectl -n smsone rollout restart deploy/modulith
    kubectl -n smsone rollout status deploy/modulith --timeout=300s

**The label trap.** If you reach for `delete pod -l` instead, the selector is
`app.kubernetes.io/name=modulith`, **not** `app=modulith`:

    kubectl -n smsone delete pod -l app=modulith                      # "No resources found" — did nothing
    kubectl -n smsone delete pod -l app.kubernetes.io/name=modulith   # correct

Both exit 0. The wrong one prints a line that reads like success and you move on believing you restarted
something. The cluster genuinely uses both conventions: chart-managed workloads (modulith, gateway,
keycloak, the portals) are `app.kubernetes.io/name=`, while the hand-written manifests under
`deploy/k3s-local/` — postgres, valkey, seaweedfs, and Jenkins itself — are `app=`. Check the manifest,
do not guess.

**6. Sweep the evicted pod records.** The eleven `argocd-repo-server` entries are corpses, not running
replicas; they hold no resources but they will make `get pods` unreadable for the next incident:

    kubectl delete pod -A --field-selector status.phase=Failed

Do **not** re-enable `BUILD_IMAGES` as part of this recovery. Build #41 was reported with memory
symptoms and build #43 proved disk; those are two different ceilings, and clearing one says nothing
about the other.

## Flyway checksum mismatch — surfaced during recovery, unrelated to it

**This was not part of the outage.** It has nothing to do with disk, memory or Jenkins. It is recorded
here only because it is what you hit *next* if you bring this cluster back with a database that predates
the identity refactor, and at 2am it will look like more fallout.

That refactor rewrote **39 migrations in place** — deliberately, pre-production — so every checksum
changed. Flyway refuses to start:

    Migration checksum mismatch for migration version 3, 5, 6, 8, 9...

The modulith will not boot. `flyway repair` is the wrong tool here: the migrations did not just change
checksum, they changed content, so a repaired history would describe a schema that was never applied.

**Verify the database is empty first.** This cluster's was — 0 organizations, 0 users:

    kubectl -n smsone exec deploy/postgres -- psql -U modulith -d modulith \
      -c 'select (select count(*) from organization) orgs, (select count(*) from person) people;'

If that errors with `relation "person" does not exist`, you are on a schema old enough to predate the
rename — which confirms the diagnosis rather than contradicting it. Fall back to
`\dt` and count whatever the tenant and user tables were called then.

Both zero, and only then:

    kubectl -n smsone exec deploy/postgres -- psql -U modulith -d modulith \
      -c 'drop schema public cascade; create schema public;'
    kubectl -n smsone rollout restart deploy/modulith

Flyway then applies all **51** migrations cleanly from scratch. This touches only the `modulith`
database — Keycloak's is a separate database on the same server and is not affected, so realm config
and users survive. If either count is non-zero, stop and get a real migration; you are looking at data
loss, not a reset.

## A test that passes locally and fails only in CI

Spring Boot changes defaults when it detects a cloud platform, and the build pod *is* one — so the
agent can behave differently from your laptop for reasons that have nothing to do with the code. That is
not theoretical: it is how `X-Forwarded-For` came to be trusted in Kubernetes and nowhere else, which
made the blocklist evadable in production while every local run stayed green.

Reproduce that class of failure in seconds instead of a 5-minute CI round trip — Spring detects
Kubernetes purely from the environment:

    KUBERNETES_SERVICE_HOST=10.43.0.1 KUBERNETES_SERVICE_PORT=443 \
      ./gradlew :gateway:app:test --tests "*BlocklistTest*"

The other half of the same trap: `gateway/app/src/test/resources/application.yml` **shadows** the shipped
`src/main/resources/application.yml` on the test classpath. A security-relevant setting added only to the
shipped file is silently absent under test, and the suite will happily prove a guarantee the product does
not actually make. When you pin something there, mirror it — `ShippedRouteTableTest` guards the shipped
file precisely because nothing else does.

## A build that will not die, and the PVC that stops CI dead

Two failure modes met while recovering from the disk outage. Both look like Jenkins being broken and
neither is.

**The wedged build.** A build whose agent pod is evicted mid-step can survive its own 40-minute
timeout: the console shows `Aborted by admin` on repeat next to "Click here to forcibly terminate
running steps", `duration` stays 0, and `building` stays true forever. Because the job declares
`disableConcurrentBuilds()`, EVERY later build then sits in the queue behind it with
`why: "Build #N is already in progress"` — so the pipeline looks idle when it is actually jammed.

The UI's abort does nothing here, and neither does the API's `/stop` or `/term` (both answered 302 and
changed nothing). Only the hard kill works:

    curl -u admin:$PW -H "Jenkins-Crumb: $C" -X POST \
      http://jenkins.smsone.local/job/enterprise-modulith-template/job/main/<N>/kill

Check `.../<N>/api/json?tree=building,result` afterwards — it should read `building:false`,
`result:"ABORTED"` — and confirm `/queue/api/json` has drained.

**The missing PVC.** `kubectl -n jenkins delete pvc jenkins-dind-cache` is the right way to reclaim the
node's disk, and it does NOT recreate the claim. The agent pod template mounts it by name, so from that
moment every build is Unschedulable:

    Pod [Pending][Unschedulable] 0/1 nodes are available:
      persistentvolumeclaim "jenkins-dind-cache" not found

Nothing fails; builds simply never start, which is easy to misread as a quiet queue. Re-apply the
manifest — `kubectl apply -f deploy/k3s-local/jenkins/jenkins.yaml` — or just the PVC document from it.

**And then wait for the old agent pod to be gone.** The claim is ReadWriteOnce, but on a single-node
cluster both pods schedule to that one node and both mount `/var/lib/docker`, so two dockerd instances
race the same state and the sidecar dies with `failed to start containerd: timeout waiting for
containerd to start`. Kill the build, then:

    kubectl -n jenkins delete pods -l jenkins=slave --wait=true
    kubectl -n jenkins get pods            # only jenkins-<hash> should remain

before triggering the next one.

## Recovery — cluster wedged during or after a build

Run these from the VM (`ssh gopher@192.168.64.5`); the Mac's `kubectl` sometimes cannot reach `:6443`
while the node is under pressure.

1. Confirm the shape of it — a node problem, not a Jenkins problem — and settle memory vs disk before
   you touch anything else:

       kubectl get nodes
       kubectl get pods -A | grep -vE 'Running|Completed'
       kubectl top node
       df -h /

   If memory is high, continue here. If memory is ~50% and the disk is over 85%, this is outage 2 —
   go to *Recovery — the disk path*. The steps below will make the node look healthy and leave the
   modulith down on `ErrImageNeverPull`.

2. Kill the agent, which is almost always what is still eating the node. Aborting the build in the UI
   is not enough — it returns 302 and leaves the pod running:

       kubectl -n jenkins delete pod -l jenkins=slave --wait=false

3. Stop it starting another one:

       curl -s -u admin:$PW -X POST http://jenkins.smsone.local/job/enterprise-modulith-template/disable

   (needs a CSRF crumb from `/crumbIssuer/api/json` as the `Jenkins-Crumb` header)

4. Everything else self-heals — Argo CD, the app and the controller come back without help. Confirm:

       for u in jenkins argocd auth; do curl -s -o /dev/null -w "$u %{http_code}\n" http://$u.smsone.local/; done

   Expect 200, 200, 200; `api.smsone.local/api/v1/me` answers 401 without a token, which is correct.

   "Self-heals" holds for memory pressure only. After disk pressure the HTTP checks can all come back
   green while `smsone/modulith:dev` is gone from containerd — the ingress answers, the pod does not
   start. Check `kubectl -n smsone get pods` too, not just the curls.

## Re-enabling the job

    curl -s -u admin:$PW -H "Jenkins-Crumb: $CRUMB" -X POST \
      http://jenkins.smsone.local/job/enterprise-modulith-template/enable

Or the **Enable** button on the job page. It scans `main` every 5 minutes and builds on a change, so
re-enabling it with either capacity problem unfixed will reproduce the matching outage. Before you do,
check both ceilings: `kubectl top node` for memory, and `df -h /` on the VM for disk. Fixing one does
not buy you the other.
