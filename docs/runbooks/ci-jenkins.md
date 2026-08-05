# Runbook — self-hosted Jenkins on local k3s

**Scope** — signing in, the two CI credentials, and the capacity limit that will take the whole cluster
down if you ignore it. Not alert-driven: this is the operator's page for the controller installed by
`make k3s-jenkins` (manifests in `deploy/k3s-local/jenkins/`, pipeline in the root `Jenkinsfile`).

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

## Capacity — the build agent does not fit on this VM

**This is the important section.** A build has already taken the entire cluster down once.

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

**So the job ships disabled after that incident.** Re-enable it only once one of these is true:

- the build runs somewhere with real headroom (a beefier external agent, or not this laptop);
- the Test stage stops needing Testcontainers on this VM;
- the dind cap is raised *and* the VM has the RAM to back it — note the Mac is 16 GB with 8 GB already
  in the VM, so "give the VM more memory" is not available here.

Scaling `modulith.replicas` to 1 in `values-local.yaml` bought ~1.5 GB and was still not enough.

## Recovery — cluster wedged during or after a build

Run these from the VM (`ssh gopher@192.168.64.5`); the Mac's `kubectl` sometimes cannot reach `:6443`
while the node is under pressure.

1. Confirm the shape of it — a node problem, not a Jenkins problem:

       kubectl get nodes
       kubectl get pods -A | grep -vE 'Running|Completed'
       kubectl top node

2. Kill the agent, which is almost always what is still eating the node. Aborting the build in the UI
   is not enough — it returns 302 and leaves the pod running:

       kubectl -n jenkins delete pod -l jenkins=slave --wait=false

3. Stop it starting another one:

       curl -s -u admin:$PW -X POST http://jenkins.smsone.local/job/enterprise-modulith-template/disable

   (needs a CSRF crumb from `/crumbIssuer/api/json` as the `Jenkins-Crumb` header)

4. Everything else self-heals — Argo CD, the app and the controller come back without help. Confirm:

       for u in jenkins argocd auth; do curl -s -o /dev/null -w "$u %{http_code}\n" http://$u.smsone.local/; done

   Expect 200, 200, 200; `api.smsone.local/api/v1/me` answers 401 without a token, which is correct.

## Re-enabling the job

    curl -s -u admin:$PW -H "Jenkins-Crumb: $CRUMB" -X POST \
      http://jenkins.smsone.local/job/enterprise-modulith-template/enable

Or the **Enable** button on the job page. It scans `main` every 5 minutes and builds on a change, so
re-enabling it with the capacity problem unfixed will reproduce the outage.
