#!/usr/bin/env python3
"""Reject snapshot/mismatched release versions before loading publishing credentials."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

requested = sys.argv[1] if len(sys.argv) == 2 else ''
if not re.fullmatch(r'\d+\.\d+\.\d+(?:-(?:RC|M)\d+)?', requested):
    raise SystemExit('Supply an exact non-SNAPSHOT release version, such as 0.1.0 or 0.1.0-RC1')
root = Path(__file__).resolve().parents[1]
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
for pom in sorted(p for p in root.rglob('pom.xml') if not any(part in ('target', '.git', 'evidence') for part in p.parts)):
    model = ET.parse(pom)
    declared = model.findtext('m:version', namespaces=ns) or model.findtext('m:parent/m:version', namespaces=ns)
    if declared != requested:
        raise SystemExit(f'{pom.relative_to(root)} has {declared}, expected {requested}')
print(f'PASS release POM versions: {requested}')
