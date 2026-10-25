#!/usr/bin/env python3
"""Build a pinned, isolated Baidu comparison; measurements require explicit --run."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import signal
import shutil
import subprocess
import time
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PIN = '2fcbc13d2016fcfb7648a18296951f6942215255'
JAR = ROOT / 'test/smartscity-id-benchmark/target/benchmarks.jar'
WORK = ROOT / 'test/smartscity-id-benchmark/target/baidu-comparison'
# Upstream versions are intentionally isolated from Smartscity dependency management.
DEPENDENCIES = [
    'org/springframework/spring-core/4.2.5.RELEASE/spring-core-4.2.5.RELEASE.jar',
    'org/springframework/spring-beans/4.2.5.RELEASE/spring-beans-4.2.5.RELEASE.jar',
    'commons-lang/commons-lang/2.6/commons-lang-2.6.jar',
    'org/slf4j/slf4j-api/1.7.7/slf4j-api-1.7.7.jar',
    'org/slf4j/slf4j-nop/1.7.7/slf4j-nop-1.7.7.jar',
]


def capture(command):
    return subprocess.check_output(command, cwd=ROOT, stderr=subprocess.STDOUT,
                                   text=True, timeout=15).strip()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, log, timeout):
    with log.open('w') as stream:
        stream.write(json.dumps([str(x) for x in command]) + '\n')
        stream.flush()
        process = subprocess.Popen(command, cwd=ROOT, stdout=stream,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
            if code:
                raise RuntimeError(f'Exit {code}: {log}')
        finally:
            for sig in (signal.SIGTERM, signal.SIGKILL):
                try:
                    os.killpg(process.pid, sig)
                    process.wait(timeout=3)
                except ProcessLookupError:
                    break
                except subprocess.TimeoutExpired:
                    pass


def inventory(upstream):
    files = sorted((ROOT / 'module/smartscity-id/src').rglob('*.java'))
    files += sorted(HERE.glob('*.java')) + [HERE / 'run.py']
    files += [ROOT / 'pom.xml', ROOT / 'build/smartscity-parent/pom.xml',
              ROOT / 'test/smartscity-id-benchmark/pom.xml']
    entries = {str(p.relative_to(ROOT)): sha(p) for p in files}
    entries.update({'baidu/' + str(p.relative_to(upstream)): sha(p)
                    for p in sorted((upstream / 'src/main/java').rglob('*.java'))})
    return entries


def main():
    global WORK, JAR
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--work-directory', type=Path, default=WORK)
    parser.add_argument('--smartscity-jar', type=Path, default=JAR)
    args = parser.parse_args()
    WORK = args.work_directory.resolve()
    JAR = args.smartscity_jar.resolve()
    upstream = args.upstream.resolve()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    if (out / 'environment.json').exists():
        parser.error('Use a fresh evidence directory; existing results are never overwritten')
    java_home = Path(os.environ['JAVA_HOME'])
    java = str(java_home / 'bin/java')
    if 'version "21.' not in capture([java, '-version']):
        parser.error('JAVA_HOME must select JDK 21')
    if capture(['git', '-C', str(upstream), 'rev-parse', 'HEAD']) != PIN:
        parser.error('Upstream checkout does not match pinned commit')
    if capture(['git', '-C', str(upstream), 'status', '--porcelain']):
        parser.error('Upstream checkout must be unmodified')
    if not JAR.exists():
        parser.error('Build the current benchmark JAR with the pinned Maven wrapper first')
    WORK.mkdir(parents=True, exist_ok=True)
    frozen = WORK / ('benchmarks-' + sha(JAR) + '.jar')
    if not frozen.exists():
        shutil.copy2(JAR, frozen)
    JAR = frozen
    deps = WORK / 'deps'
    deps.mkdir(exist_ok=True)
    libraries = []
    for relative in DEPENDENCIES:
        target = deps / Path(relative).name
        url = 'https://repo.maven.apache.org/maven2/' + relative
        if not target.exists():
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read()
            with urllib.request.urlopen(url + '.sha1', timeout=30) as response:
                expected = response.read().decode().strip().split()[0]
            if hashlib.sha1(data).hexdigest() != expected:
                raise RuntimeError(f'Download checksum mismatch: {url}')
            target.write_bytes(data)
        libraries.append(target)
    classes = WORK / 'classes'
    if classes.exists():
        # Use a new compilation directory to prevent stale classes or JMH metadata.
        classes = WORK / ('classes-' + str(time.time_ns()))
    classes.mkdir()
    upstream_root = upstream / 'src/main/java/com/baidu/fsg/uid'
    sources = [upstream_root / name for name in [
        'BitsAllocator.java', 'UidGenerator.java', 'worker/WorkerIdAssigner.java',
        'utils/DateUtils.java', 'utils/NamingThreadFactory.java', 'utils/PaddedAtomicLong.java']]
    for directory in ('impl', 'buffer', 'exception'):
        sources += sorted((upstream_root / directory).glob('*.java'))
    cp = os.pathsep.join(str(p) for p in [classes, JAR] + libraries)
    run([str(java_home / 'bin/javac'), '--release', '8', '-proc:none', '-cp', cp,
         '-d', str(classes)] + [str(p) for p in sources], out / 'compile-upstream.txt', 60)
    processor = Path.home() / '.m2/repository/org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar'
    run([str(java_home / 'bin/javac'), '--release', '9', '-cp', cp,
         '-processorpath', str(processor) + os.pathsep + str(JAR),
         '-processor', 'org.openjdk.jmh.generators.BenchmarkProcessor',
         '-d', str(classes)] + [str(p) for p in sorted(HERE.glob('*.java'))],
        out / 'compile-harness.txt', 60)
    environment = {'os': platform.platform(), 'logicalCpus': os.cpu_count(),
                   'java': capture([java, '-version']), 'upstreamCommit': PIN,
                   'baseCommit': capture(['git', 'rev-parse', 'HEAD']),
                   'gitStatus': capture(['git', 'status', '--short']),
                   'jarSha256': sha(JAR), 'processorSha256': sha(processor),
                   'libraries': {p.name: sha(p) for p in libraries},
                   'compiledUpstreamSources': [str(p.relative_to(upstream)) for p in sources],
                   'classSha256': {str(p.relative_to(classes)): sha(p) for p in classes.rglob('*.class')},
                   'jvmOptions': ['-Xms512m', '-Xmx512m', '-Duser.timezone=UTC'],
                   'jmh': {'forks': 2, 'warmup': '2 x 1s', 'measurement': '3 x 2s',
                           'profiler': 'gc', 'threads': [1, 4, 8]},
                   'measurementBudgetSeconds': 480, 'storage': 'No storage/network on generation path',
                   'classpath': cp}
    if platform.system() == 'Darwin':
        for key in ('machdep.cpu.brand_string', 'hw.memsize', 'hw.ncpu'):
            environment[key] = capture(['sysctl', '-n', key])
    (out / 'environment.json').write_text(json.dumps(environment, indent=2) + '\n')
    initial = inventory(upstream)
    (out / 'sources-before.json').write_text(json.dumps(initial, indent=2) + '\n')
    run([java, '-cp', cp, 'org.openjdk.jmh.Main', '-l'], out / 'discovery.txt', 20)
    result = {'status': 'NOT_RUN', 'jmh': [], 'probes': [], 'errors': []}
    start = time.monotonic()

    def remaining(limit):
        budget = min(limit, 480 - (time.monotonic() - start))
        if budget <= 0:
            raise TimeoutError('Measurement budget exhausted')
        return budget

    def save():
        result['elapsedSeconds'] = round(time.monotonic() - start, 2)
        (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')

    try:
        if not args.run:
            return
        result['status'] = 'PARTIAL'
        # Rotate ordering between profiles/thread counts to reduce fixed order bias.
        implementations = ['smartscity', 'baidu-default', 'baidu-cached']
        for p_index, profile in enumerate(('standard', 'high-throughput')):
            for t_index, threads in enumerate((1, 4, 8)):
                shift = (p_index + t_index) % 3
                for impl in implementations[shift:] + implementations[:shift]:
                    name = f'{profile}-{threads}-{impl}'
                    print('JMH ' + name, flush=True)
                    raw = out / (name + '.json')
                    run([java, '-cp', cp, 'org.openjdk.jmh.Main', 'ComparisonBenchmark.generate',
                         '-p', f'implementation={impl}', '-p', f'profile={profile}', '-t', str(threads),
                         '-f', '2', '-wi', '2', '-w', '1s', '-i', '3', '-r', '2s',
                         '-foe', 'true', '-prof', 'gc', '-rf', 'json', '-rff', str(raw)],
                        out / (name + '.txt'), remaining(45))
                    records = json.loads(raw.read_text())
                    if len(records) != 1:
                        raise RuntimeError(f'Incomplete JMH output: {name}')
                    record = records[0]
                    if record['forks'] != 2 or len(record['primaryMetric']['rawData']) != 2:
                        raise RuntimeError(f'Incomplete forks: {name}')
                    metrics = record['secondaryMetrics']
                    values = [record['primaryMetric']['score']] + [metrics[k]['score'] for k in
                              ('ids', 'timeouts', 'rejections', 'gc.alloc.rate.norm')]
                    if not all(math.isfinite(v) and v >= 0 for v in values) or values[1] <= 0:
                        raise RuntimeError(f'Invalid metrics: {name}')
                    result['jmh'].append({'profile': profile, 'threads': threads, 'implementation': impl,
                        'attemptsPerSecond': values[0], 'idsPerSecond': values[1],
                        'timeoutsPerSecond': values[2], 'rejectionsPerSecond': values[3],
                        'bytesPerAttempt': values[4],
                        'estimatedBytesPerSuccess': values[4] * values[0] / values[1],
                        'idsScoreError999': metrics['ids']['scoreError'],
                        'allocationMiBPerSecond': metrics['gc.alloc.rate']['score']})
                    save()
        for impl in implementations:
            log = out / f'probe-{impl}.txt'
            try:
                run([java, '-Xms512m', '-Xmx512m', '-Duser.timezone=UTC', '-cp', cp,
                     'com.smartscity.id.benchmark.comparison.ComparisonProbe', impl], log, remaining(35))
                status = 'PASS'
            except RuntimeError as error:
                status = 'FAIL'
                result['errors'].append(str(error))
            rows = [json.loads(line) for line in log.read_text().splitlines() if line.startswith('{')]
            result['probes'].append({'status': status, 'implementation': impl, 'data': rows})
            save()
        result['status'] = 'MEASURED'
    except BaseException as error:
        result['errors'].append(repr(error))
        raise
    finally:
        after = inventory(upstream)
        (out / 'sources-after.json').write_text(json.dumps(after, indent=2) + '\n')
        result['sourceUnchanged'] = initial == after and sha(JAR) == environment['jarSha256']
        if not result['sourceUnchanged']:
            result['status'] = 'NOT_COMPARABLE'
        save()


if __name__ == '__main__':
    main()
