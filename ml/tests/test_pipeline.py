from copy import deepcopy
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from travel_ml.common import load_config
from travel_ml.pipeline import generate,effective_config
from travel_ml.provenance import record_runtime
from travel_ml.storage import verify_output


class PipelineTests(unittest.TestCase):
    def test_spawn_parallel_and_sequential_generate_identical_source_artifacts(self):
        config=load_config()
        config['calendar_panel']={'start':'2025-04-01','end':'2025-04-05'}
        config['simulation'].update(start='2025-03-01T00:00:00+09:00',end='2025-04-20T00:00:00+09:00',
                                    internal_arrival_demand_end_date='2025-07-04')
        with TemporaryDirectory() as tmp:
            a,b=Path(tmp)/'a',Path(tmp)/'b'
            sequential=generate(config,a,hotels=2,workers=1)
            parallel=generate(config,b,hotels=2,workers=2)
            self.assertEqual(sequential,parallel)
            record_runtime(a);verify_output(a)
            self.assertTrue((a/'runtime_environment.json').exists())
            with self.assertRaisesRegex(ValueError,'Output exists'):
                generate(config,a,hotels=2)
    def test_pilot_does_not_open_final_holdout(self):
        config=effective_config(load_config(),3,True)
        self.assertEqual(config['generation']['selected_hotel_indices'],[1,76,96])
        self.assertEqual(config['calendar_panel']['end'],'2025-09-15')
        self.assertFalse(config['simulation']['config_frozen_for_final_test'])
        self.assertTrue(config['generation']['is_pilot'])
