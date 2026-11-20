import copy
import unittest
from travel_ml.common import load_config
from travel_ml.labels import build_labels,split_rows


def feature(cohort):
    return {'hotelId':'HOTEL-001','targetCheckInDate':'2025-11-01','asOf':'2025-10-31T09:00:00+09:00','cohortBookingIds':cohort}


def observed(booking,label,when='2025-11-02T09:00:00+09:00'):
    return {'event_id':f'E-{booking}-{label}','event_type':'OBSERVED_OUTCOME','hotel_id':'HOTEL-001',
            'booking_id':booking,'occurred_at':when,'available_at':when,'payload':{'label':label}}


class LabelTests(unittest.TestCase):
    def setUp(self): self.config=load_config()
    def label(self,cohort,events): return build_labels({'events':events},[feature(cohort)],self.config)[0]
    def test_empty_is_not_eligible(self):
        row=self.label([],[])
        self.assertEqual(row['labelStatus'],'NOT_ELIGIBLE');self.assertIsNone(row['target'])
    def test_one_failure_remains_positive_with_unknown_member(self):
        row=self.label(['A','B'],[observed('A',1)])
        self.assertEqual(row['target'],1);self.assertEqual(row['unknownBookingCount'],1)
    def test_some_success_and_unknown_is_not_negative(self):
        self.assertIsNone(self.label(['A','B'],[observed('A',0)])['target'])
    def test_all_success_uses_last_observation(self):
        row=self.label(['A','B'],[observed('A',0),observed('B',0,'2025-11-03T09:00:00+09:00')])
        self.assertEqual(row['target'],0);self.assertEqual(row['labelAvailableAt'],'2025-11-03T09:00:00+09:00')
    def test_future_evidence_is_not_used_at_extraction(self):
        self.assertIsNone(self.label(['A'],[observed('A',1,'2026-02-01T09:00:00+09:00')])['target'])
    def test_private_truth_does_not_produce_label(self):
        source={'events':[],'truth':[{'booking_id':'A','truth_label':1}]}
        self.assertIsNone(build_labels(source,[feature(['A'])],self.config)[0]['target'])
    def test_semantic_correction_rejected(self):
        with self.assertRaises(ValueError): self.label(['A'],[observed('A',0),observed('A',1)])
    def test_split_boundary_purges_equal_time(self):
        row={'hotelId':'HOTEL-001','targetCheckInDate':'2025-09-29','asOf':'2025-09-28T09:00:00+09:00',
             'target':0,'labelStatus':'KNOWN','labelAvailableAt':'2025-09-30T09:00:00+09:00'}
        parts,audit=split_rows([row],self.config)
        self.assertEqual(parts['train'],[]);self.assertEqual(audit[0]['status'],'PURGED_LABEL_NOT_AVAILABLE')
        row['labelAvailableAt']='2025-09-30T08:59:59+09:00'
        parts,_=split_rows([row],self.config);self.assertEqual(len(parts['train']),1)
