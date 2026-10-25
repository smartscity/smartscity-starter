#!/usr/bin/env python3
"""Verify production boundaries, registration, metadata and bytecode from actual JARs."""
from pathlib import Path
import hashlib
import json
import struct
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
modules = {
    'module/smartscity-id': ('smartscity-id', 53),
    'starter/smartscity-starter-id/boot2-3': ('smartscity-starter-id', 53),
    'starter/smartscity-starter-id/boot4': ('smartscity-starter-id-boot4', 61),
}
expected_imports = {f'com.smartscity.id.autoconfigure.{name}' for name in
                    ('IdAutoConfiguration', 'IdHealthAutoConfiguration', 'IdMetricsAutoConfiguration')}
for directory, (module, major) in modules.items():
    pom = ET.parse(root / directory / 'pom.xml')
    for dep in pom.findall('m:dependencies/m:dependency', ns):
        if dep.findtext('m:scope', default='compile', namespaces=ns) == 'test':
            continue
        artifact = dep.findtext('m:artifactId', namespaces=ns)
        assert directory != 'module/smartscity-id', 'Domain must have zero third-party or project dependencies'
        assert not any(x in artifact for x in ('testkit', 'benchmark', 'compatibility-tests'))
    version = pom.findtext('m:version', namespaces=ns) or pom.findtext('m:parent/m:version', namespaces=ns)
    jar = root / directory / 'target' / (module + '-' + version + '.jar')
    with zipfile.ZipFile(jar) as archive:
        classes = [n for n in archive.namelist() if n.endswith('.class')]
        assert classes, 'Every production JAR must contain its owned implementation'
        for name in classes:
            data = archive.read(name)
            assert struct.unpack('>H', data[6:8])[0] == major, (name, 'wrong bytecode baseline')
            forbidden = [b'sun/misc/Unsafe', b'jdk/internal/']
            if directory == 'module/smartscity-id':
                forbidden += [b'org/springframework/', b'org/apache/zookeeper/', b'java/lang/reflect/']
            elif directory.endswith('/boot4'):
                forbidden += [b'org/springframework/boot/actuate/health/']
            elif directory.endswith('/boot2-3'):
                forbidden += [b'org/springframework/boot/health/']
            for entry in forbidden:
                assert entry not in data, (name, entry)
        if directory.startswith('starter/'):
            metadata = json.loads(archive.read('META-INF/spring-configuration-metadata.json'))
            defaults = {p['name']: p.get('defaultValue') for p in metadata['properties']}
            assert defaults['smartscity.id.database.use-application-data-source'] is False
            assert all('smartscity.id.instance.' + name in defaults for name in ('id', 'application-name', 'host-ip', 'host-port', 'hostname'))
            assert defaults['smartscity.id.epoch'] == '2026-01-01T00:00:00Z'
            assert all('smartscity.id.bits.' + name in defaults for name in ('timestamp', 'worker', 'sequence'))
            assert defaults['smartscity.id.wait-timeout'] == '10ms'
            assert defaults['smartscity.id.buffer-size'] == 16384
            assert defaults['smartscity.id.low-water-percent'] == 50
            assert all(p['name'].startswith('smartscity.id.') for p in metadata['properties'])
            imports = set(archive.read('META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports').decode().splitlines())
            assert imports == expected_imports
            for entry in imports:
                assert entry.replace('.', '/') + '.class' in classes
            assert 'META-INF/spring-autoconfigure-metadata.properties' in archive.namelist()
            assert all('db/' + dialect + '.sql' in archive.namelist() for dialect in ('mysql', 'postgresql', 'tidb'))
            assert any('autoconfigure/IdGeneratorFactory.class' in name for name in classes)
    print(f'PASS {module}: {len(classes)} classes; SHA256={hashlib.sha256(jar.read_bytes()).hexdigest()}')
bom = ET.parse(root / 'build/smartscity-dependencies/pom.xml')
assert bom.find('m:parent', ns) is None, 'BOM must not inherit external dependency management'
managed = bom.findall('m:dependencyManagement/m:dependencies/m:dependency', ns)
assert {d.findtext('m:artifactId', namespaces=ns) for d in managed} == {artifact for artifact, _ in modules.values()}
assert all(d.findtext('m:groupId', namespaces=ns) == 'com.smartscity' for d in managed)
print('PASS production dependency boundaries, generation isolation, BOM, registration and metadata')
