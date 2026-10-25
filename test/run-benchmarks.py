#!/usr/bin/env python3
"""Opt-in, bounded local benchmarks. No external database or ZooKeeper required."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "test/smartscity-id-benchmark/target/benchmarks.jar"


def capture(command):
    return subprocess.check_output(command, cwd=ROOT, stderr=subprocess.STDOUT, text=True, timeout=10).strip()


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
            # Kill this runner's group, including JMH forks on timeout/interruption.
            for sig in (signal.SIGTERM, signal.SIGKILL):
                try:
                    os.killpg(process.pid, sig)
                    process.wait(timeout=3)
                except ProcessLookupError:
                    break
                except subprocess.TimeoutExpired:
                    pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not args.run:
        parser.error("--run required; see docs/08-Benchmark.md for scenario/budget")
    if not JAR.exists() or not os.environ.get("JAVA_HOME"):
        parser.error("Build -Pbenchmarks first and set JAVA_HOME to JDK 21")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    environment = {"os": platform.platform(), "logicalCpus": os.cpu_count(), "java": capture([java, "-version"]),
                   "baseCommit": capture(["git", "rev-parse", "HEAD"]), "gitStatus": capture(["git", "status", "--short"]),
                   "jarSha256": hashlib.sha256(JAR.read_bytes()).hexdigest(), "jvm": ["-Xms512m", "-Xmx512m"],
                   "budgetSeconds": 480, "storage": "local; no storage or network I/O on measured ID path"}
    if platform.system() == "Darwin":
        environment["cpu"] = capture(["sysctl", "-n", "machdep.cpu.brand_string"])
        environment["memoryBytes"] = int(capture(["sysctl", "-n", "hw.memsize"]))
    (out / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")
    files = sorted(p for p in ROOT.glob("**/*")
                   if p.is_file() and "target" not in p.parts and p.suffix in (".java", ".xml"))
    files += [Path(__file__).resolve()]
    (out / "sources.sha256").write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(ROOT)}\n" for p in files))
    start = time.monotonic()
    result = {"status": "PARTIAL", "jmh": [], "fixedRate": []}

    def remaining(limit):
        value = min(limit, 480 - (time.monotonic() - start))
        if value <= 0:
            raise TimeoutError("Benchmark budget exhausted")
        return value

    try:
        for profile in ("standard", "javascript", "high-throughput"):
            for threads in (1, 4, 8):
                name = f"{profile}-{threads}"
                print(f"JMH {name}", flush=True)
                raw = out / f"{name}.json"
                run([java, "-Xms128m", "-Xmx256m", "-jar", str(JAR), "IdBenchmark.generate", "-p", f"profile={profile}",
                     "-t", str(threads), "-f", "1", "-wi", "2", "-w", "2s", "-i", "3", "-r", "3s",
                     "-foe", "true", "-prof", "gc", "-rf", "json", "-rff", str(raw)], out / f"{name}.txt", remaining(40))
                records = json.loads(raw.read_text())
                if len(records) != 1:
                    raise RuntimeError(f"Missing benchmark: {name}")
                record = records[0]
                secondary = record["secondaryMetrics"]
                ids, timeouts = secondary["ids"]["score"], secondary["timeouts"]["score"]
                if not math.isfinite(ids) or ids <= 0 or not math.isfinite(timeouts):
                    raise RuntimeError(f"Invalid measurement: {name}")
                result["jmh"].append({"profile": profile, "threads": threads, "successfulIdsPerSecond": ids,
                    "timeoutsPerSecond": timeouts, "attemptsPerSecond": record["primaryMetric"]["score"],
                    "bytesPerAttempt": secondary["gc.alloc.rate.norm"]["score"]})
        for trial in range(1, 4):
            print(f"Fixed rate 100k/s trial {trial}", flush=True)
            log = out / f"fixed-rate-{trial}.txt"
            run([java, "-Xms512m", "-Xmx512m", "-cp", str(JAR), "com.smartscity.id.benchmark.FixedRateProbe", "20"], log, remaining(40))
            records = [json.loads(line) for line in log.read_text().splitlines() if line.startswith("{")]
            if len(records) != 1 or records[0]["duplicates"] != 0:
                raise RuntimeError(f"Invalid fixed-rate report: {log}")
            result["fixedRate"].append(records[0])
        result["status"] = "MEASURED"
        result["all100kTargetsMet"] = all(trial["targetMet"] for trial in result["fixedRate"])
    finally:
        result["elapsedSeconds"] = round(time.monotonic() - start, 2)
        (out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
