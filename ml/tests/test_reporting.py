from pathlib import Path
from tempfile import TemporaryDirectory
import json
import unittest

from test_training import fixture
from travel_ml.common import json_dump,sha256
from travel_ml.reporting import add_audit_slices,validate_audit_source
from travel_ml.storage import verify_output,write_parquet
from travel_ml.training import train_models


class ReportingTests(unittest.TestCase):
    def test_private_audit_groups_do_not_supply_unknown_targets_or_reselect_model(self):
        with TemporaryDirectory() as tmp:
            root=Path(tmp);data=root/'data';out=root/'model'
            config,rows,_=fixture(data)
            train_models(data,out,config)
            before=sha256(out/'model.txt')
            by_hotel={}
            for row in rows:by_hotel.setdefault(row['hotelId'],[]).append(row)
            for hotel,population in by_hotel.items():
                private=data/'private_audit'/f'hotel={hotel}'
                audit=data/'audit'/f'hotel={hotel}'
                json_dump(private/'hotel_profile.json',{'room_capacity':24,'private_profile':'unit-test-only'})
                truth=[];features=[]
                for row in population:
                    ids=[f'{hotel}-{row["targetCheckInDate"]}-{n}' for n in range(row['targetConfirmedBookingCount'])]
                    features.append({'targetCheckInDate':row['targetCheckInDate'],'cohortBookingIds':ids})
                    for booking in ids:
                        truth.append({'booking_id':booking,'truth_label':int(row['target']) if row['target'] else 1,
                            'observed_label':int(row['target']) if row['target'] else None,'reason':'UNIT_TEST_PRIVATE_CAUSE'})
                write_parquet(private/'truth.parquet',truth)
                write_parquet(audit/'features.parquet',features)
            metadata=json.loads((out/'model_metadata.json').read_text())
            json_dump(data/'manifest.json',{'configSha256':metadata['configSha256'],'files':{p.relative_to(data).as_posix():sha256(p) for p in sorted(data.rglob('*')) if p.is_file()}})
            predictions_before=(out/'test_predictions.csv').read_bytes()
            result=add_audit_slices(data,out)
            self.assertEqual(sha256(out/'model.txt'),before)
            self.assertEqual((out/'test_predictions.csv').read_bytes(),predictions_before)
            self.assertGreater(sum(g['unknownRows'] for g in result['groups']['simulatorProfile'].values()),0)
            verify_output(out)
            self.assertTrue((out/'audit_slices.json').exists())

    def test_other_valid_experiment_cannot_be_silently_joined(self):
        with TemporaryDirectory() as tmp:
            root=Path(tmp);data=root/'data';out=root/'model'
            config,_,_=fixture(data)
            train_models(data,out,config)
            metadata=json.loads((out/'model_metadata.json').read_text())
            def manifest(config_hash):
                json_dump(data/'manifest.json',{'configSha256':config_hash,'files':{p.relative_to(data).as_posix():sha256(p) for p in sorted(data.rglob('*')) if p.is_file() and p.name!='manifest.json'}})
            manifest(metadata['configSha256']);validate_audit_source(data,out)
            manifest('different-healthy-experiment')
            with self.assertRaisesRegex(ValueError,'configuration differs'):
                validate_audit_source(data,out)
            with (data/'dataset.csv').open('a') as stream:stream.write('\n')
            manifest(metadata['configSha256'])
            with self.assertRaisesRegex(ValueError,'fitted model input'):
                validate_audit_source(data,out)
