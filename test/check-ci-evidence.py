#!/usr/bin/env python3
"""Require real database and restart suites to execute in the CI reactor, never pass by skipping."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
required = {'module/smartscity-id': {'RestartConcurrencyTest': 3}}
for generation in ('boot2-3', 'boot4'):
    required[f'starter/smartscity-starter-id/{generation}'] = {
        'MySqlIntegrationTest': 6,
        'PostgresIntegrationTest': 1,
        'RestartStabilityTest': 2,
        'ZookeeperRestartStabilityTest': 3,
        'ZookeeperIntegrationTest': 8,
    }
for module, suites in required.items():
    for name, count in suites.items():
        reports = list((root / module / 'target/surefire-reports').glob(f'TEST-*.{name}.xml'))
        assert len(reports) == 1, (module, name, 'missing or duplicate report')
        suite = ET.parse(reports[0]).getroot()
        totals = {key: int(suite.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
        assert totals == dict(tests=count, failures=0, errors=0, skipped=0), (module, name, totals)
        print('PASS', module, name, count, 'executed tests; no skips')
