#!/usr/bin/env python3
"""Run installed adapters against independent Boot BOMs using a private MySQL process."""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

# Reuse the existing owned-process cleanup and bounded command execution.
import importlib.util
spec = importlib.util.spec_from_file_location('isolated', Path(__file__).with_name('run-destructive.py'))
isolated = importlib.util.module_from_spec(spec)
spec.loader.exec_module(isolated)
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java', type=Path, action='append', required=True, help='Runtime JDK home; repeat for each installed JDK')
    args = parser.parse_args()
    if not os.environ.get('JAVA_HOME') or not shutil.which('mysqld') or not shutil.which('mysql'):
        parser.error('Need build JAVA_HOME (21), mysqld and mysql; install production artifacts first')
    for java in args.java:
        if not (java / 'bin/java').is_file(): parser.error(f'No java in {java}')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    status = {'status': 'PARTIAL', 'matrix': [], 'samples': [], 'cleanup': False}
    server = None
    port = None
    with tempfile.TemporaryDirectory(prefix='starter-compat-') as temporary:
        work = Path(temporary)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        status['isolatedPort'] = port
        data, unix = work / 'data', work / 'mysql.sock'
        data.mkdir()
        try:
            isolated.run(['mysqld', '--no-defaults', f'--datadir={data}', '--initialize-insecure'], out / 'initialize.txt', 60)
            with (out / 'mysql.txt').open('w') as log:
                server = subprocess.Popen(['mysqld', '--no-defaults', f'--datadir={data}', f'--socket={unix}',
                    f'--port={port}', '--bind-address=127.0.0.1', '--mysqlx=OFF', f'--pid-file={work / "mysql.pid"}'],
                    stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            deadline = time.monotonic() + 30
            while not unix.exists():
                if server.poll() is not None or time.monotonic() > deadline: raise RuntimeError('Private MySQL did not start')
                time.sleep(.1)
            isolated.run(['mysql', '--no-defaults', '-uroot', f'--socket={unix}', '-e',
                'CREATE DATABASE smartscity_id_test'], out / 'schema.txt', 10)
            url = f'jdbc:mysql://127.0.0.1:{port}/smartscity_id_test?useSSL=false&allowPublicKeyRetrieval=true'
            for java in args.java:
                runtime = subprocess.check_output([str(java / 'bin/java'), '-version'], stderr=subprocess.STDOUT, text=True).splitlines()[0]
                for boot in ('2.7.18', '3.3.6', '3.3.13', '3.4.13', '3.5.16', '4.0.9', '4.1.2'):
                    name = f'boot-{boot}-java-{java.name}'
                    isolated.run([str(ROOT / 'mvnw'), '-B', '-ntp', '-f', 'test/smartscity-id-compatibility-tests/pom.xml',
                        '-Pboot4' if boot.startswith('4.') else '-Pboot3', f'-Dspring-boot.version={boot}',
                        f'-Djvm={java / "bin/java"}', f'-Did.test.mysql.url={url}', 'clean', 'test'], out / (name + '.txt'), 180)
                    report = ET.parse(ROOT / 'test/smartscity-id-compatibility-tests/target/surefire-reports/TEST-com.smartscity.id.compatibility.StarterDiscoveryTest.xml').getroot()
                    counts = {k: int(report.attrib[k]) for k in ('tests', 'failures', 'errors', 'skipped')}
                    if counts['tests'] < 8 or any(counts[k] for k in ('failures', 'errors', 'skipped')): raise RuntimeError(f'Incomplete {name}: {counts}')
                    status['matrix'].append({'boot': boot, 'runtime': runtime, **counts})
                    print(f'PASS {name}: {counts}', flush=True)
            # Execute actual packaged applications against the schema prepared by the consumer tests.
            for directory in ('id-example', 'id-example-boot4'):
                jar = ROOT / f'samples/id/{directory}/target/smartscity-{directory}-0.1.0-SNAPSHOT.jar'
                log = out / (directory + '.txt')
                isolated.run([str(Path(os.environ['JAVA_HOME']) / 'bin/java'), '-jar', str(jar),
                    f'--smartscity.id.database.url={url}', '--smartscity.id.database.username=root',
                    '--smartscity.id.database.password=', '--spring.main.banner-mode=off'], log, 30)
                if 'ID=' not in log.read_text() or 'ready=true' not in log.read_text(): raise RuntimeError(f'Missing sample output: {log}')
                status['samples'].append({'sample': directory, 'status': 'PASS'})
            status['status'] = 'PASS'
        finally:
            isolated.stop(server)
            with socket.socket() as sock:
                sock.settimeout(.2)
                status['cleanup'] = sock.connect_ex(('127.0.0.1', port)) != 0
            if not status['cleanup']: status['status'] = 'FAIL'
            (out / 'result.json').write_text(json.dumps(status, indent=2) + '\n')
    if status['status'] != 'PASS': raise RuntimeError(status)
    print(json.dumps(status), flush=True)


if __name__ == '__main__':
    main()
