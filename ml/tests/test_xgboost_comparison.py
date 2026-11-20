"""Comparison provenance and test-label isolation, using small temporary fixtures.

These fixtures are deliberately not the user's experiment.  The production
provenance guard is exercised directly; only the test-label isolation test
mocks that guard after it has validated an unchanged fixture.
"""
from __future__ import annotations

from copy import deepcopy
import csv
from datetime import date, datetime, time, timedelta
import hashlib
from importlib.metadata import PackageNotFoundError, version as package_version
from importlib.util import find_spec
import json
import math
from pathlib import Path
import shutil
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
from zoneinfo import ZoneInfo

from travel_ml.common import FEATURE_NAMES
from travel_ml.training import MATURITY, SCHEDULE, train_models
from travel_ml import xgboost_compare as comparison
from travel_ml.xgboost_compare import compare_models, validate_comparison_inputs

ROOT = Path(__file__).resolve().parents[1]
ZONE = ZoneInfo('Asia/Seoul')
INPUT_CSVS = ('dataset.csv', 'train.csv', 'validation.csv', 'test.csv', 'split_audit.csv')


def optional_xgboost_available() -> bool:
    if find_spec('xgboost') is None:
        return False
    try:
        import xgboost
        return xgboost.__version__ == '3.4.1' and package_version('xgboost-cpu') == '3.4.1'
    except (ImportError, OSError, PackageNotFoundError):
        return False


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_sha(value: dict) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                    separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2,
                               allow_nan=False)+'\n', encoding='utf-8')


def seal_reference(folder: Path) -> None:
    manifest = json.loads((folder/'manifest.json').read_text())
    manifest['files'] = {p.relative_to(folder).as_posix(): sha(p)
                         for p in sorted(folder.rglob('*'))
                         if p.is_file() and p.name != 'manifest.json'}
    write_json(folder/'manifest.json', manifest)


