#!/usr/bin/env python3
"""Run a bounded observability experiment and retain raw evidence, including failures."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / 'test/smartscity-observability-benchmark/target/benchmarks.jar'


def command(args, log, timeout):
    with log.open('w') as stream:
        process = subprocess.Popen(args, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            import signal
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            stream.write('\nTIMEOUT: experiment process group terminated\n')
            return 124


def output(args):
    return subprocess.check_output(args, cwd=ROOT, text=True, stderr=subprocess.STDOUT).strip()


def report(directory, smoke, codes):
    rows = json.loads((directory / 'jmh.json').read_text()) if (directory / 'jmh.json').exists() else []
    udp = [json.loads(p.read_text()) for p in sorted(directory.glob('udp-*.json'))]
    errors = []
    expected = set()
    prefix = 'com.smartscity.observability.benchmark.'
    for method in ('ac', 'sequentialReplace'):
        for dictionary in ('64', '1024'):
            for size in ('1024', '8192'):
                for match in ('HIT', 'MISS'):
                    expected.add((prefix + 'LiteralMatchBenchmark.' + method,
                                  tuple(sorted(dict(dictionarySize=dictionary, inputSize=size, match=match).items()))))
    for method in ('structuredMask', 'pipeEncode', 'logWithMdc'):
        for size in ('1024', '8192'):
            expected.add((prefix + 'PipelineBenchmark.' + method, (('inputSize', size),)))
    for method in ('root', 'clientChild'):
        expected.add((prefix + 'CorrelationBenchmark.' + method, ()))
    observed = {(r['benchmark'], tuple(sorted(r.get('params', {}).items()))) for r in rows}
    if observed != expected or len(rows) != 24:
        errors.append('Incomplete or duplicate JMH scenario matrix')
    for r in rows:
        metric = r['primaryMetric']
        if (r['mode'] != 'thrpt' or metric['scoreUnit'] != 'ops/s'
                or not math.isfinite(metric['score']) or metric['score'] <= 0
                or r['forks'] != (1 if smoke else 2)
                or r['warmupIterations'] != (1 if smoke else 3)
                or r['measurementIterations'] != (1 if smoke else 5)
                or r['threads'] != 1
                or len(metric['rawData']) != r['forks']
                or any(len(fork) != r['measurementIterations'] for fork in metric['rawData'])
                or 'gc.alloc.rate.norm' not in r['secondaryMetrics']):
            errors.append('Invalid JMH evidence: ' + r['benchmark'])
    if len(udp) != 6 or sorted(r['target_rate'] for r in udp) != [10000, 10000, 50000, 50000, 100000, 100000]:
        errors.append('Incomplete UDP matrix')
    for r in udp:
        if (r['cleanup'] != 'PASS' or r['attempts'] != r['dropped'] + r['locally_written']
                or r['locally_written'] != r['received'] + r['delivery_gap']):
            errors.append('Invalid UDP accounting or cleanup')
    if any(code != 0 for code in codes.values()):
        errors.append('Experiment process failed')
    status = 'FAIL' if errors else ('SMOKE' if smoke else 'MEASURED')
    summary = dict(status=status, errors=errors, return_codes=codes, jmh_cases=len(rows), udp_trials=len(udp),
                   scope='Single shared host, one JMH thread; logging sink excludes filesystem/network I/O. UDP is loopback, not collector throughput or delivery assurance.')
    (directory / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    lines = ['# Observability performance evidence', '', 'Status: **' + status + '**.', '', summary['scope'], '',
             'Throughput is operations/second; allocation is normalized bytes/operation. JMH confidence intervals and per-fork data remain in jmh.json. Admission percentiles are sampled call costs, not network latency.', '',
             '| Scenario | Parameters | ops/s | B/op |', '|---|---|---:|---:|']
    for r in rows:
        alloc = r['secondaryMetrics'].get('gc.alloc.rate.norm', {}).get('score', float('nan'))
        lines.append(f"| {r['benchmark'].removeprefix(prefix)} | {r.get('params', {})} | {r['primaryMetric']['score']:.1f} | {alloc:.1f} |")
    lines += ['', '## Matched literal comparison', '', 'Ratio above one favors AC. Both methods assert equal output before each trial; these synthetic dictionary/input distributions do not establish a universal speedup.', '',
              '| Dictionary | Input | Match | AC / sequential throughput |', '|---:|---:|---|---:|']
    lookup = {(r['benchmark'].split('.')[-1], tuple(sorted(r.get('params', {}).items()))): r for r in rows}
    for r in rows:
        if r['benchmark'].endswith('LiteralMatchBenchmark.ac'):
            p = r['params']
            other = lookup.get(('sequentialReplace', tuple(sorted(p.items()))))
            if other:
                ratio = r['primaryMetric']['score'] / other['primaryMetric']['score']
                lines.append(f"| {p['dictionarySize']} | {p['inputSize']} | {p['match']} | {ratio:.2f}x |")
    lines += ['', '## UDP trials', '', 'Queue capacity 8192 lines; payload limit 1400 bytes; one producer, one sender, one receiver. Each trial warms for two seconds separately. Received counts include the bounded post-close settle period.', '',
              '| Target/s | Offered/s | Local drops | Delivery gap | Received | Offer p50 / p99 ns | Drain ms | Load |', '|---:|---:|---:|---:|---:|---:|---:|---|']
    for r in udp:
        lines.append(f"| {r['target_rate']} | {r['offered_rate']:.0f} | {r['dropped']} | {r['delivery_gap']} | {r['received']} | {r['offer_p50_ns']} / {r['offer_p99_ns']} | {r['drain_ns']/1e6:.2f} | {r['load_status']} |")
    lines += ['', 'UNDERDRIVEN means the producer did not reach 95% of the requested rate. Drops and delivery gaps remain measurements, not hidden failures or proof of reliable UDP. No production capacity or SLA is inferred.', '']
    if errors:
        lines += ['Errors: ' + '; '.join(errors), '']
    (directory / 'report.md').write_text('\n'.join(lines))
    return not errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--smoke', action='store_true')
    args = parser.parse_args()
    directory = args.output.resolve()
    directory.mkdir(parents=True, exist_ok=False)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
    env = dict(timestamp=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), platform=platform.platform(),
               cpu_count=os.cpu_count(), machine=platform.machine(), java=output([java, '-version']),
               revision=output(['git', 'rev-parse', 'HEAD']), dirty=bool(output(['git', 'status', '--porcelain'])),
               runner=os.environ.get('RUNNER_NAME', 'local'), github_run_id=os.environ.get('GITHUB_RUN_ID'),
               benchmark_sha256=hashlib.sha256(JAR.read_bytes()).hexdigest(), smoke=args.smoke)
    cpuinfo = Path('/proc/cpuinfo')
    env['cpu'] = next((line for line in cpuinfo.read_text().splitlines() if line.startswith('model name')), '') if cpuinfo.exists() else platform.processor()
    env['artifacts'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                        for base in ('module/smartscity-masking', 'starter/smartscity-starter-logging/boot2-3', 'starter/smartscity-starter-metrics/boot2-3')
                        for p in (ROOT / base / 'target').glob('*-SNAPSHOT.jar')}
    (directory / 'environment.json').write_text(json.dumps(env, indent=2) + '\n')
    command([str(ROOT / 'mvnw'), '-version'], directory / 'maven.log', 30)
    cmd = [java, '-jar', str(JAR), 'com.smartscity.observability.benchmark.*Benchmark',
           '-f', '1' if args.smoke else '2', '-wi', '1' if args.smoke else '3', '-i', '1' if args.smoke else '5',
           '-w', '100ms' if args.smoke else '1s', '-r', '100ms' if args.smoke else '1s', '-t', '1',
           '-prof', 'gc', '-jvmArgs', '-Xms256m -Xmx512m', '-rf', 'json', '-rff', str(directory / 'jmh.json'), '-foe', 'true']
    (directory / 'commands.json').write_text(json.dumps({'jmh': cmd}, indent=2) + '\n')
    codes = {'jmh': command(cmd, directory / 'jmh.log', 900)}
    if codes['jmh'] == 0:
        codes['udp'] = command([java, '-cp', str(JAR), 'com.smartscity.observability.benchmark.UdpLoadProbe',
                                str(directory), '1' if args.smoke else '8'], directory / 'udp.log', 180)
    else:
        codes['udp'] = -1
    raise SystemExit(0 if report(directory, args.smoke, codes) else 1)


if __name__ == '__main__':
    main()
