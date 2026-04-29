#!/usr/bin/env sh
set -eu

BASE_URL="${TRUSTGRID_BASE_URL:-http://localhost:8080}"

echo "TrustGrid demo smoke"
echo "Base URL: ${BASE_URL}"

curl -fsS "${BASE_URL}/actuator/health"
echo
curl -fsS "${BASE_URL}/actuator/health/readiness"
echo
curl -fsS "${BASE_URL}/api/v1/system/ping"
echo
curl -fsS "${BASE_URL}/api/v1/system/node"
echo
curl -fsS "${BASE_URL}/api/v1/system/dependencies"
echo

echo "Optional demo surfaces:"
echo "${BASE_URL}/api/v1/trust-incidents"
echo "${BASE_URL}/api/v1/trust-alerts"
echo "${BASE_URL}/api/v1/ops/dashboard/trust-control-room"
echo "${BASE_URL}/api/v1/consistency/findings"
echo "${BASE_URL}/api/v1/data-repair/recommendations"
echo "${BASE_URL}/api/v1/capability-governance/policies"
echo "${BASE_URL}/api/v1/capability-governance/timeline"
