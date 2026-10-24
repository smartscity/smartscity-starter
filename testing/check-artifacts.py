#!/usr/bin/env python3
"""Checks production boundaries/metadata/bytecode. Does not run a benchmark."""
from pathlib import Path
import hashlib
import json
import struct
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
modules = ["api", "spi", "core", "spring-boot-autoconfigure", "spring-boot-starter"]
allowed = {"api": set(), "spi": {"smartscity-id-api"}, "core": {"smartscity-id-api", "smartscity-id-spi"}}
for short in modules:
    module = "smartscity-id-" + short
    pom = ET.parse(root / module / "pom.xml")
    for dep in pom.findall("m:dependencies/m:dependency", ns):
        if dep.findtext("m:scope", default="compile", namespaces=ns) == "test":
            continue
        if short in allowed:
            assert dep.findtext("m:groupId", namespaces=ns) == "com.smartscity"
            assert dep.findtext("m:artifactId", namespaces=ns) in allowed[short]
    jar = root / module / "target" / (module + "-0.1.0-SNAPSHOT.jar")
    with zipfile.ZipFile(jar) as archive:
        count = 0
        for name in archive.namelist():
            if not name.endswith(".class"):
                continue
            data = archive.read(name)
            assert struct.unpack(">H", data[6:8])[0] == 53, (name, "not Java 9 bytecode")
            if short in ("api", "spi", "core"):
                for forbidden in (b"org/springframework/", b"org/apache/zookeeper/", b"sun/misc/Unsafe", b"jdk/internal/", b"java/lang/reflect/"):
                    assert forbidden not in data, (name, forbidden)
            count += 1
        if short == "spring-boot-autoconfigure":
            metadata = json.loads(archive.read("META-INF/spring-configuration-metadata.json"))
            defaults = {p["name"]: p.get("defaultValue") for p in metadata["properties"]}
            assert defaults["smartscity.id.wait-timeout"] == "10ms"
            assert defaults["smartscity.id.buffer-size"] == 16384
            assert defaults["smartscity.id.low-water-percent"] == 50
            imports = archive.read("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports").decode().splitlines()
            for entry in imports:
                assert entry.replace(".", "/") + ".class" in archive.namelist()
            assert "META-INF/spring-autoconfigure-metadata.properties" in archive.namelist()
    print(f"PASS {module}: {count} classes, SHA256={hashlib.sha256(jar.read_bytes()).hexdigest()}")
print("PASS production dependency boundaries, generated metadata and Java 9 bytecode")
