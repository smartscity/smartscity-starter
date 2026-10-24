#!/usr/bin/env python3
"""Own a fresh loopback MySQL instance; never accept an external database URL."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PORT = 13367


def port_open():
    with socket.socket() as sock:
        sock.settimeout(.2)
        return sock.connect_ex(("127.0.0.1", PORT)) == 0


def stop(process):
    if process is not None:
        # Stop the process group as well: Maven may own Surefire/SDK children.
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=5)


def run(command, output, timeout):
    with output.open("w") as log:
        log.write(json.dumps(command) + "\n")
        log.flush()
        process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
            if code:
                raise RuntimeError(f"Exit {code}: see {output}")
        finally:
            stop(process)


def reports(destination):
    destination.mkdir()
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for module in ("smartscity-id-core", "smartscity-id-spring-boot-autoconfigure"):
        for path in (ROOT / module / "target/surefire-reports").glob("TEST-*.xml"):
            suite = ET.parse(path).getroot()
            for key in totals:
                totals[key] += int(suite.attrib.get(key, 0))
            # Keep assertion output without XML's entire host environment/classpath.
            text = path.with_name(path.name[5:-4] + ".txt")
            if text.exists():
                shutil.copyfile(text, destination / text.name)
    (destination / "totals.json").write_text(json.dumps(totals, indent=2) + "\n")
    return totals


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", help="Explicit opt-in to kill/pause isolated SDKs and shut down test MySQL")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not args.run:
        parser.error("--run is required")
    mysqld, mysql = shutil.which("mysqld"), shutil.which("mysql")
    if not mysqld or not mysql or not os.environ.get("JAVA_HOME"):
        parser.error("Need mysqld, mysql and JAVA_HOME pointing to JDK 21")
    if port_open():
        parser.error(f"Port {PORT} occupied; refusing to touch the existing server")
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    server = None
    status = {"status": "FAIL", "isolatedPort": PORT, "cleanup": False}
    # A new private directory on every run; never reuse user data directories.
    with tempfile.TemporaryDirectory(prefix="id-fault-") as temporary:
        work = Path(temporary)
        data, sock = work / "data", work / "mysql.sock"
        data.mkdir()
        try:
            run([mysqld, "--no-defaults", f"--datadir={data}", "--initialize-insecure"], out / "initialize.txt", 60)
            with (out / "mysql.txt").open("w") as log:
                server = subprocess.Popen([mysqld, "--no-defaults", f"--datadir={data}", f"--socket={sock}",
                    f"--port={PORT}", "--bind-address=127.0.0.1", "--mysqlx=OFF", f"--pid-file={work / 'mysql.pid'}"],
                    stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            deadline = time.monotonic() + 30
            while not sock.exists() or not port_open():
                if server.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Isolated MySQL failed to start")
                time.sleep(.1)
            run([mysql, "--no-defaults", "-u", "root", f"--socket={sock}", "-e", "CREATE DATABASE smartscity_id_test"], out / "schema.txt", 10)
            base = [str(ROOT / "mvnw"), "-o", "-B", "-pl", "smartscity-id-spring-boot-autoconfigure", "-am",
                    "-Dsurefire.failIfNoSpecifiedTests=false", "-DargLine=-Xmx512m",
                    f"-Did.test.mysql.url=jdbc:mysql://127.0.0.1:{PORT}/smartscity_id_test?useSSL=false&allowPublicKeyRetrieval=true"]
            run(base + ["clean", "test", "-Dtest=CoreTest,ClockAndLifecycleTest,LifecycleReviewTest,MySqlIntegrationTest,ZookeeperIntegrationTest"], out / "faults.txt", 150)
            status["faults"] = reports(out / "fault-reports")
            # Remove stale reports so outage totals only include this invocation.
            for module in ("smartscity-id-core", "smartscity-id-spring-boot-autoconfigure"):
                shutil.rmtree(ROOT / module / "target/surefire-reports", ignore_errors=True)
            run(base + ["test", "-Dtest=MySqlOutageTest", "-Did.test.mysql.outage=true"], out / "outage.txt", 40)
            status["outage"] = reports(out / "outage-reports")
            if any(t["failures"] or t["errors"] or t["skipped"] for t in (status["faults"], status["outage"])):
                raise RuntimeError("A selected fault test failed or was skipped")
            status["status"] = "PASS"
        finally:
            stop(server)
            status["cleanup"] = not port_open()
            status["elapsedSeconds"] = round(time.monotonic() - started, 2)
            (out / "result.json").write_text(json.dumps(status, indent=2) + "\n")
            if not status["cleanup"]:
                raise RuntimeError("Port still active after cleanup; see result.json")
    print(json.dumps(status))


if __name__ == "__main__":
    main()
