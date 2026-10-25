#!/usr/bin/env python3
"""Compare frozen before, future-time-only, optimized SDKs and the pinned Baidu cache."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import time
import run as support


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', action='store_true', required=True)
    parser.add_argument('--upstream', required=True, type=Path)
    parser.add_argument('--variants', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    if (out / 'result.json').exists():
        parser.error('Use a fresh evidence directory')
    variants = args.variants.resolve()
    artifacts = {label: variants / label / 'sdk.jar' for label in ('before', 'future-only', 'optimized')}
    artifacts['baidu-cached'] = artifacts['optimized']
    for jar in artifacts.values():
        if not jar.is_file():
            parser.error(f'Missing frozen SDK: {jar}')
    prep = out / 'prepare'
    command = [sys.executable, str(support.HERE / 'run.py'), '--upstream', str(args.upstream),
               '--output', str(prep), '--work-directory', str(variants / 'harness'),
               '--smartscity-jar', str(variants / 'before/benchmarks.jar')]
    support.run(command, out / 'prepare.txt', 120)
    environment = json.loads((prep / 'environment.json').read_text())
    common = environment['classpath'].split(os.pathsep)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
    artifact_hashes = {k: support.sha(v) for k, v in artifacts.items()}
    harness_hashes = {str(p): support.sha(p) for p in support.HERE.glob('*.java')}
    environment['variants'] = artifact_hashes
    environment['harnessSources'] = harness_hashes
    environment['measurementBudgetSeconds'] = 480
    environment['scope'] = 'Three controlled Smartscity variants plus unchanged Baidu Cached; local bounded runs'
    (out / 'environment.json').write_text(json.dumps(environment, indent=2) + '\n')
    result = {'status': 'PARTIAL', 'jmh': [], 'probes': [], 'errors': []}
    start = time.monotonic()

    def save():
        result['elapsedSeconds'] = round(time.monotonic() - start, 2)
        (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')

    def budget(maximum):
        remaining = min(maximum, 480 - (time.monotonic() - start))
        if remaining <= 0:
            raise TimeoutError('Measurement budget exhausted')
        return remaining

    def classpath(variant):
        return os.pathsep.join([common[0], str(artifacts[variant])] + common[1:])

    try:
        for profile in ('standard', 'high-throughput'):
            labels = ['before', 'future-only', 'optimized', 'baidu-cached'] if profile == 'standard' else ['optimized', 'baidu-cached']
            for index, threads in enumerate((1, 4, 8)):
                shift = index % len(labels)
                for variant in labels[shift:] + labels[:shift]:
                    implementation = 'baidu-cached' if variant == 'baidu-cached' else 'smartscity'
                    name = f'{profile}-{threads}-{variant}'
                    print('JMH ' + name, flush=True)
                    raw = out / (name + '.json')
                    support.run([java, '-cp', classpath(variant), 'org.openjdk.jmh.Main',
                        'ComparisonBenchmark.generate', '-p', f'implementation={implementation}',
                        '-p', f'profile={profile}', '-t', str(threads), '-f', '2',
                        '-wi', '2', '-w', '1s', '-i', '3', '-r', '2s', '-foe', 'true',
                        '-prof', 'gc', '-rf', 'json', '-rff', str(raw)], out / (name + '.txt'), budget(45))
                    records = json.loads(raw.read_text())
                    if len(records) != 1:
                        raise RuntimeError(f'Missing results: {name}')
                    record = records[0]
                    primary = record['primaryMetric']
                    secondary = record['secondaryMetrics']
                    if len(primary['rawData']) != 2 or any(len(row) != 3 for row in primary['rawData']):
                        raise RuntimeError(f'Incomplete samples: {name}')
                    values = {k: secondary[k]['score'] for k in ('ids', 'timeouts', 'rejections', 'gc.alloc.rate.norm', 'gc.alloc.rate')}
                    if any(not math.isfinite(v) or v < 0 for v in values.values()) or values['ids'] <= 0:
                        raise RuntimeError(f'Invalid metrics: {name}')
                    total = values['ids'] + values['timeouts'] + values['rejections']
                    discrepancy = abs(total / primary['score'] - 1)
                    # Control flags exclude JMH synchronization calls; require matching timed-loop counts.
                    if discrepancy > 0.00001:
                        raise RuntimeError(f'Timed-loop outcome mismatch: {name}: {discrepancy}')
                    result['jmh'].append({'profile': profile, 'threads': threads, 'variant': variant,
                        'attemptsPerSecond': primary['score'], 'idsPerSecond': values['ids'],
                        'timeoutsPerSecond': values['timeouts'], 'rejectionsPerSecond': values['rejections'],
                        'bytesPerAttempt': values['gc.alloc.rate.norm'],
                        'estimatedBytesPerSuccess': values['gc.alloc.rate.norm'] * primary['score'] / values['ids'],
                        'allocationMiBPerSecond': values['gc.alloc.rate'],
                        'idsScoreError999': secondary['ids']['scoreError'], 'outcomeDiscrepancy': discrepancy})
                    save()
        for variant in ('before', 'future-only', 'optimized', 'baidu-cached'):
            implementation = 'baidu-cached' if variant == 'baidu-cached' else 'smartscity'
            log = out / f'probe-{variant}.txt'
            support.run([java, '-Xms512m', '-Xmx512m', '-Duser.timezone=UTC', '-cp', classpath(variant),
                         'com.smartscity.id.benchmark.comparison.ComparisonProbe', implementation], log, budget(35))
            rows = [json.loads(line) for line in log.read_text().splitlines() if line.startswith('{')]
            if len(rows) != 1 or rows[0]['duplicates']:
                raise RuntimeError(f'Invalid uniqueness probe: {variant}')
            result['probes'].append({'variant': variant, 'status': 'PASS', **rows[0]})
            save()
        result['status'] = 'MEASURED'
    except BaseException as error:
        result['errors'].append(repr(error))
        raise
    finally:
        result['frozenArtifactsUnchanged'] = all(support.sha(artifacts[k]) == v for k, v in artifact_hashes.items())
        result['harnessUnchanged'] = all(support.sha(Path(p)) == v for p, v in harness_hashes.items())
        if not result['frozenArtifactsUnchanged'] or not result['harnessUnchanged']:
            result['status'] = 'NOT_COMPARABLE'
        save()


if __name__ == '__main__':
    main()
