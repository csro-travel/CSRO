"""API-023 HTTP contract with the real frozen model and fail-closed boundaries.

HTTP dependencies are optional for the baseline ML workflow.  Only this class
is skipped when FastAPI/httpx are absent; runtime tests still exercise inference.
"""
from copy import deepcopy
import hashlib
from importlib.util import find_spec
import json
import os
from pathlib import Path
import shutil
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import lightgbm as lgb

from travel_ml.risk_runtime import RiskModelRuntime

HTTP_AVAILABLE = find_spec('fastapi') is not None and find_spec('httpx') is not None
if HTTP_AVAILABLE:
    from fastapi.testclient import TestClient
    from travel_ml.risk_api import create_app

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT/'artifacts/reference_model_v02'
PATH = '/internal/v1/risk-assessments'
TOKEN = 'unit-test-service-token'
AUTH = {'Authorization': 'Bearer '+TOKEN}
PROBLEM_KEYS = {'type', 'title', 'status', 'detail', 'code', 'traceId'}
RESPONSE_KEYS = {'hotelId', 'targetCheckInDate', 'asOf', 'riskScore', 'modelVersion', 'featureSetVersion'}


def artifact_hashes():
    return {p.relative_to(MODEL).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in MODEL.rglob('*') if p.is_file()}


