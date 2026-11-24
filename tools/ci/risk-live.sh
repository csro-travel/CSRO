#!/usr/bin/env bash
# Run only inside the isolated GitHub Actions matrix job.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]]
case "$1" in RiskAssessmentLiveTest|RiskCaseDemoLiveTest|RiskJobsIntegrationTest) ;; *) exit 2 ;; esac
if [[ "$1" == RiskJobsIntegrationTest ]]; then
  export RISK_JOBS_TEST=true RISK_JOBS_TEST_USER=yb RISK_JOBS_TEST_PASSWORD=ci-isolated-db-only
  export RISK_JOBS_TEST_URL=jdbc:postgresql://127.0.0.1:25435/yeohaengbom_risk_stage2_test
else
export PGHOST=127.0.0.1 PGPORT=25439 PGDATABASE=csro_risk_fixture_test PGUSER=yb
export PGPASSWORD=ci-isolated-db-only CSRO_RISK_TEST_DB_PASSWORD=ci-isolated-db-only
db=(psql -X --no-password --set=ON_ERROR_STOP=1)
[[ "$("${db[@]}" -Atc "select current_database() || ':' || current_user")" == csro_risk_fixture_test:yb ]]
"${db[@]}" -f backend/src/main/resources/db/migration/V1__initial_business_schema.sql
"${db[@]}" -f backend/src/main/resources/db/local/R__seed_yb001.sql

export ML_SERVICE_TOKEN=ci-local-ml-token ML_RISK_SERVICE_TOKEN=ci-local-ml-token
export ML_RISK_BASE_URL=http://127.0.0.1:18023
wait_for() { curl --fail --silent --show-error --retry 30 --retry-all-errors --retry-delay 1 "$1" >/dev/null; }
pids=()
trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT
(cd ml && exec python -m uvicorn travel_ml.risk_api:app --host 127.0.0.1 --port 18023) &
pids+=($!)
wait_for "$ML_RISK_BASE_URL/readyz"
if [[ "$1" == RiskCaseDemoLiveTest ]]; then
  export CSRO_RISK_DEMO_E2E=true
  export MOCK_SERVICE_TOKEN=ci-local-mock-token BOOKING_RECONFIRMATION_MOCK_SERVICE_TOKEN=ci-local-mock-token
  export CSRO_DEMO_TOKEN=ci-local-demo-token CSRO_DEMO_SUBJECT=demo-counselor-001
  (cd mock-server && exec python -m uvicorn app.main:app --host 127.0.0.1 --port 18027) &
  pids+=($!)
  wait_for http://127.0.0.1:18027/healthz
  (cd backend && bash gradlew bootJar)
  jars=(backend/build/libs/*.jar)
  SPRING_PROFILES_ACTIVE=local-demo SERVER_PORT=18038 SPRING_FLYWAY_ENABLED=false \
    SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:25439/csro_risk_fixture_test \
    SPRING_DATASOURCE_USERNAME=yb SPRING_DATASOURCE_PASSWORD="$PGPASSWORD" java -jar "${jars[0]}" &
  pids+=($!)
  wait_for http://127.0.0.1:18038/hello
else
  export CSRO_RISK_LIVE_TEST=true
fi
fi
(cd backend && bash gradlew test --tests "com.csro.backend.risk.$1")
python - "$1" <<'PY'
import sys
import xml.etree.ElementTree as ET
suite = ET.parse(f"backend/build/test-results/test/TEST-com.csro.backend.risk.{sys.argv[1]}.xml").getroot()
if int(suite.attrib["tests"]) == 0 or any(int(suite.attrib[k]) for k in ("skipped", "errors", "failures")):
    sys.exit("Risk live test missing, skipped or failed")
PY
