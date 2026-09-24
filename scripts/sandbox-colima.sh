#!/usr/bin/env bash
# Sandbox tools on Colima's k3s under gVisor (docs/phase-2-execution-spec.md slice 2.4).
#
#   scripts/sandbox-colima.sh            install gVisor in the Colima VM, apply infra/k8s/sandbox.yaml,
#                                        smoke-test a gVisor pod and run KubernetesJobSandboxClusterTest
#   scripts/sandbox-colima.sh --agents   also switch the agents Deployment to the kubernetes sandbox
#                                        provider (tilt up re-applies agents.yaml and undoes this)
#
# Needs Colima started with the containerd runtime and Kubernetes:
#   colima start --runtime containerd --kubernetes
# (with the docker runtime, k3s talks to cri-dockerd, which cannot honor a RuntimeClass handler).
set -euo pipefail
cd "$(dirname "$0")/.."

GVISOR_RELEASE="${GVISOR_RELEASE:-20250113}"
K="kubectl --context colima"

colima status 2>&1 | grep -q 'runtime: containerd' || {
  echo "Colima must run with --runtime containerd --kubernetes (see the header of this script)" >&2; exit 1; }

echo "== 1. gVisor ${GVISOR_RELEASE} in the Colima VM"
colima ssh -- sudo sh -s "$GVISOR_RELEASE" <<'VM'
set -eu
release="$1"; arch=$(uname -m)
if ! /usr/local/bin/runsc --version 2>/dev/null | grep -q "release-${release}"; then
  base="https://storage.googleapis.com/gvisor/releases/release/${release}/${arch}"
  tmp=$(mktemp -d); cd "$tmp"
  for f in runsc containerd-shim-runsc-v1; do
    wget -q "${base}/${f}" "${base}/${f}.sha512"
    sha512sum -c "${f}.sha512"
    install -m 755 "$f" /usr/local/bin/
  done
  rm -rf "$tmp"
fi
# k3s regenerates config.toml on start; runtimes are added through config.toml.tmpl.
cfg=/var/lib/rancher/k3s/agent/etc/containerd/config.toml
tmpl="${cfg}.tmpl"
if ! grep -qs runsc "$tmpl"; then
  cp "$cfg" "$tmpl"
  if grep -q '^version = 3' "$cfg"; then
    printf '\n[plugins."io.containerd.cri.v1.runtime".containerd.runtimes.runsc]\n  runtime_type = "io.containerd.runsc.v1"\n' >> "$tmpl"
  else
    printf '\n[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runsc]\n  runtime_type = "io.containerd.runsc.v1"\n' >> "$tmpl"
  fi
fi
/usr/local/bin/runsc --version | head -1
VM

echo "== 2. restart k3s so containerd registers runsc"
colima kubernetes stop >/dev/null
colima kubernetes start >/dev/null
for _ in $(seq 1 60); do $K get nodes 2>/dev/null | grep -q ' Ready' && break; sleep 3; done

echo "== 3. namespace, RuntimeClass, RBAC and proxy Service"
$K apply -f infra/k8s/sandbox.yaml

echo "== 4. smoke test: a pod under the gvisor RuntimeClass"
$K -n pdlc-sandbox delete pod gvisor-smoke --ignore-not-found --now >/dev/null
$K -n pdlc-sandbox run gvisor-smoke --image=busybox:1.36 --restart=Never \
  --overrides='{"spec":{"runtimeClassName":"gvisor","securityContext":{"runAsNonRoot":true,"runAsUser":65534,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"gvisor-smoke","image":"busybox:1.36","command":["sh","-c","dmesg | head -1"],"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' >/dev/null
$K -n pdlc-sandbox wait --for=jsonpath='{.status.phase}'=Succeeded pod/gvisor-smoke --timeout=120s >/dev/null
$K -n pdlc-sandbox logs gvisor-smoke | grep 'Starting gVisor' || { echo "the smoke pod did not run under gVisor" >&2; exit 1; }
$K -n pdlc-sandbox delete pod gvisor-smoke --now >/dev/null

echo "== 5. KubernetesJobSandbox against this cluster, as the agents-sandbox service account"
tmp=$(mktemp -d)
$K config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' | base64 -d > "$tmp/ca.pem"
PDLC_K8S_API=$($K config view --minify -o jsonpath='{.clusters[0].cluster.server}') \
PDLC_K8S_TOKEN=$($K -n pdlc create token agents-sandbox --duration 30m) \
PDLC_K8S_CA="$tmp/ca.pem" \
  mvn -q -pl adapters -am test -Dtest=KubernetesJobSandboxClusterTest -Dsurefire.failIfNoSpecifiedTests=false
rm -rf "$tmp"
echo "   Job spec accepted, NetworkPolicy installed, runs and terminateRun leave nothing behind"

if [ "${1:-}" = "--agents" ]; then
  echo "== 6. agents: kubernetes sandbox provider"
  $K -n pdlc set serviceaccount deployment/agents agents-sandbox
  $K -n pdlc set env deployment/agents PDLC_SANDBOX_PROVIDER=kubernetes PDLC_SANDBOX_RUNTIME=gvisor \
    PDLC_SANDBOX_PROXY_ADVERTISED_HOST=sandbox-egress-proxy.pdlc.svc.cluster.local
  $K -n pdlc rollout status deployment/agents --timeout=180s
  $K -n pdlc logs deployment/agents | grep 'Sandbox tools run on' || true
fi
