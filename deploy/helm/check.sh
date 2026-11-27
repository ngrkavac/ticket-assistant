#!/usr/bin/env bash
# Checks the Helm chart: helm lint and kubeconform (schema validation of the rendered manifests)
# for every environment, plus the chart's own guards. Used by CI (.github/workflows/helm.yml) and
# locally:
#   deploy/helm/check.sh
# Needs helm and kubeconform on PATH (or HELM / KUBECONFORM set to commands that run them).
# kubeconform downloads the Kubernetes JSON schemas, so it needs network access.
set -euo pipefail

cd "$(dirname "$0")"
HELM=${HELM:-helm}
KUBECONFORM=${KUBECONFORM:-kubeconform}
# Oldest Kubernetes version the chart supports (Chart.yaml kubeVersion is >=1.30).
K8S_VERSION=${K8S_VERSION:-1.30.0}
chart=helpdesk

for env in staging prod; do
  values="$chart/values-$env.yaml"
  echo "== $env: helm lint"
  $HELM lint --strict "$chart" -f "$values"
  echo "== $env: kubeconform"
  $HELM template helpdesk "$chart" -f "$values" --kube-version "$K8S_VERSION" \
    | $KUBECONFORM -strict -summary -kubernetes-version "$K8S_VERSION"
done

# Each case must fail to render, with the expected message.
expect_failure() {
  local name=$1 pattern=$2
  shift 2
  local out
  if out=$($HELM template helpdesk "$chart" "$@" 2>&1); then
    echo "FAIL: $name rendered, but should have been refused"
    exit 1
  fi
  if ! grep -q -- "$pattern" <<<"$out"; then
    echo "FAIL: $name failed without the expected message ($pattern):"
    echo "$out"
    exit 1
  fi
  echo "ok: $name is refused"
}

echo "== guards"
expect_failure "defaults without an environment file" "is required"
expect_failure "Ollama capacity exceeded" "Ollama capacity exceeded" \
  -f "$chart/values-prod.yaml" --set assistant.hpa.maxReplicas=10
expect_failure "Rust ticket server" "must be java or python" \
  -f "$chart/values-prod.yaml" --set ticketServer.implementation=rust
expect_failure "python with several replicas" "must be 1 for the python" \
  -f "$chart/values-prod.yaml" --set ticketServer.implementation=python

echo "== python variant"
$HELM template helpdesk "$chart" -f "$chart/values-staging.yaml" --kube-version "$K8S_VERSION" \
  --set ticketServer.implementation=python \
  | $KUBECONFORM -strict -summary -kubernetes-version "$K8S_VERSION"
