import copy
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from test_training import fixture
from travel_ml.__main__ import predict
from travel_ml.common import ROOT,json_dump
from travel_ml.training import train_models


class PredictionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=TemporaryDirectory();cls.root=Path(cls.temp.name)
        config,_,_=fixture(cls.root/'data')
        train_models(cls.root/'data',cls.root/'model',config)
        cls.request=json.loads((ROOT/'examples/risk-request.json').read_text())
    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()
    def execute(self,request):
        path=self.root/'request.json';json_dump(path,request)
        return predict(self.root/'model',path)
    def test_cli_model_roundtrip_uses_exact_contract(self):
        result=self.execute(self.request)
        self.assertEqual(result['featureSetVersion'],'risk-features-0.2')
        self.assertEqual(result['hotelId'],self.request['hotelId'])
        self.assertTrue(0<=result['riskScore']<=1)
    def test_version_extra_truth_and_wrong_clock_are_rejected(self):
        for change in ('version','truth','clock'):
            request=copy.deepcopy(self.request)
            if change=='version':request['featureSetVersion']='risk-features-0.1'
            if change=='truth':request['features']['truth']=1
            if change=='clock':request['asOf']='2025-10-31T10:00:00+09:00'
            with self.subTest(change=change),self.assertRaises(ValueError):self.execute(request)
    def test_nonstandard_json_nan_is_rejected(self):
        request=copy.deepcopy(self.request);request['features']['pastFailureRate90d']=float('nan')
        path=self.root/'bad.json';path.write_text(json.dumps(request))
        with self.assertRaises(ValueError):predict(self.root/'model',path)
    def test_malformed_top_level_shapes_are_consistent_validation_errors(self):
        examples=[[],None,{**self.request,'targetCheckInDate':None},{**self.request,'features':[]},
                  {**self.request,'unexpected':1}]
        for request in examples:
            with self.subTest(request=request),self.assertRaises(ValueError):self.execute(request)
