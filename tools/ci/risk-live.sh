#!/usr/bin/env bash
# Run only inside the isolated GitHub Actions matrix job.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]]
test_class="$1"
export PGHOST=127.0.0.1 PGUSER=yb PGPASSWORD=ci-isolated-db-only
case "$test_class" in
  RiskJobsIntegrationTest)
    export PGPORT=25435 PGDATABASE=yeohaengbom_risk_stage2_test
    export RISK_JOBS_TEST=true RISK_JOBS_TEST_USER=yb RISK_JOBS_TEST_PASSWORD="$PGPASSWORD"
    export RISK_JOBS_TEST_URL=jdbc:postgresql://127.0.0.1:25435/yeohaengbom_risk_stage2_test
    ;;
  RiskBatchAssessmentIntegrationTest|RiskBatchServiceIntegrationTest)
    export PGPORT=25436 PGDATABASE=yeohaengbom_risk_batch_test
    export RISK_BATCH_TEST=true RISK_BATCH_TEST_DB_USER=yb RISK_BATCH_TEST_DB_PASSWORD="$PGPASSWORD"
    export RISK_BATCH_TEST_DB_URL=jdbc:postgresql://127.0.0.1:25436/yeohaengbom_risk_batch_test
    ;;
  RiskRecheckIntegrationTest)
    export PGPORT=25434 PGDATABASE=yeohaengbom_risk_link_test
    export RISK_LINK_TEST=true RISK_LINK_TEST_DB_PASSWORD="$PGPASSWORD"
    export RISK_LINK_TEST_DB_URL=jdbc:postgresql://127.0.0.1:25434/yeohaengbom_risk_link_test
    export RISK_LINK_TEST_ML_URL=http://127.0.0.1:18023
    export RISK_LINK_TEST_MOCK_URL=http://127.0.0.1:18027
    ;;
  RiskAssessmentLiveTest|RiskCaseDemoLiveTest)
    export PGPORT=25439 PGDATABASE=csro_risk_fixture_test
    export CSRO_RISK_TEST_DB_PASSWORD="$PGPASSWORD"
    ;;
  *) exit 2 ;;
esac

# Verify the dedicated endpoint before SQL setup, Spring or Flyway.
db=(psql -X --no-password --set=ON_ERROR_STOP=1)
[[ "$("${db[@]}" -Atc "select current_database() || ':' || current_user")" == "$PGDATABASE:$PGUSER" ]]
if [[ "$test_class" == RiskAssessmentLiveTest || "$test_class" == RiskCaseDemoLiveTest ]]; then
  # Spring uses Flyway-disabled profiles in these live tests.
  while IFS= read -r migration; do
    "${db[@]}" -f "$migration"
  done < <(printf "%s\n" backend/src/main/resources/db/migration/V[0-9]*__*.sql | LC_ALL=C sort -V)
  "${db[@]}" -f backend/src/main/resources/db/local/R__seed_yb001.sql
fi

if [[ "$test_class" == RiskAssessmentLiveTest || "$test_class" == RiskCaseDemoLiveTest || "$test_class" == RiskRecheckIntegrationTest ]]; then
  export ML_SERVICE_TOKEN=ci-local-ml-token ML_RISK_SERVICE_TOKEN=ci-local-ml-token
  export ML_RISK_BASE_URL=http://127.0.0.1:18023
  wait_for() { curl --fail --silent --show-error --retry 30 --retry-all-errors --retry-delay 1 "$1" >/dev/null; }
  pids=()
  trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT
  (cd ml && exec python -m uvicorn travel_ml.risk_api:app --host 127.0.0.1 --port 18023) &
  pids+=($!)
  wait_for "$ML_RISK_BASE_URL/readyz"
  if [[ "$test_class" == RiskCaseDemoLiveTest || "$test_class" == RiskRecheckIntegrationTest ]]; then
    export MOCK_SERVICE_TOKEN=ci-local-mock-token BOOKING_RECONFIRMATION_MOCK_SERVICE_TOKEN=ci-local-mock-token
    (cd mock-server && exec python -m uvicorn app.main:app --host 127.0.0.1 --port 18027) &
    pids+=($!)
    wait_for http://127.0.0.1:18027/healthz
  fi
  if [[ "$test_class" == RiskCaseDemoLiveTest ]]; then
    export CSRO_RISK_DEMO_E2E=true
    export CSRO_DEMO_TOKEN=ci-local-demo-token CSRO_DEMO_SUBJECT=demo-counselor-001
    (cd backend && bash gradlew bootJar)
    jars=(backend/build/libs/*.jar)
    SPRING_PROFILES_ACTIVE=local-demo SERVER_PORT=18038 SPRING_FLYWAY_ENABLED=false \
      SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:25439/csro_risk_fixture_test \
      SPRING_DATASOURCE_USERNAME=yb SPRING_DATASOURCE_PASSWORD="$PGPASSWORD" java -jar "${jars[0]}" &
    pids+=($!)
    wait_for http://127.0.0.1:18038/hello
  elif [[ "$test_class" == RiskAssessmentLiveTest ]]; then
    export CSRO_RISK_LIVE_TEST=true
  fi
fi
(cd backend && bash gradlew test --tests "com.csro.backend.risk.$test_class" --rerun)
python - "$test_class" <<'PY'
import sys
import xml.etree.ElementTree as ET
suite = ET.parse(f"backend/build/test-results/test/TEST-com.csro.backend.risk.{sys.argv[1]}.xml").getroot()
if int(suite.attrib["tests"]) == 0 or any(int(suite.attrib[k]) for k in ("skipped", "errors", "failures")):
    sys.exit("Risk integration test missing, skipped or failed")
PY
