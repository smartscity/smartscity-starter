#!/usr/bin/env bash
set -euo pipefail
id_repo_root=$(cd "$(dirname "$0")/.." && pwd)
id_probe_cp="$id_repo_root/smartscity-id-compatibility-tests/target/test-classes"
for id_module in api spi core; do
  id_probe_cp="$id_probe_cp:$id_repo_root/smartscity-id-$id_module/target/smartscity-id-$id_module-0.1.0-SNAPSHOT.jar"
done
if [ "$#" -eq 0 ]; then
  echo 'Usage: testing/run-runtime-probe.sh /path/to/jdk9 /path/to/jdk17 ...' >&2
  exit 2
fi
for id_test_java_home in "$@"; do
  "$id_test_java_home/bin/java" -cp "$id_probe_cp" com.smartscity.id.compatibility.CoreRuntimeProbe
done
