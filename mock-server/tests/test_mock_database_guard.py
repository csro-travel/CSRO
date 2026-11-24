import sys
from datetime import datetime
from types import ModuleType
import unittest
from unittest.mock import Mock, patch

from app.stateful import MockDatabaseSettings, PostgresMockStore


class MockDatabaseGuardTest(unittest.TestCase):
    def test_business_database_aliases_and_overrides_are_rejected_before_connection(self):
        valid = {"MOCK_DB_PASSWORD":"test-placeholder"}
        for changed in ({"MOCK_DB_HOST":"localhost"},{"MOCK_DB_PORT":"5432"},
                        {"MOCK_DB_NAME":"yeohaengbom"},{"MOCK_DB_USER":"yb"},
                        {"MOCK_DB_PORT":"25442?currentSchema=public"},{"MOCK_DB_PASSWORD":" "}):
            with self.subTest(changed=next(iter(changed))):
                with self.assertRaises(RuntimeError):
                    MockDatabaseSettings.from_environment({**valid,**changed})
        with self.assertRaises(RuntimeError):
            MockDatabaseSettings.from_environment({})
        self.assertNotIn("test-placeholder",repr(MockDatabaseSettings.from_environment(valid)))

    def test_test_endpoint_requires_an_explicit_test_setting_path(self):
        values = {"MOCK_DB_HOST":"127.0.0.1","MOCK_DB_PORT":"25443","MOCK_DB_NAME":"yeohaengbom_mock_test",
                  "MOCK_DB_USER":"yb_mock_test","MOCK_DB_PASSWORD":"test-placeholder"}
        with self.assertRaises(RuntimeError):
            MockDatabaseSettings.from_environment(values)
        self.assertEqual(MockDatabaseSettings.from_environment(values,testing=True).database,"yeohaengbom_mock_test")
        with self.assertRaises(RuntimeError):
            PostgresMockStore(MockDatabaseSettings("127.0.0.1",5432,"yeohaengbom","yb","test-placeholder"),
                              as_of=datetime.fromisoformat("2026-11-09T10:20:00+09:00"))

    def test_actual_identity_mismatch_blocks_every_ddl_and_seed_statement(self):
        connection = Mock()
        connection.execute.return_value.fetchone.return_value = {"db":"yeohaengbom","username":"yb_mock"}
        fake = ModuleType("psycopg")
        fake.connect = Mock(return_value=connection)
        rows = ModuleType("psycopg.rows")
        rows.dict_row = object()
        with patch.dict(sys.modules,{"psycopg":fake,"psycopg.rows":rows}):
            with self.assertRaises(RuntimeError):
                PostgresMockStore(MockDatabaseSettings.from_environment({"MOCK_DB_PASSWORD":"test-placeholder"}),
                                  as_of=datetime.fromisoformat("2026-11-09T10:20:00+09:00"))
        self.assertEqual(connection.execute.call_count,1)
        self.assertTrue(connection.execute.call_args.args[0].startswith("SELECT current_database()"))
        connection.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
