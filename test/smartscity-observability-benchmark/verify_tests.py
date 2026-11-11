#!/usr/bin/env python3
"""Reject missing test reports and unexpected skips in the observability CI matrix."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
mode = sys.argv[1]
if mode == 'contracts':
    paths = ['module/smartscity-observability', 'starter/smartscity-starter-observability/boot2-3',
             'starter/smartscity-starter-observability/boot4']
    expected = (47, 0)
elif mode in ('boot2', 'boot3', 'boot4'):
    paths = ['test/smartscity-observability-compatibility-tests']
    expected = (6, 1 if mode == 'boot2' else 0)
else:
    raise SystemExit('Unknown verification mode: ' + mode)
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for path in paths:
    reports = list((root / path / 'target/surefire-reports').glob('TEST-*.xml'))
    assert reports, f'Missing test reports: {path}'
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, '0'))
assert (totals['tests'], totals['skipped']) == expected, (mode, totals, expected)
assert totals['failures'] == totals['errors'] == 0, totals
print('PASS', mode, totals)
