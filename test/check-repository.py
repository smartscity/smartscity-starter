#!/usr/bin/env python3
"""Check maintained documentation links, locale navigation and reactor source layout."""
from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
errors = []
for directory in ('build', 'core', 'module', 'starter', 'test', 'samples', 'docs', '.github'):
    assert (root / directory).is_dir(), directory
assert not list(root.glob('smartscity-*/pom.xml'))
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
for pom in sorted(p for p in root.rglob('pom.xml') if not any(part in ('target', '.git', 'evidence') for part in p.parts)):
    model = ET.parse(pom)
    for tag in ('sourceDirectory', 'testSourceDirectory'):
        assert model.find('.//m:' + tag, ns) is None, (pom, 'custom source root')
    for plugin in model.findall('.//m:plugin', ns):
        assert plugin.findtext('m:artifactId', namespaces=ns) != 'build-helper-maven-plugin', pom
    for module in model.findall('.//m:modules/m:module', ns):
        assert (pom.parent / module.text / 'pom.xml').is_file(), (pom, module.text)
for path in root.rglob('*'):
    if not path.is_file() or any(part in ('.git', 'target', '.build-cache') for part in path.parts):
        continue
    if path.suffix not in ('.md', '.java', '.py', '.sh', '.yml', '.xml'): continue
    if 'evidence' in path.parts: continue  # Raw historical output is immutable.
    text = path.read_text()
    if path.suffix == '.java' and '@author jack' not in text:
        errors.append(f'Missing class Javadoc author: {path.relative_to(root)}')
    if path.suffix == '.java' and re.search(r'^import .*\.\*;', text, re.M):
        errors.append(f'Wildcard Java import: {path.relative_to(root)}')
    if re.search(r'[\u4e00-\u9fff]', text): errors.append(f'Non-English maintained file: {path.relative_to(root)}')
    if path.suffix == '.md':
        for target in re.findall(r'(?<!!)\[[^\]]*\]\(([^)]+)\)', text):
            target = target.split('#')[0]
            if not target or '://' in target or target.startswith('mailto:'): continue
            if not (path.parent / target).exists(): errors.append(f'{path.relative_to(root)} -> missing {target}')
for component_root in ('module', 'starter'):
    for pom in (root / component_root).rglob('pom.xml'):
        if 'target' in pom.parts: continue
        assert (pom.parent / 'README.md').is_file(), pom
        assert not (pom.parent / 'USER_GUIDE.md').exists(), 'Usage belongs to docs/<component>'
        for path in (pom.parent / 'src').glob('*'):
            assert path.name in ('main', 'test'), (path, 'nonstandard source tree')
for name in ('README.md', 'USER_GUIDE.md', 'compatibility.md', 'migration.md', 'testing.md'):
    assert (root / 'docs/id' / name).is_file(), name
family = root / 'starter/smartscity-starter-id'
family_pom = ET.parse(family / 'pom.xml')
assert family_pom.findtext('m:artifactId', namespaces=ns) == 'smartscity-starter-id-family'
assert family_pom.findtext('m:packaging', namespaces=ns) == 'pom'
assert family_pom.findtext('m:properties/m:maven.deploy.skip', namespaces=ns) == 'true'
assert family_pom.findtext("m:profiles/m:profile[m:id='release']/m:properties/m:maven.deploy.skip", namespaces=ns) == 'true'
for generation in ('boot2-3', 'boot4'):
    model = ET.parse(family / generation / 'pom.xml')
    assert model.findtext('m:parent/m:artifactId', namespaces=ns) == 'smartscity-parent'
    assert not any('spring-boot4' in d.findtext('m:artifactId', default='', namespaces=ns)
                   or 'id-spring-boot' in d.findtext('m:artifactId', default='', namespaces=ns)
                   for d in model.findall('m:dependencies/m:dependency', ns))
# Standard source trees are intentionally mirrored, except the framework health API.
left, right = family / 'boot2-3/src', family / 'boot4/src'
left_files = {p.relative_to(left) for p in left.rglob('*') if p.is_file()}
right_files = {p.relative_to(right) for p in right.rglob('*') if p.is_file()}
assert left_files == right_files, 'Starter source/resource/test inventory drift'
for relative in left_files:
    if relative.as_posix() == 'main/java/com/smartscity/id/autoconfigure/IdHealthAutoConfiguration.java': continue
    assert (left / relative).read_bytes() == (right / relative).read_bytes(), ('Starter generation drift', relative)
for source in (root / 'module').rglob('*.java'):
    if 'target' in source.parts or 'test' in source.parts: continue
    assert not re.search(r'^import (org\.springframework|org\.apache\.zookeeper|javax\.sql)\.', source.read_text(), re.M), source
assert not [p for p in root.rglob('.gitkeep') if '.git' not in p.parts and 'target' not in p.parts]
nav = json.loads((root / 'docs/navigation.json').read_text())
assert nav['defaultLocale'] == 'en' and list(nav['locales']) == ['en']
for paths in nav['sections'].values():
    for path in paths:
        assert (root / 'docs' / path).is_file(), path
if errors: raise SystemExit('\n'.join(errors))
print('PASS responsibility layout, reactor paths, English maintained files and documentation navigation/links')
