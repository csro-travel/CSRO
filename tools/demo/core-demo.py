r"""Run the isolated synthetic customer-rescue demo; never reuse or erase a DB.

Windows: py tools\demo\core-demo.py
The console stays open. Commands: status, arrive (after booking), stop.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import webbrowser


ROOT = Path(__file__).resolve().parents[2]
INITIAL_TIME = "2026-11-09T10:20:00+09:00"
ARRIVAL_TIME = "2026-11-10T15:00:00+09:00"
FIXED_PORTS = (25434, 25442, 18082, 3100, 3101)
ENV_PREFIXES = ("SPRING_", "BOOKING_", "ML_", "MOCK_", "RISK_", "CSRO_", "SERVER_", "QUOTE_", "REPLACEMENT_", "RESOLUTION_", "AGENT_", "HOTEL_ANALYSIS_", "INCIDENT_")


def clean_environment() -> dict[str, str]:
    # A previously activated venv or service console must not redirect Spring/Flyway.
    return {k: v for k, v in os.environ.items() if not k.startswith(ENV_PREFIXES)}


def demo_ports(args: argparse.Namespace) -> tuple[int, ...]:
    """Explicit service ports let managed computers avoid their reserved system ports."""
    selected = (args.agent_port, args.mock_port, args.ml_port)
    if (any(type(port) is not int or not 1024 <= port <= 65535 for port in selected)
            or len(set(selected)) != len(selected) or set(selected).intersection(FIXED_PORTS)):
        raise RuntimeError("ML, Agent and Mock ports must be distinct non-privileged ports separate from the fixed demo ports.")
    return FIXED_PORTS + selected


def ensure_ports_free(ports: tuple[int, ...]) -> None:
    for port in ports:
        with socket.socket() as probe:
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            else:
                # Mirror local servers' POSIX restart behavior: TIME_WAIT is not
                # a live listener, while an already listening port remains busy.
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                raise RuntimeError(f"Port {port} is occupied. Stop that service yourself; no existing service was stopped or reused.") from None


def python_for(explicit: str | None, candidates: list[Path], imports: str, cwd: Path) -> str:
    paths = [Path(explicit)] if explicit else candidates
    for path in paths:
        if path.is_file():
            result = subprocess.run([str(path), "-c", imports], cwd=cwd, env=clean_environment(), capture_output=True)
            if result.returncode == 0:
                # Resolving a venv's Python symlink selects the base interpreter and
                # silently loses its site-packages on Unix. Keep the venv entry path.
                return str(path.absolute())
    raise RuntimeError(f"Python environment missing dependencies in {cwd.relative_to(ROOT)}. Install its documented requirements first.")


class Demo:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.base = clean_environment()
        self.owner = secrets.token_hex(16)
        self.session = Path(tempfile.mkdtemp(prefix="csro-core-demo-"))
        self.children: list[subprocess.Popen] = []
        self.logs = []
        self.containers: list[dict] = []
        self.web = None
        self.case_id = None
        self.phase = "PREPARING"
        self.counselor_token = secrets.token_urlsafe(32)
        self.customer_token = secrets.token_urlsafe(32)
        self.mock_token = secrets.token_urlsafe(32)
        self.agent_token = secrets.token_urlsafe(32)
        self.ml_token = secrets.token_urlsafe(32)

    def command(self, argv, *, cwd=ROOT, env=None, name="command", timeout=120):
        with open(self.session / (name + ".log"), "wb") as log:
            result = subprocess.run([str(a) for a in argv], cwd=cwd, env=env or self.base,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"{name} failed. Inspect {self.session / (name + '.log')}; do not share private files wholesale.")

    def start(self, name, argv, cwd, env):
        log = open(self.session / (name + ".log"), "ab")
        self.logs.append(log)
        proc = subprocess.Popen([str(a) for a in argv], cwd=cwd, env=env, stdout=log, stderr=log)
        self.children.append(proc)
        return proc

    @staticmethod
    def read(port, path, token=None):
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if port in (3100, 3101) and path.startswith("/demo/"):
            headers.update({"X-Demo-Read": "1", "Sec-Fetch-Site": "same-origin"})
        request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers=headers)
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.load(response)

    def ready(self, proc, port, path, token=None):
        until = time.monotonic() + 90
        while time.monotonic() < until:
            if proc.poll() is not None:
                raise RuntimeError(f"Service on {port} exited. Inspect private logs in {self.session}.")
            try:
                return self.read(port, path, token)
            except (OSError, ValueError):
                time.sleep(0.2)
        raise RuntimeError(f"Service on {port} did not become ready. Inspect {self.session}.")

    def database(self, port, name, user):
        container = {"name": f"csro-core-{port}-{self.owner[:12]}", "port": port,
                     "database": name, "user": user, "password": secrets.token_urlsafe(32)}
        # Docker can create the named container and then fail to bind its port.
        # Track that attempt before run so label-checked cleanup can still find it.
        self.containers.append(container)
        subprocess.run(["docker", "run", "--detach", "--name", container["name"],
                        "--label", "csro.core-demo-owner=" + self.owner,
                        "-p", f"127.0.0.1:{port}:5432", "-e", "POSTGRES_USER=" + user,
                        "-e", "POSTGRES_DB=" + name, "-e", "POSTGRES_PASSWORD", "postgres:18.7"],
                       env={**self.base, "POSTGRES_PASSWORD": container["password"]},
                       capture_output=True, check=True)
        # Keep its data after stop. Never reuse an unrelated container or delete a volume.
        for _ in range(120):
            # The image's initialization server accepts Unix sockets before it restarts.
            # Wait for TCP, which is enabled only for the final server.
            ready = subprocess.run(["docker", "exec", container["name"], "pg_isready", "-h", "127.0.0.1", "-U", user, "-d", name], capture_output=True)
            if ready.returncode == 0:
                break
            time.sleep(0.25)
        else:
            raise RuntimeError("A new dedicated demo database did not become ready.")
        actual = subprocess.run(["docker", "exec", container["name"], "psql", "-XAt", "-U", user, "-d", name,
                                 "-v", "ON_ERROR_STOP=1", "-c", "SELECT current_database() || ':' || current_user"],
                                capture_output=True, text=True, check=True).stdout.strip()
        if actual != f"{name}:{user}":
            raise RuntimeError("Actual demo database identity differs. Spring/Flyway will not start.")
        return container

    def publish(self):
        # Non-secret session evidence helps inspect a stopped demo without exposing credentials.
        public = {"owner": self.owner, "phase": self.phase, "caseId": self.case_id,
                  "containers": [{k: v for k, v in c.items() if k != "password"} for c in self.containers],
                  "counselorUrl": "http://127.0.0.1:3100/cases",
                  "customerUrl": None if not self.case_id else f"http://127.0.0.1:3101/customer/cases/{self.case_id}",
                  "customerReportUrl": "http://127.0.0.1:3101/customer/bookings/YB-001",
                  "riskSelectionPolicy": "MAX_OBSERVED_RECALL",
                  "riskInputKind": "SYNTHETIC_OBSERVATIONS",
                  "servicePorts": {"agent": self.args.agent_port, "mock": self.args.mock_port,
                                   "ml": self.args.ml_port, "spring": 18082}}
        (self.session / "session.json").write_text(json.dumps(public, indent=2), encoding="utf-8")

    def spring(self, as_of):
        self.web = self.start("spring", [*self.java, "--quote.search.worker.enabled=true",
                                       "--replacement.worker.enabled=true", "--incident.recheck.worker.enabled=true",
                                       "--quote.search.demo-as-of=" + as_of],
                              ROOT / "backend", self.spring_env)
        self.ready(self.web, 18082, "/api/v1/cases", self.counselor_token)

    def prepare(self):
        ensure_ports_free(demo_ports(self.args))
        for name in ("docker", "java", "node"):
            if not shutil.which(name):
                raise RuntimeError(f"Required command missing: {name}")
        subprocess.run(["docker", "info", "--format", "{{.ServerVersion}}"], capture_output=True, check=True)
        suffix = Path("Scripts/python.exe") if os.name == "nt" else Path("bin/python")
        self.ml_python = python_for(self.args.python_ml, [ROOT / "ml/.venv" / suffix],
                                    "import travel_ml.risk_api, travel_ml.risk_automation", ROOT / "ml")
        self.mock_python = python_for(self.args.python_mock, [ROOT / "mock-server/.venv" / suffix],
                                      "import uvicorn, psycopg, app.main", ROOT / "mock-server")
        self.agent_python = python_for(self.args.python_agent,
                                       [ROOT / "llm/agent/.venv" / suffix, ROOT / ".venv" / suffix, Path(self.mock_python)],
                                       "import uvicorn, app.main", ROOT / "llm/agent")
        if not self.args.skip_build:
            print("Building Spring and frontend. This may take a few minutes.", flush=True)
            gradle = ["cmd.exe", "/d", "/c", "gradlew.bat"] if os.name == "nt" else ["bash", "./gradlew"]
            self.command([*gradle, "bootJar", "--console=plain"], cwd=ROOT / "backend", name="build-spring", timeout=600)
            pnpm = shutil.which("pnpm.cmd" if os.name == "nt" else "pnpm")
            if not pnpm:
                raise RuntimeError("pnpm is missing. Install the repository's documented version.")
            prefix = ["cmd.exe", "/d", "/c", pnpm] if os.name == "nt" else [pnpm]
            self.command([*prefix, "build"], cwd=ROOT / "frontend/travel_bom", name="build-frontend", timeout=600)
            self.command([*prefix, "exec", "tsc", "--noEmit"], cwd=ROOT / "frontend/travel_bom", name="typecheck", timeout=120)
        jars = [p for p in (ROOT / "backend/build/libs").glob("*.jar") if not p.name.endswith("-plain.jar")]
        if len(jars) != 1 or not (ROOT / "frontend/travel_bom/.next/BUILD_ID").is_file():
            raise RuntimeError("Build output missing or ambiguous. Run without --skip-build.")
        self.java = ["java", "-jar", str(jars[0]), "--spring.profiles.active=local-chat-demo"]
        business = self.database(25434, "yeohaengbom_chat_demo", "yb_chat_demo")
        supplier = self.database(25442, "yeohaengbom_mock", "yb_mock")
        self.publish()
        mock_command = [self.mock_python, "-m", "app.stateful_server"] if self.args.mock_port == 8001 else [
            self.mock_python, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(self.args.mock_port), "--no-proxy-headers"]
        mock = self.start("mock", mock_command, ROOT / "mock-server",
                          {**self.base, "MOCK_SERVICE_TOKEN": self.mock_token, "MOCK_STATEFUL_ENABLED": "true",
                           "MOCK_DB_PASSWORD": supplier["password"], "MOCK_BOOKING_AS_OF": INITIAL_TIME})
        ml = self.start("ml", [self.ml_python, "-m", "uvicorn", "travel_ml.risk_api:app", "--host", "127.0.0.1", "--port", str(self.args.ml_port), "--log-level", "warning", "--no-proxy-headers"],
                        ROOT / "ml", {**self.base, "ML_SERVICE_TOKEN": self.ml_token})
        agent = self.start("agent", [self.agent_python, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(self.args.agent_port), "--log-level", "warning", "--no-proxy-headers"],
                           ROOT / "llm/agent", {**self.base, "AGENT_SERVICE_TOKEN": self.agent_token})
        self.ready(mock, self.args.mock_port, "/healthz"); self.ready(ml, self.args.ml_port, "/readyz"); self.ready(agent, self.args.agent_port, "/openapi.json")
        helper = "from pathlib import Path; import sys; from travel_ml.risk_automation import load_sources,prepare_cycle; p,f=prepare_cycle(load_sources(Path(sys.argv[1])),sys.argv[2],Path(sys.argv[3])); assert not f; print(p)"
        prepared = subprocess.run([self.ml_python, "-c", helper, str(ROOT / "ml/config/risk_automation_sources_v1.json"), "2026-11-10", str(self.session / "observations")],
                                  cwd=ROOT / "ml", env=self.base, capture_output=True, text=True, check=True)
        manifest = prepared.stdout.strip()
        self.spring_env = {**self.base, "CSRO_CHAT_DEMO_DB_PASSWORD": business["password"], "CSRO_CHAT_DEMO_TOKEN": self.counselor_token,
                           "CSRO_CUSTOMER_DEMO_TOKEN": self.customer_token, "ML_RISK_SERVICE_TOKEN": self.ml_token,
                           "ML_RISK_BASE_URL": f"http://127.0.0.1:{self.args.ml_port}",
                           "HOTEL_ANALYSIS_AGENT_BASE_URL": f"http://127.0.0.1:{self.args.agent_port}",
                           "HOTEL_ANALYSIS_AGENT_SERVICE_TOKEN": self.agent_token,
                           "SPRING_APPLICATION_JSON": json.dumps({
                               "booking": {"reconfirmation": {"mock": {"base-url": f"http://127.0.0.1:{self.args.mock_port}", "service-token": self.mock_token}}},
                               "quote": {"ranking": {"agent": {"base-url": f"http://127.0.0.1:{self.args.agent_port}", "service-token": self.agent_token}}}})}
        self.command([*self.java, "--spring.main.web-application-type=none", "--risk.batch.enabled=true",
                      "--risk.batch.manifest-file=" + manifest, "--risk.batch.policy=MAX_OBSERVED_RECALL"],
                     cwd=ROOT / "backend", env=self.spring_env, name="risk-case", timeout=120)
        self.spring(INITIAL_TIME)
        listing = self.read(18082, "/api/v1/cases", self.counselor_token)
        self.case_id = next(c["caseId"] for c in listing["items"] if c["bookingId"] == "YB-001")
        frontend = ROOT / "frontend/travel_bom"
        shared = {**self.base, "CSRO_DEMO_SPRING_PORT": "18082", "CSRO_LOCAL_DEMO": "true",
                  "CSRO_REPLACEMENT_DEMO_PROXY": "true", "CSRO_RESOLUTION_DEMO_PROXY": "true"}
        counselor = self.start("counselor", ["node", "demo-server.mjs"], frontend,
                               {**shared, "PORT": "3100", "CSRO_DEMO_TOKEN": self.counselor_token, "CSRO_CHAT_DEMO_PROXY": "true",
                                "CSRO_QUOTE_DEMO_PROXY": "true", "CSRO_OFFER_DEMO_PROXY": "true"})
        customer = self.start("customer", ["node", "demo-server.mjs"], frontend,
                              {**shared, "PORT": "3101", "CSRO_CUSTOMER_DEMO_PROXY": "true", "CSRO_CUSTOMER_DEMO_TOKEN": self.customer_token,
                               "CSRO_INCIDENT_DEMO_PROXY": "true"})
        self.ready(counselor, 3100, "/demo/cases"); self.ready(customer, 3101, f"/demo/cases/{self.case_id}/messages")
        self.phase = "BOOKING"
        self.publish()
        print(f"DEMO_READY {self.session / 'session.json'}", flush=True)
        print("Synthetic demo: search -> send 3 candidates -> customer selection -> counselor booking.")
        print("ML uses its actual synthetic-observation score with the explicit MAX_OBSERVED_RECALL demo selection policy.")
        print("Counselor: http://127.0.0.1:3100/cases")
        print(f"Customer: http://127.0.0.1:3101/customer/cases/{self.case_id}")
        print("Customer incident report: http://127.0.0.1:3101/customer/bookings/YB-001 (new report, same active case)")
        print("API-024 is configured for explicitly selected stored natural hotel text; the structured Mock JSON is not analyzed automatically.")
        print("After CONFIRMED, type arrive here to advance the virtual date; then confirm check-in, finish tasks, and close the case.")
        if not self.args.no_browser:
            webbrowser.open("http://127.0.0.1:3100/cases")
            webbrowser.open(f"http://127.0.0.1:3101/customer/cases/{self.case_id}")

    def arrive(self):
        if self.phase != "BOOKING":
            print("The virtual arrival phase is already active.", flush=True)
            return
        messages = self.read(18082, f"/api/v1/cases/{self.case_id}/messages", self.counselor_token)
        pointer = messages.get("latestReplacementBooking")
        if not pointer or self.read(18082, "/api/v1/replacement-bookings/" + pointer["replacementBookingId"], self.counselor_token)["status"] != "CONFIRMED":
            print("Wait for the counselor's booking to become CONFIRMED before arrive.", flush=True)
            return
        self.web.terminate()
        self.web.wait(timeout=15)
        self.spring(ARRIVAL_TIME)
        self.phase = "ARRIVAL"
        self.publish()
        print("DEMO_ARRIVAL_READY 2026-11-10T15:00:00+09:00 - refresh both pages.", flush=True)

    def status(self):
        detail = self.read(18082, f"/api/v1/cases/{self.case_id}", self.counselor_token)
        view = detail.get("resolution", {})
        print(json.dumps({"phase": self.phase, "caseId": self.case_id, "status": detail["status"],
                          "canClose": view.get("canClose"), "blockers": view.get("blockers", [])}, ensure_ascii=False), flush=True)

    def stop(self):
        for proc in reversed(self.children):
            try:
                if proc.poll() is not None:
                    continue
                try:
                    proc.terminate()
                except OSError:
                    proc.kill()
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=5)
            except (OSError, subprocess.SubprocessError):
                print("An owned demo process could not stop; remaining owned resources will still be checked.", file=sys.stderr)
        for log in self.logs:
            try:
                log.close()
            except OSError:
                print("An owned demo log could not close.", file=sys.stderr)
        for container in reversed(self.containers):
            try:
                check = subprocess.run(["docker", "inspect", "--format", '{{ index .Config.Labels "csro.core-demo-owner" }}', container["name"]], capture_output=True, text=True)
                if check.returncode == 0 and check.stdout.strip() == self.owner:
                    stopped = subprocess.run(["docker", "stop", container["name"]], capture_output=True)
                    if stopped.returncode:
                        print("An owned demo container could not stop. Check Docker Desktop.", file=sys.stderr)
            except (OSError, subprocess.SubprocessError):
                print("An owned demo container could not be checked; no other container was accessed.", file=sys.stderr)
        self.phase = "STOPPED"
        try:
            self.publish()
        except OSError:
            print("The stopped demo session summary could not be updated.", file=sys.stderr)
        print(f"Owned demo cleanup finished. Database containers and data retained; session: {self.session}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python-ml"); parser.add_argument("--python-mock"); parser.add_argument("--python-agent")
    parser.add_argument("--mock-port", type=int, default=8001, help="Optional loopback Mock port; never stop a service already using a port.")
    parser.add_argument("--agent-port", type=int, default=8000, help="Optional loopback Agent port; use a private unused port on managed computers.")
    parser.add_argument("--ml-port", type=int, default=8002, help="Optional loopback ML port; use a private unused port on managed computers.")
    parser.add_argument("--skip-build", action="store_true", help="Use an already verified current build.")
    parser.add_argument("--no-browser", action="store_true", help="Print URLs without opening browser windows.")
    demo = Demo(parser.parse_args())
    try:
        demo.prepare()
        while True:
            command = input("Demo [status / arrive / stop]: ").strip().lower()
            if command == "stop":
                break
            if command == "arrive":
                demo.arrive()
            elif command == "status":
                demo.status()
            elif command:
                print("Commands: status, arrive, stop")
    except (KeyboardInterrupt, EOFError):
        pass
    except Exception as failure:
        # urllib/Subprocess exceptions can include arguments or URLs; never dump env/traceback.
        print(f"Demo stopped: {failure if isinstance(failure, RuntimeError) else type(failure).__name__}. Logs: {demo.session}", file=sys.stderr)
        return 1
    finally:
        demo.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
