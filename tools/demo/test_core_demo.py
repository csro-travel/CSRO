"""Launcher safety regressions; no service, Docker command or database is started."""
from contextlib import redirect_stderr, redirect_stdout
import argparse
import importlib.util
import io
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch


_SPEC = importlib.util.spec_from_file_location("csro_core_demo_test_target", Path(__file__).with_name("core-demo.py"))
demo = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(demo)


class CoreDemoSafetyTest(unittest.TestCase):
    def test_python_probe_preserves_the_venv_entry_instead_of_resolving_its_symlink(self):
        with tempfile.TemporaryDirectory(prefix="csro-demo-python-") as folder:
            root = Path(folder)
            base = root / "base-python"
            base.touch()
            entry = root / "venv" / "bin" / "python"
            entry.parent.mkdir(parents=True)
            try:
                entry.symlink_to(base)
            except OSError:
                self.skipTest("Creating symlinks requires an unavailable platform permission")
            with patch.object(demo.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as probe:
                selected = demo.python_for(None, [entry], "import required_package", root)
            self.assertEqual(selected, str(entry.absolute()))
            self.assertNotEqual(selected, str(base.absolute()))
            self.assertEqual(probe.call_args.args[0][0], str(entry))

    def test_dependency_probe_cannot_inherit_a_stateful_database_or_service_override(self):
        with tempfile.TemporaryDirectory(prefix="csro-demo-environment-") as folder:
            entry = Path(folder) / "python-probe"
            entry.touch()
            with patch.dict(os.environ, {"MOCK_STATEFUL_ENABLED":"true", "MOCK_DB_PASSWORD":"test-placeholder",
                    "SPRING_DATASOURCE_URL":"test-business-url", "KEEP_DEMO_TEST":"retained",
                    "HOTEL_ANALYSIS_DEMO_EVIDENCE_ID":"EV-123", "HOTEL_ANALYSIS_AGENT_SERVICE_TOKEN":"test-placeholder",
                    "INCIDENT_RECHECK_WORKER_ENABLED":"true"}), \
                    patch.object(demo.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as probe:
                demo.python_for(str(entry), [], "import app.main", Path(folder))
            environment = probe.call_args.kwargs["env"]
            for key in ("MOCK_STATEFUL_ENABLED", "MOCK_DB_PASSWORD", "SPRING_DATASOURCE_URL",
                        "HOTEL_ANALYSIS_DEMO_EVIDENCE_ID", "HOTEL_ANALYSIS_AGENT_SERVICE_TOKEN",
                        "INCIDENT_RECHECK_WORKER_ENABLED"):
                # Do not print the entire environment, even if this assertion fails.
                self.assertFalse(key in environment, "Dependency probes must remove service configuration before import")
            self.assertEqual(environment.get("KEEP_DEMO_TEST"), "retained")

    def test_custom_service_ports_do_not_probe_managed_system_defaults(self):
        args = argparse.Namespace(agent_port=18024, mock_port=18027, ml_port=18002)
        ports = demo.demo_ports(args)
        self.assertEqual(set(ports), {25434, 25442, 18082, 3100, 3101, 18024, 18027, 18002})
        self.assertTrue({8000, 8001, 8002}.isdisjoint(ports))

    def test_port_collisions_and_privileged_values_are_rejected_before_service_commands(self):
        for agent, mock, ml in ((18024, 18024, 18002), (18024, 18027, 18082),
                                (18024, 18027, 25434), (80, 18027, 18002),
                                (18024, 18027, 65536)):
            with self.subTest(agent=agent, mock=mock, ml=ml), patch.object(demo.subprocess, "run") as commands:
                with self.assertRaises(RuntimeError):
                    demo.demo_ports(argparse.Namespace(agent_port=agent, mock_port=mock, ml_port=ml))
                commands.assert_not_called()

    def test_occupied_loopback_port_is_rejected_without_stopping_its_existing_listener(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(2)
            port = listener.getsockname()[1]
            with patch.object(demo.subprocess, "run") as commands:
                with self.assertRaisesRegex(RuntimeError, "is occupied"):
                    demo.ensure_ports_free((port,))
                commands.assert_not_called()
            with socket.create_connection(("127.0.0.1", port), timeout=2):
                accepted, _ = listener.accept()
                accepted.close()

    def test_cleanup_only_stops_matching_owner_and_keeps_the_original_failure(self):
        instance = demo.Demo.__new__(demo.Demo)
        instance.owner = "test-current-owner"
        instance.session = Path("unused-test-session")
        instance.phase = "PREPARING"
        broken_child, healthy_child = Mock(), Mock()
        broken_child.poll.return_value = None
        broken_child.terminate.side_effect = OSError("simulated child stop failure")
        broken_child.kill.side_effect = OSError("simulated child kill failure")
        healthy_child.poll.return_value = None
        instance.children = [broken_child, healthy_child]
        broken_log, healthy_log = Mock(), Mock()
        broken_log.close.side_effect = OSError("simulated log close failure")
        instance.logs = [broken_log, healthy_log]
        instance.containers = [{"name":"owned-container"}, {"name":"foreign-container"}, {"name":"unavailable-container"}]
        instance.publish = Mock()
        calls = []

        def docker_call(argv, **kwargs):
            calls.append(argv)
            self.assertEqual(argv[0], "docker")
            self.assertTrue(argv[1] in {"inspect", "stop"}, "Cleanup must never remove containers or volumes")
            if argv[-1] == "unavailable-container":
                raise OSError("simulated Docker inspection failure")
            if argv[1] == "inspect":
                owner = instance.owner if argv[-1] == "owned-container" else "different-owner"
                return subprocess.CompletedProcess(argv, 0, stdout=owner+"\n")
            return subprocess.CompletedProcess(argv, 0)

        with patch.object(demo.subprocess, "run", side_effect=docker_call), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, "original startup failure"):
                try:
                    raise RuntimeError("original startup failure")
                finally:
                    instance.stop()
        self.assertEqual([argv[-1] for argv in calls if argv[1] == "stop"], ["owned-container"])
        healthy_child.terminate.assert_called_once()
        healthy_child.wait.assert_called_once()
        healthy_log.close.assert_called_once()
        instance.publish.assert_called_once()
        self.assertEqual(instance.phase, "STOPPED")


if __name__ == "__main__":
    unittest.main()
