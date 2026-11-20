"""Controlled full-calendar-shape fixtures test training boundaries, not reality."""
import copy
import csv
from datetime import date, datetime, time, timedelta
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from zoneinfo import ZoneInfo

import numpy as np

from travel_ml.training import (FEATURES, MATURITY, META, SCHEDULE, binary_metrics,
    read_rows, rule_scores, select_threshold, top_k_metrics, train_models, validate_splits)

ROOT=Path(__file__).resolve().parents[1]
ZONE=ZoneInfo('Asia/Seoul')


def fixture(folder, single_validation=False):
    config=json.loads((ROOT/'config/simulator_v0.2.json').read_text())
    dates=[date(2025,m,d) for m in range(4,10) for d in (1,15)]
    dates += [date(2025,9,29),date(2025,9,30)]
    dates += [date(2025,10,d) for d in (1,10,20,27,29,31)]
    dates += [date(2025,11,d) for d in (1,15,30)]+[date(2025,12,d) for d in (1,15,31)]
    rows=[]
    for target in dates:
        for hotel in range(1,25):
            count=0 if hotel==24 else (1 if hotel<10 else 3)
            # Controlled fixture targets. No model or simulator quality claim.
            status='NOT_ELIGIBLE' if count==0 else 'UNKNOWN' if hotel==23 else 'KNOWN'
            label=(hotel+target.day)%2
            if single_validation and target.month==10:label=0
            rows.append({
                'hotelId':f'TEST-HOTEL-{hotel:03d}',
                'targetCheckInDate':target.isoformat(),
                'asOf':datetime.combine(target-timedelta(days=1),time(9),ZONE).isoformat(),
                'featureSetVersion':'risk-features-0.2',
                'pastFailureRate90d':0.12 if hotel%2 else 0.01,
                'targetConfirmedBookingCount':count,
                'bookingVolumeGrowth7d':(hotel%3)/10,
                'reconfirmationOverdueRate7d':(hotel%4)/10,
                'inventoryUnavailableRate7d':(hotel%2)/10,
                'isPeakDay':'true' if target.weekday() in (4,5) else 'false',
                'target':str(label) if status=='KNOWN' else '',
                'labelStatus':status,
                'labelAvailableAt':datetime.combine(target+timedelta(days=3),time(18),ZONE).isoformat() if status=='KNOWN' else '',
                'private_truth':'future failure must never become a model feature'})
    fields=list(rows[0])
    def write(path,values):
        with path.open('w',newline='') as file:
            writer=csv.DictWriter(file,fieldnames=fields);writer.writeheader();writer.writerows(values)
    folder.mkdir(parents=True,exist_ok=True)
    write(folder/'dataset.csv',rows)
    for name,(first,last) in SCHEDULE.items():
        selected=[]
        for row in rows:
            if first<=row['targetCheckInDate']<=last and row['labelStatus']=='KNOWN':
                label_at=datetime.fromisoformat(row['labelAvailableAt'])
                if name in MATURITY and label_at>=MATURITY[name]:continue
                selected.append(row)
        write(folder/f'{name}.csv',selected)
    return config,rows,write


class TrainingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=TemporaryDirectory()
        cls.root=Path(cls.tmp.name)
        cls.data=cls.root/'data'
        cls.config,cls.raw,cls.write=fixture(cls.data)
        cls.out=cls.root/'model'
        cls.report=train_models(cls.data,cls.out,cls.config)

    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()

    def test_feature_whitelist_model_checkpoint_and_artifacts(self):
        import lightgbm as lgb
        meta=json.loads((self.out/'model_metadata.json').read_text())
        model=lgb.Booster(model_file=str(self.out/'model.txt'))
        self.assertEqual(model.feature_name(),list(FEATURES))
        self.assertEqual(meta['featureNames'],list(FEATURES))
        self.assertNotIn('hotelId',model.feature_name())
        self.assertNotIn('private_truth',model.feature_name())
        self.assertFalse(meta['realWorldPerformanceValidated'])
        self.assertIn('no train+validation refit',meta['frozenSelection']['finalModelPolicy'])
        self.assertEqual(meta['selectionFrozenBeforeTestSha256'],self.report['selectionFrozenBeforeTestSha256'])
        self.assertEqual(meta['parameters']['num_threads'],1)
        from travel_ml.storage import verify_output
        self.assertEqual(verify_output(self.out)['type'],'model')
        for name in ('model.txt','model_metadata.json','evaluation_report.json','evaluation_report.md','search_results.csv','test_predictions.csv','manifest.json'):
            self.assertTrue((self.out/name).exists())
        with (self.out/'search_results.csv').open() as f:
            candidates=list(csv.DictReader(f))
        self.assertEqual(len(candidates),6)

    def test_exact_dates_unknown_exclusion_and_maturity(self):
        panel,splits,audit=validate_splits(self.data,self.config)
        self.assertTrue(all(row['target'] in (0,1) for part in splits.values() for row in part))
        self.assertTrue(all(row['labelAvailableAt']<MATURITY['train'] for row in splits['train']))
        self.assertTrue(all(row['labelAvailableAt']<MATURITY['validation'] for row in splits['validation']))
        self.assertGreater(audit['train']['maturityPurged'],0)
        self.assertGreater(audit['validation']['maturityPurged'],0)
        self.assertGreater(audit['test']['unknownExcluded'],0)
        self.assertGreater(audit['test']['notEligibleExcluded'],0)
        self.assertEqual(sum(audit['test'][key] for key in ('knownIncluded','unknownExcluded','notEligibleExcluded','maturityPurged')),audit['test']['calendarPanelRows'])

    def test_top_k_unknown_keeps_position_and_bounds(self):
        rows=read_rows(self.data/'dataset.csv')[:3]
        for row in rows:row['labelStatus']='KNOWN';row['target']=0
        rows[0]['labelStatus']='UNKNOWN';rows[0]['target']=None
        rows[1]['target']=1
        result,chosen=top_k_metrics(rows,np.array([.99,.8,.1]),k=1)
        self.assertEqual(result['selectedRows'],1)
        self.assertEqual(result['selectedKnownRows'],0)
        self.assertEqual(result['selectedUnknownRows'],1)
        self.assertIsNone(result['knownPrecision'])
        self.assertEqual(result['precisionLowerBound'],0)
        self.assertEqual(result['precisionUpperBound'],1)
        self.assertEqual(result['knownPositiveRecall'],0)
        self.assertIn(rows[0]['key'],chosen)

    def test_threshold_validation_f1_tie_and_single_class_metrics(self):
        result=select_threshold([1,0,1],[.8,.5,.2])
        # All positives at threshold .2 produce F1=.8, versus .666 at .8.
        self.assertEqual(result['threshold'],.2)
        no_positive=select_threshold([0,0],[.8,.2])
        self.assertGreater(no_positive['threshold'],.8)
        metric=binary_metrics([0,0],[.1,.2],.5)
        self.assertIsNone(metric['averagePrecision'])
        self.assertIsNone(metric['rocAuc'])
        self.assertIsNone(metric['recall'])
        self.assertEqual(metric['confusionMatrix'],{'tn':2,'fp':0,'fn':0,'tp':0})
        self.assertIn('rocAuc',metric['undefinedReasons'])

    def test_rule_score_has_exposure_adjustment(self):
        rows=read_rows(self.data/'dataset.csv')[:2]
        rows[0]['values'][:2]=[.1,1]
        rows[1]['values'][:2]=[.1,3]
        np.testing.assert_allclose(rule_scores(rows),[.1,1-.9**3])
        np.testing.assert_allclose(rule_scores(rows,reference=True),[.1,.1])

    def test_output_never_overwrites(self):
        with self.assertRaises(FileExistsError):train_models(self.data,self.out,self.config)

    def test_single_class_validation_is_reported_not_crashed(self):
        folder=self.root/'single-class-validation'
        config,_,_=fixture(folder,single_validation=True)
        report=train_models(folder,self.root/'single-model',config)
        self.assertIsNone(report['validation']['lightgbm']['averagePrecision'])
        self.assertEqual(report['selection']['earlyStoppingMetric'],'binary_logloss_single_class_fallback')

    def test_tampered_maturity_membership_and_bad_feature_rejected(self):
        folder=self.root/'bad-membership'
        config,raw,write=fixture(folder)
        leaked=[row for row in raw if row['targetCheckInDate']=='2025-09-30' and row['labelStatus']=='KNOWN'][0]
        existing=[]
        with (folder/'train.csv').open() as f:existing=list(csv.DictReader(f))
        write(folder/'train.csv',[*existing,leaked])
        with self.assertRaisesRegex(ValueError,'membership'):validate_splits(folder,config)
        bad=self.root/'bad-feature.csv'
        modified=copy.deepcopy(raw)
        modified[0]['inventoryUnavailableRate7d']='nan'
        write(bad,modified)
        with self.assertRaisesRegex(ValueError,'finite range'):read_rows(bad)

    def test_training_requires_both_classes(self):
        folder=self.root/'no-training-positive'
        config,raw,write=fixture(folder)
        for row in raw:
            if row['labelStatus']=='KNOWN':row['target']='0'
        write(folder/'dataset.csv',raw)
        for name,(first,last) in SCHEDULE.items():
            chosen=[r for r in raw if first<=r['targetCheckInDate']<=last and r['labelStatus']=='KNOWN' and (name not in MATURITY or datetime.fromisoformat(r['labelAvailableAt'])<MATURITY[name])]
            write(folder/f'{name}.csv',chosen)
        with self.assertRaisesRegex(ValueError,'both observed classes'):train_models(folder,self.root/'not-created',config)

    def test_test_outcomes_do_not_change_checkpoint_or_thresholds(self):
        folder=self.root/'test-outcomes-changed'
        config,raw,write=fixture(folder)
        for row in raw:
            if row['targetCheckInDate']>='2025-11-01' and row['labelStatus']=='KNOWN':
                row['target']=str(1-int(row['target']))
        write(folder/'dataset.csv',raw)
        write(folder/'test.csv',[r for r in raw if r['targetCheckInDate']>='2025-11-01' and r['labelStatus']=='KNOWN'])
        other=self.root/'test-independent-model'
        result=train_models(folder,other,config)
        self.assertEqual(result['selectionFrozenBeforeTestSha256'],self.report['selectionFrozenBeforeTestSha256'])
        self.assertEqual(result['thresholdsSelectedOnValidation'],self.report['thresholdsSelectedOnValidation'])
        self.assertEqual((other/'model.txt').read_bytes(),(self.out/'model.txt').read_bytes())

    def test_prediction_rows_include_unknown_and_test_not_used_for_selection(self):
        with (self.out/'test_predictions.csv').open() as f:rows=list(csv.DictReader(f))
        self.assertTrue(any(row['labelStatus']=='UNKNOWN' and row['lightgbmScore'] for row in rows))
        report=self.report
        self.assertTrue(all(result['threshold']>=0 for result in report['thresholdsSelectedOnValidation'].values()))
        self.assertEqual(len(report['validationPermutationImportance']),6)
        self.assertEqual(report['validationPastRateAblation']['removedFeature'],'pastFailureRate90d')
        for name in ('lightgbm','exposureRule','pastRateReference'):
            self.assertGreater(report['test'][name]['topK']['eligibleRows'],report['test'][name]['knownLabelMetrics']['knownRows'])

if __name__=='__main__':unittest.main()