@unittest.skipUnless(HTTP_AVAILABLE, 'Optional HTTP tests require requirements-api.txt (FastAPI and httpx)')
class RiskApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.payload = json.loads((ROOT/'examples/risk-request.json').read_text())
        cls.runtime = RiskModelRuntime(MODEL)
        cls.runtime.load()
        cls.expected = cls.runtime.predict(cls.payload)
        cls.original_hashes = artifact_hashes()

    def client(self, **kwargs):
        return TestClient(create_app(runtime=self.runtime, service_token=TOKEN, **kwargs))

    def assert_problem(self, response, status, code=None):
        self.assertEqual(response.status_code, status)
        self.assertEqual(response.headers['content-type'].split(';')[0], 'application/problem+json')
        body = response.json()
        self.assertEqual(set(body), PROBLEM_KEYS)
        self.assertEqual(body['status'], status)
        self.assertEqual(body['type'], 'about:blank')
        self.assertIsInstance(body['code'], str)
        self.assertTrue(body['code'])
        self.assertTrue(body['traceId'])
        self.assertEqual(response.headers.get('x-request-id'), body['traceId'])
        if code:
            self.assertEqual(body['code'], code)
        if status == 401:
            self.assertEqual(response.headers.get('www-authenticate'), 'Bearer')
        return body

    def test_real_model_success_echo_exact_six_fields_and_unchanged_bundle(self):
        with self.client() as client:
            response = client.post(PATH, headers=AUTH, json=self.payload)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers['content-type'].split(';')[0], 'application/json')
        self.assertTrue(response.headers.get('x-request-id'))
        self.assertEqual(set(response.json()), RESPONSE_KEYS)
        self.assertEqual(response.json(), self.expected)
        self.assertAlmostEqual(response.json()['riskScore'], .38033996022231914, places=14)
        self.assertEqual(artifact_hashes(), self.original_hashes)

    def test_startup_loads_one_booster_and_reuses_it_across_requests(self):
        original = lgb.Booster
        with patch('lightgbm.Booster', wraps=original) as loader:
            app = create_app(model_dir=MODEL, service_token=TOKEN)
            with TestClient(app) as client:
                for _ in range(4):
                    self.assertEqual(client.post(PATH, headers=AUTH, json=self.payload).json(), self.expected)
            self.assertEqual(loader.call_count, 1)

    def test_missing_wrong_and_non_bearer_tokens_are_401(self):
        headers = [{}, {'Authorization': 'Bearer wrong'}, {'Authorization': 'Basic '+TOKEN}]
        with self.client() as client:
            for header in headers:
                with self.subTest(header=header):
                    self.assert_problem(client.post(PATH, headers=header, json=self.payload), 401)
            # Authentication is checked before parsing invalid JSON.
            self.assert_problem(client.post(PATH, content='{'), 401)

    def test_unconfigured_service_token_fails_closed_while_health_stays_up(self):
        with patch.dict(os.environ, {'ML_SERVICE_TOKEN': ''}):
            app = create_app(model_dir=MODEL, service_token=None, runtime=self.runtime)
            with TestClient(app) as client:
                self.assertEqual(client.get('/healthz').status_code, 200)
                self.assertEqual(client.get('/readyz').status_code, 503)
                self.assert_problem(client.post(PATH, headers=AUTH, json=self.payload), 401)

    def test_malformed_non_object_duplicate_and_nonstandard_json_are_400(self):
        ordinary = json.dumps(self.payload)
        duplicate_top = ordinary.replace('"hotelId": "HOTEL-001"', '"hotelId":"first","hotelId":"HOTEL-001"')
        duplicate_feature = ordinary.replace('"pastFailureRate90d": 0.05', '"pastFailureRate90d":0.1,"pastFailureRate90d":0.05')
        cases = ['{', '', '[]', 'null', '"request"', duplicate_top, duplicate_feature]
        for value in ('NaN', 'Infinity', '-Infinity'):
            cases.append(ordinary.replace('"pastFailureRate90d": 0.05', '"pastFailureRate90d": '+value))
        with self.client() as client:
            for content in cases:
                with self.subTest(content=content):
                    response = client.post(PATH, headers={**AUTH, 'Content-Type': 'application/json'}, content=content)
                    self.assert_problem(response, 400, 'INVALID_RISK_REQUEST')
            self.assert_problem(client.post(PATH, headers={**AUTH, 'Content-Type': 'text/plain'}, content=ordinary),
                                400, 'INVALID_RISK_REQUEST')
            self.assert_problem(client.post(PATH, headers={**AUTH, 'Content-Type': 'application/json'}, content=b'\xff'),
                                400, 'INVALID_RISK_REQUEST')

    def test_bad_envelope_field_types_dates_and_extra_fields_are_400(self):
        cases = []
        for field, value in [('hotelId', 123), ('features', []), ('targetCheckInDate', '2025-13-01'),
                             ('asOf', '2025-10-31T09:00:00')]:
            payload = deepcopy(self.payload); payload[field] = value; cases.append(payload)
        extra = deepcopy(self.payload); extra['riskLevel'] = 'HIGH'; cases.append(extra)
        cases.append({'request': self.payload})
        with self.client() as client:
            for payload in cases:
                with self.subTest(payload=payload):
                    self.assert_problem(client.post(PATH, headers=AUTH, json=payload), 400, 'INVALID_RISK_REQUEST')

    def test_feature_contract_and_clock_business_errors_are_422(self):
        cases = []
        version = deepcopy(self.payload); version['featureSetVersion'] = 'risk-features-0.1'; cases.append(version)
        missing = deepcopy(self.payload); missing['features'].pop('pastFailureRate90d'); cases.append(missing)
        extra = deepcopy(self.payload); extra['features']['hidden_truth'] = 1; cases.append(extra)
        bound = deepcopy(self.payload); bound['features']['pastFailureRate90d'] = 1.1; cases.append(bound)
        clock = deepcopy(self.payload); clock['asOf'] = '2025-10-31T09:00:01+09:00'; cases.append(clock)
        with self.client() as client:
            for payload in cases:
                with self.subTest(payload=payload):
                    self.assert_problem(client.post(PATH, headers=AUTH, json=payload), 422)

    def test_utc_equivalent_clock_and_integer_valued_float_follow_existing_schema(self):
        payload = deepcopy(self.payload)
        payload['asOf'] = '2025-10-31T00:00:00Z'
        payload['features']['targetConfirmedBookingCount'] = 12.0
        with self.client() as client:
            response = client.post(PATH, headers=AUTH, json=payload)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()['asOf'], payload['asOf'])
        self.assertEqual(response.json()['riskScore'], self.expected['riskScore'])

    def test_missing_or_corrupt_model_returns_503_without_killing_app(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            corrupt = root/'corrupt'; shutil.copytree(MODEL, corrupt)
            (corrupt/'model.txt').write_text('invalid model bytes')
            for model in (root/'missing', corrupt):
                with self.subTest(model=model.name):
                    app = create_app(model_dir=model, service_token=TOKEN)
                    with TestClient(app) as client:
                        self.assertEqual(client.get('/healthz').status_code, 200)
                        self.assertEqual(client.get('/readyz').status_code, 503)
                        body = self.assert_problem(client.post(PATH, headers=AUTH, json=self.payload), 503, 'MODEL_UNAVAILABLE')
                        self.assertNotIn(str(root), json.dumps(body))

    def test_health_and_ready_show_availability_without_authentication(self):
        with self.client() as client:
            health, ready = client.get('/healthz'), client.get('/readyz')
        self.assertEqual(health.status_code, 200)
        self.assertEqual(health.json()['status'], 'alive')
        self.assertTrue(health.json()['modelReady'])
        self.assertEqual(ready.status_code, 200)
        self.assertEqual(ready.json()['status'], 'ready')
        self.assertTrue(ready.json()['modelReady'])
        self.assertTrue(ready.json()['authConfigured'])

    def test_unexpected_error_is_a_sanitized_500_problem(self):
        secret = '/private/customer/model-secret.txt token='+TOKEN

        class BrokenRuntime:
            ready = True
            model_version = 'unit-broken-runtime'
            feature_set_version = 'risk-features-0.2'
            load_error = None
            def load(self):
                pass
            def predict(self, payload):
                raise RuntimeError(secret)

        app = create_app(service_token=TOKEN, runtime=BrokenRuntime())
        with self.assertLogs('travel_ml.risk_api', level='ERROR') as captured:
            with TestClient(app) as client:
                response = client.post(PATH, headers=AUTH, json=self.payload)
        body = self.assert_problem(response, 500, 'INTERNAL_ERROR')
        rendered = json.dumps(body)
        self.assertNotIn(secret, rendered)
        self.assertNotIn(TOKEN, rendered)
        self.assertNotIn('RuntimeError', rendered)
        logged = '\n'.join(captured.output)
        self.assertIn(body['traceId'], logged)
        self.assertIn('exceptionType=RuntimeError', logged)
        self.assertNotIn(secret, logged)
        self.assertNotIn(TOKEN, logged)
        self.assertTrue(all(record.exc_info is None for record in captured.records))


if __name__ == '__main__':
    unittest.main()
