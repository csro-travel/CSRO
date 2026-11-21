"""Real frozen LightGBM inference and request/model boundary checks, no HTTP."""
from copy import deepcopy
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import shutil
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import lightgbm as lgb

from travel_ml.__main__ import predict as cli_predict
from travel_ml.risk_runtime import (ModelUnavailableError, RiskModelRuntime,
                                   RiskRequestError, validate_request)

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT/'artifacts/reference_model_v02'
REQUEST_PATH = ROOT/'examples/risk-request.json'
RESPONSE_KEYS = {'hotelId', 'targetCheckInDate', 'asOf', 'riskScore', 'modelVersion', 'featureSetVersion'}


def hashes(folder):
    return {p.relative_to(folder).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in folder.rglob('*') if p.is_file()}


def reseal(folder):
    manifest = json.loads((folder/'manifest.json').read_text())
    manifest['files'] = {name: digest for name, digest in hashes(folder).items() if name != 'manifest.json'}
    (folder/'manifest.json').write_text(json.dumps(manifest, sort_keys=True, indent=2)+'\n')


class RiskRuntimeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.payload = json.loads(REQUEST_PATH.read_text())
        cls.baseline_hashes = hashes(MODEL)
        cls.expected = cli_predict(MODEL, REQUEST_PATH)
        cls.runtime = RiskModelRuntime(MODEL)
        cls.runtime.load()

    def test_real_reference_score_matches_existing_cli_and_does_not_write_artifacts(self):
        self.assertTrue(self.runtime.ready)
        result = self.runtime.predict(deepcopy(self.payload))
        self.assertEqual(set(result), RESPONSE_KEYS)
        self.assertEqual(result, self.expected)
        self.assertAlmostEqual(result['riskScore'], .38033996022231914, places=14)
        self.assertEqual(result['modelVersion'], self.runtime.model_version)
        self.assertEqual(result['featureSetVersion'], self.runtime.feature_set_version)
        self.assertEqual(hashes(MODEL), self.baseline_hashes)

    def test_load_is_idempotent_and_repeated_requests_reuse_one_booster(self):
        original = lgb.Booster
        with patch('lightgbm.Booster', wraps=original) as loader:
            runtime = RiskModelRuntime(MODEL)
            runtime.load()
            runtime.load()
            self.assertTrue(runtime.ready)
            self.assertEqual([runtime.predict(self.payload) for _ in range(5)], [self.expected]*5)
            self.assertEqual(loader.call_count, 1)

    def test_concurrent_predictions_are_stable_and_leave_request_unchanged(self):
        payload = deepcopy(self.payload)
        with ThreadPoolExecutor(max_workers=4) as executor:
            results = list(executor.map(lambda _: self.runtime.predict(payload), range(8)))
        self.assertEqual(results, [self.expected]*8)
        self.assertEqual(payload, self.payload)

    def test_utc_equivalent_d_minus_one_and_schema_integer_float_are_accepted(self):
        payload = deepcopy(self.payload)
        payload['asOf'] = '2025-10-31T00:00:00Z'
        payload['features']['targetConfirmedBookingCount'] = 12.0
        validated = validate_request(payload)
        self.assertEqual(validated['features']['targetConfirmedBookingCount'], 12.0)
        result = self.runtime.predict(payload)
        self.assertEqual(result['asOf'], payload['asOf'])
        self.assertEqual(result['riskScore'], self.expected['riskScore'])

    def test_malformed_envelope_types_and_nonfinite_numbers_are_400(self):
        cases = [None, [], 'request', 1]
        for key, value in [('hotelId', 1), ('hotelId', ' '), ('features', []),
                           ('targetCheckInDate', '2025-02-30'), ('targetCheckInDate', '20251101'),
                           ('asOf', '2025-10-31T09:00:00'), ('asOf', 'not-a-time')]:
            payload = deepcopy(self.payload)
            payload[key] = value
            cases.append(payload)
        extra = deepcopy(self.payload); extra['riskScore'] = .9; cases.append(extra)
        missing = deepcopy(self.payload); missing.pop('hotelId'); cases.append(missing)
        for value in (float('nan'), float('inf'), -float('inf')):
            payload = deepcopy(self.payload); payload['features']['pastFailureRate90d'] = value; cases.append(payload)
        for key, value in [('targetConfirmedBookingCount', 1.5), ('targetConfirmedBookingCount', True),
                           ('reconfirmationOverdueRate7d', '0.2'), ('isPeakDay', 1)]:
            payload = deepcopy(self.payload); payload['features'][key] = value; cases.append(payload)
        for case in cases:
            with self.subTest(case=repr(case)):
                with self.assertRaises(RiskRequestError) as caught:
                    validate_request(case)
                self.assertEqual(caught.exception.status_code, 400)
                self.assertEqual(caught.exception.code, 'INVALID_RISK_REQUEST')

    def test_six_feature_schema_version_bounds_and_wrong_clock_are_422(self):
        cases = []
        unsupported = deepcopy(self.payload); unsupported['featureSetVersion'] = 'risk-features-0.1'; cases.append(unsupported)
        missing = deepcopy(self.payload); missing['features'].pop('isPeakDay'); cases.append(missing)
        extra = deepcopy(self.payload); extra['features']['private_truth'] = 1; cases.append(extra)
        wrong_clock = deepcopy(self.payload); wrong_clock['asOf'] = '2025-10-31T10:00:00+09:00'; cases.append(wrong_clock)
        for key, value in [('pastFailureRate90d', -.1), ('pastFailureRate90d', 1.1),
                           ('targetConfirmedBookingCount', -1), ('bookingVolumeGrowth7d', 11),
                           ('inventoryUnavailableRate7d', 2)]:
            payload = deepcopy(self.payload); payload['features'][key] = value; cases.append(payload)
        for case in cases:
            with self.subTest(case=case):
                with self.assertRaises(RiskRequestError) as caught:
                    validate_request(case)
                self.assertEqual(caught.exception.status_code, 422)

    def test_missing_corrupt_and_feature_incompatible_models_fail_closed(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            folders = [root/'missing']
            for kind in ('corrupt', 'metadata-features', 'internal-features'):
                folder = root/kind
                shutil.copytree(MODEL, folder)
                if kind == 'corrupt':
                    (folder/'model.txt').write_text('not the frozen model')
                else:
                    metadata = json.loads((folder/'model_metadata.json').read_text())
                    if kind == 'metadata-features':
                        metadata['featureNames'] = list(reversed(metadata['featureNames']))
                    else:
                        # LightGBM's tree_sizes are byte offsets.  A text-mode
                        # write on Windows changes LF to CRLF and invalidates
                        # those offsets before the native parser sees the file.
                        model_bytes = (folder/'model.txt').read_bytes()
                        original = ('feature_names='+' '.join(metadata['featureNames'])).encode('utf-8')
                        replacement = ('feature_names='+' '.join(reversed(metadata['featureNames']))).encode('utf-8')
                        self.assertEqual(model_bytes.count(original), 1)
                        modified = model_bytes.replace(original, replacement, 1)
                        header_start = model_bytes.index(original)
                        self.assertEqual(len(modified), len(model_bytes))
                        self.assertEqual(modified[:header_start], model_bytes[:header_start])
                        self.assertEqual(modified[header_start+len(replacement):],
                                         model_bytes[header_start+len(original):])
                        self.assertEqual(modified.count(b'\n'), model_bytes.count(b'\n'))
                        self.assertEqual(modified.count(b'\r\n'), model_bytes.count(b'\r\n'))
                        (folder/'model.txt').write_bytes(modified)
                        # Catch accidental reintroduction of text-mode writes
                        # before a malformed byte-offset model reaches C++.
                        self.assertEqual((folder/'model.txt').read_bytes(), modified)
                        metadata['modelSha256'] = hashlib.sha256((folder/'model.txt').read_bytes()).hexdigest()
                        metadata['modelVersion'] = 'process-synthetic-lightgbm-0.2-'+metadata['modelSha256'][:12]
                        manifest = json.loads((folder/'manifest.json').read_text())
                        manifest['modelVersion'] = metadata['modelVersion']
                        (folder/'manifest.json').write_text(json.dumps(manifest))
                    (folder/'model_metadata.json').write_text(json.dumps(metadata))
                    reseal(folder)
                folders.append(folder)
            for folder in folders:
                with self.subTest(folder=folder.name):
                    runtime = RiskModelRuntime(folder)
                    runtime.load()  # Startup must remain usable for health diagnostics.
                    self.assertFalse(runtime.ready)
                    with self.assertRaises(ModelUnavailableError):
                        runtime.predict(self.payload)
                    self.assertNotIn(str(folder), str(runtime.load_error))
        self.assertEqual(hashes(MODEL), self.baseline_hashes)


if __name__ == '__main__':
    unittest.main()
