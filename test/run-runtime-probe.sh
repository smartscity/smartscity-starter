#!/usr/bin/env bash
set -euo pipefail
id_repo_root=$(cd "$(dirname "$0")/.." && pwd)
id_probe_cp="$id_repo_root/test/smartscity-id-compatibility-tests/target/test-classes"
id_probe_cp="$id_probe_cp:$id_repo_root/module/smartscity-id/target/smartscity-id-0.1.0-SNAPSHOT.jar"
if [ "$#" -eq 0 ]; then
  echo 'Usage: test/run-runtime-probe.sh /path/to/jdk9 /path/to/jdk17 ...' >&2
  exit 2
fi
for id_test_java_home in "$@"; do
  "$id_test_java_home/bin/java" -cp "$id_probe_cp" com.smartscity.id.compatibility.CoreRuntimeProbe
done
