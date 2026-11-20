from pathlib import Path
import csv
import json
import tempfile
import unittest

import pandas as pd

from travel_ml.common import json_dump,sha256
from travel_ml.storage import verify_output,write_csv,write_parquet


class StorageTests(unittest.TestCase):
    def test_csv_keeps_unknown_blank_and_metadata_outside_selected_columns(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'data.csv'
            write_csv(path,[{'feature':.5,'target':None,'truth':1}],['feature','target'])
            with path.open(encoding='utf-8-sig',newline='') as stream:
                rows=list(csv.DictReader(stream))
            self.assertEqual(rows,[{'feature':'0.5','target':''}])
    def test_parquet_nested_payload_is_inspectable_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'events.parquet'
            write_parquet(path,[{'event_id':'E1','payload':{'status':'CONFIRMED','dates':['2025-01-01']}}])
            row=pd.read_parquet(path).iloc[0]
            self.assertEqual(json.loads(row['payload'])['status'],'CONFIRMED')
    def test_checksum_validation_rejects_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);json_dump(root/'data.json',{'value':1})
            json_dump(root/'manifest.json',{'files':{'data.json':sha256(root/'data.json')}})
            verify_output(root)
            json_dump(root/'data.json',{'value':2})
            with self.assertRaisesRegex(ValueError,'checksum mismatch'):
                verify_output(root)