def fixture(root: Path) -> tuple[Path, Path, dict]:
    data, reference = root/'data', root/'reference'
    data.mkdir()
    config = json.loads((ROOT/'config/simulator_v0.2.json').read_text())
    # Short baseline training is confined to this explicitly marked unit fixture.
    grid = config['evaluation']['lightgbm']
    grid.update(n_estimators=12, early_stopping_rounds=3,
                num_leaves_candidates=[7], min_child_samples_candidates=[5])
    targets = [date(2025, m, 1) for m in range(4, 10)]
    targets += [date(2025, 9, 29), date(2025, 9, 30)]
    targets += [date(2025, 10, d) for d in (1, 15, 29, 31)]
    targets += [date(2025, 11, 1), date(2025, 11, 15), date(2025, 12, 1), date(2025, 12, 31)]
    rows = []
    for target in targets:
        for hotel in range(1, 19):
            status = 'NOT_ELIGIBLE' if hotel == 18 else 'UNKNOWN' if hotel == 17 else 'KNOWN'
            label = hotel % 2
            rows.append({
                'hotelId': f'UNIT-HOTEL-{hotel:03d}',
                'targetCheckInDate': target.isoformat(),
                'asOf': datetime.combine(target-timedelta(days=1), time(9), ZONE).isoformat(),
                'featureSetVersion': 'risk-features-0.2',
                'pastFailureRate90d': .18 if label else .01,
                'targetConfirmedBookingCount': 0 if status == 'NOT_ELIGIBLE' else 2,
                'bookingVolumeGrowth7d': (hotel % 3)/10,
                'reconfirmationOverdueRate7d': (hotel % 4)/10,
                'inventoryUnavailableRate7d': (hotel % 2)/10,
                'isPeakDay': target.weekday() in (4, 5),
                'target': label if status == 'KNOWN' else '',
                'labelStatus': status,
                'labelAvailableAt': datetime.combine(target+timedelta(days=3), time(18), ZONE).isoformat()
                    if status == 'KNOWN' else '',
                # Canary columns would disclose the target if an automatic
                # drop-target/select-all-numeric learner were accidentally used.
                'private_truth': label,
                'future_failure_count': 100*label,
            })

    def write_rows(path, selected):
        with path.open('w', encoding='utf-8', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(selected)

    write_rows(data/'dataset.csv', rows)
    audit = []
    for name, (first, last) in SCHEDULE.items():
        selected = []
        for row in rows:
            if not first <= row['targetCheckInDate'] <= last:
                continue
            reason = row['labelStatus'] if row['labelStatus'] != 'KNOWN' else 'INCLUDED'
            if reason == 'INCLUDED' and name in MATURITY \
                    and datetime.fromisoformat(row['labelAvailableAt']) >= MATURITY[name]:
                reason = 'PURGED_LABEL_NOT_AVAILABLE'
            if reason == 'INCLUDED':
                selected.append(row)
            audit.append({'hotelId': row['hotelId'], 'targetCheckInDate': row['targetCheckInDate'],
                          'asOf': row['asOf'], 'assignedSplit': name, 'status': reason,
                          'labelCutoff': config['evaluation'][f'{name}_label_available_before'] if name != 'test'
                              else config['evaluation']['test_label_available_by']})
        write_rows(data/f'{name}.csv', selected)
    with (data/'split_audit.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(audit[0]))
        writer.writeheader()
        writer.writerows(audit)
    write_json(data/'effective_config.json', config)
    manifest = {'isSynthetic': True, 'isPilot': False, 'scope': 'UNIT_TEST_FIXTURE_ONLY',
                'configSha256': canonical_sha(config),
                'files': {name: sha(data/name) for name in (*INPUT_CSVS, 'effective_config.json')}}
    write_json(data/'full_run_manifest.json', manifest)
    train_models(data, reference, config)
    metadata = json.loads((reference/'model_metadata.json').read_text())
    metadata['datasetManifestSha256'] = sha(data/'full_run_manifest.json')
    metadata['inputDatasetManifestSha256'] = sha(data/'full_run_manifest.json')
    metadata['trainingSourceHashes'] = {name: sha(ROOT/'travel_ml'/name)
                                      for name in ('training.py', '__main__.py', 'reporting.py')}
    write_json(reference/'model_metadata.json', metadata)
    seal_reference(reference)
    protocol = json.loads((ROOT/'config/xgboost_comparison_v0.2.json').read_text())
    protocol['experiment_version'] = 'unit-test-xgboost-comparison'
    protocol['scope'] = 'UNIT_TEST_FIXTURE_ONLY'
    protocol['xgboost']['num_boost_round'] = 8
    protocol['xgboost']['early_stopping_rounds'] = 3
    protocol['expected_reference'] = {
        'model_sha256': metadata['modelSha256'], 'model_version': metadata['modelVersion'],
        'data_config_sha256': metadata['configSha256'],
        'full_dataset_manifest_sha256': sha(data/'full_run_manifest.json'),
        'reference_model_manifest_sha256': sha(reference/'manifest.json'),
    }
    return data, reference, protocol


@unittest.skipUnless(optional_xgboost_available(),
                     'Optional comparison tests require xgboost-cpu==3.4.1 from requirements-xgboost.txt')
class XGBoostComparisonTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        cls.data, cls.reference, cls.protocol = fixture(cls.root)
        cls.original_input_hashes = {p.name: sha(p) for p in cls.data.iterdir() if p.is_file()}
        cls.original_reference_hashes = {p.name: sha(p) for p in cls.reference.iterdir() if p.is_file()}
        cls.output = cls.root/'comparison'
        cls.report = compare_models(cls.data, cls.reference, cls.output, cls.protocol)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def isolated_copy(self, name):
        folder = self.root/name
        folder.mkdir()
        data, reference = folder/'data', folder/'reference'
        shutil.copytree(self.data, data)
        shutil.copytree(self.reference, reference)
        return data, reference

    def test_reference_guard_accepts_exact_data_and_same_fixed_splits(self):
        context = validate_comparison_inputs(self.data, self.reference, self.protocol)
        self.assertIsInstance(context, dict)

    def test_every_reference_csv_hash_is_bound_even_when_rows_still_parse(self):
        for name in INPUT_CSVS:
            with self.subTest(csv=name):
                data, reference = self.isolated_copy('csv-'+name.replace('.', '-'))
                with (data/name).open('ab') as stream:
                    stream.write(b'\n')
                with self.assertRaises(ValueError):
                    validate_comparison_inputs(data, reference, self.protocol)

    def test_changed_config_or_manifest_cannot_reuse_the_reference_result(self):
        for name in ('effective_config.json', 'full_run_manifest.json'):
            with self.subTest(file=name):
                data, reference = self.isolated_copy('changed-'+name.replace('.', '-'))
                value = json.loads((data/name).read_text())
                value['unit_tamper_marker'] = True
                write_json(data/name, value)
                with self.assertRaises(ValueError):
                    validate_comparison_inputs(data, reference, self.protocol)

    def test_baseline_model_corruption_and_self_resealed_replacement_are_rejected(self):
        data, reference = self.isolated_copy('corrupt-model')
        with (reference/'model.txt').open('ab') as stream:
            stream.write(b'\n# altered baseline bytes\n')
        with self.assertRaises(ValueError):
            validate_comparison_inputs(data, reference, self.protocol)
        # Resealing a locally changed artifact must not replace the separately
        # frozen baseline identity in the comparison protocol.
        metadata = json.loads((reference/'model_metadata.json').read_text())
        metadata['modelSha256'] = sha(reference/'model.txt')
        write_json(reference/'model_metadata.json', metadata)
        seal_reference(reference)
        with self.assertRaises(ValueError):
            validate_comparison_inputs(data, reference, self.protocol)

    def test_existing_output_is_preserved_without_training(self):
        output = self.root/'do-not-overwrite'
        output.mkdir()
        sentinel = output/'keep.txt'
        sentinel.write_text('existing work', encoding='utf-8')
        with patch.object(comparison, 'freeze_validation_selection', side_effect=AssertionError('training must not begin')):
            with self.assertRaises(FileExistsError):
                compare_models(self.data, self.reference, output, self.protocol)
        self.assertEqual(sentinel.read_text(), 'existing work')

    def test_six_feature_whitelist_and_reference_artifacts_are_preserved(self):
        import xgboost as xgb
        booster = xgb.Booster()
        booster.load_model(self.output/'xgboost_model.json')
        self.assertEqual(booster.feature_names, list(FEATURE_NAMES))
        self.assertNotIn('private_truth', booster.feature_names)
        self.assertNotIn('future_failure_count', booster.feature_names)
        metadata = json.loads((self.output/'model_metadata.json').read_text())
        self.assertEqual(metadata['featureNames'], list(FEATURE_NAMES))
        self.assertFalse(metadata['realWorldPerformanceValidated'])
        self.assertFalse(metadata['independentFinalHoldout'])
        self.assertEqual(metadata['referenceModelSha256'], self.protocol['expected_reference']['model_sha256'])
        self.assertEqual({p.name: sha(p) for p in self.data.iterdir() if p.is_file()}, self.original_input_hashes)
        self.assertEqual({p.name: sha(p) for p in self.reference.iterdir() if p.is_file()}, self.original_reference_hashes)
        with (self.output/'search_results.csv').open(newline='') as stream:
            self.assertEqual(len(list(csv.DictReader(stream))), 6)
        from travel_ml.storage import verify_output
        self.assertEqual(verify_output(self.output)['type'], 'xgboost-comparison')

    def test_changed_test_labels_cannot_change_checkpoint_selection_or_threshold(self):
        """Exercise the holdout stage after immutable input guards have passed.

        Production rejects edited test.csv bytes (tested above).  This mock
        changes only the materialized test outcomes returned AFTER model and
        selection receipts are written, without modifying any source file.
        """
        output = self.root/'test-outcomes-inverted'
        real_validate = comparison.validate_splits
        barrier_checked = []

        def materialize_changed_holdout(data_dir, config):
            pending = list(output.parent.glob(output.name+'-pending-*'))
            self.assertEqual(len(pending), 1)
            self.assertTrue((pending[0]/'xgboost_model.json').is_file())
            frozen = json.loads((pending[0]/'selection_frozen.json').read_text())
            self.assertFalse(frozen['testLabelsMaterialized'])
            barrier_checked.append(True)
            panel, splits, audit = real_validate(data_dir, config)
            panel, splits = deepcopy((panel, splits))
            for row in panel:
                if row['date'] >= date(2025, 11, 1) and row['labelStatus'] == 'KNOWN':
                    row['target'] = 1-row['target']
            for row in splits['test']:
                row['target'] = 1-row['target']
            return panel, splits, audit

        with patch.object(comparison, 'validate_splits', side_effect=materialize_changed_holdout):
            report = compare_models(self.data, self.reference, output, self.protocol)
        self.assertEqual(barrier_checked, [True])
        self.assertEqual((output/'xgboost_model.json').read_bytes(), (self.output/'xgboost_model.json').read_bytes())
        self.assertEqual((output/'selection_frozen.json').read_bytes(), (self.output/'selection_frozen.json').read_bytes())
        self.assertEqual(report['selection'], self.report['selection'])
        self.assertEqual(report['thresholdsFrozenOnValidation'], self.report['thresholdsFrozenOnValidation'])
        self.assertNotEqual(report['test']['xgboost']['knownLabelMetrics']['confusionMatrix'],
                            self.report['test']['xgboost']['knownLabelMetrics']['confusionMatrix'])

    def test_inputs_changed_during_candidate_training_abort_before_output(self):
        data, reference = self.isolated_copy('midrun-input-change')
        output = self.root/'aborted-midrun-comparison'
        real_selection = comparison.freeze_validation_selection

        def train_then_corrupt(*args, **kwargs):
            result = real_selection(*args, **kwargs)
            with (data/'test.csv').open('ab') as stream:
                stream.write(b'\n')
            return result

        with patch.object(comparison, 'freeze_validation_selection', side_effect=train_then_corrupt):
            with self.assertRaises(ValueError):
                compare_models(data, reference, output, self.protocol)
        self.assertFalse(output.exists())

    def test_comparison_source_hash_change_midfit_aborts_without_modifying_source(self):
        output = self.root/'aborted-source-change'
        real_selection = comparison.freeze_validation_selection
        real_sha = comparison.sha256
        comparison_path = Path(comparison.__file__).resolve()
        before = sha(comparison_path)
        changed = False

        def simulated_source_hash(path):
            if changed and Path(path).resolve() == comparison_path:
                return '0'*64
            return real_sha(path)

        def train_then_simulate_source_change(*args, **kwargs):
            nonlocal changed
            result = real_selection(*args, **kwargs)
            changed = True
            return result

        with patch.object(comparison, 'sha256', side_effect=simulated_source_hash):
            with patch.object(comparison, 'freeze_validation_selection', side_effect=train_then_simulate_source_change):
                with self.assertRaisesRegex(ValueError, 'module source changed during comparison'):
                    compare_models(self.data, self.reference, output, self.protocol)
        self.assertFalse(output.exists())
        self.assertEqual(sha(comparison_path), before)

    def test_exact_exposure_boundary_is_independent_of_platform_libm(self):
        # Actual frozen validation boundary.  The helper must evaluate the
        # declared arithmetic without log1p/expm1 platform rounding.
        p, threshold = .040229885057471264, .2498112275967828
        rows = [{'values': [p, 7]}]
        with patch.object(comparison.math, 'log1p', side_effect=AssertionError('libm log1p must not be used')):
            with patch.object(comparison.math, 'expm1', side_effect=AssertionError('libm expm1 must not be used')):
                score = float(comparison.exact_exposure_scores(rows)[0])
        self.assertEqual(score, .24981122759678281)
        self.assertEqual(score.hex(), '0x1.ff9d07659d46ap-3')
        self.assertGreater(score, threshold)

    def test_exact_exposure_does_not_expand_the_frozen_threshold_and_handles_edges(self):
        p, threshold = .040229885057471264, .2498112275967828
        immediately_lower_p = math.nextafter(p, -math.inf)
        below = float(comparison.exact_exposure_scores([{'values': [immediately_lower_p, 7]}])[0])
        self.assertEqual(below.hex(), '0x1.ff9d07659d468p-3')
        self.assertLess(below, threshold)
        self.assertFalse(below >= threshold)  # A multi-ULP acceptance band would wrongly include it.
        edge_inputs = [(0., 0), (0., 7), (1., 0), (1., 7), (p, 1)]
        scores = comparison.exact_exposure_scores([{'values': [probability, count]} for probability, count in edge_inputs])
        self.assertEqual([float(value) for value in scores], [0., 0., 0., 1., p])
        for count in (-1, 1.5, True):
            with self.subTest(invalid_count=count):
                with self.assertRaises(ValueError):
                    comparison.exact_exposure_scores([{'values': [p, count]}])


if __name__ == '__main__':
    unittest.main()
