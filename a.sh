(
  set -e
  cd /Users/liyunlong/git/smartscity/smartscity-starter

  export JAVA_HOME=/Users/liyunlong/.sdkman/candidates/java/21.0.12+1.1-tem
  export PATH="$JAVA_HOME/bin:$PATH"

  # Rebuild to measure the current source.
  ./mvnw -Pbenchmarks \
    -pl :smartscity-id-benchmark -am \
    -Dmaven.test.skip=true package

  uid_bench_out="$PWD/test/evidence/uid-local-$(date +%Y%m%d-%H%M%S)"

  # Use the existing local Baidu checkout; the runner verifies its pinned commit.
  python3 test/smartscity-id-benchmark/comparison/run.py \
    --upstream /private/tmp/smartscity-baidu-uid-20261025 \
    --output "$uid_bench_out" \
    --run

  printf '\nResults: %s\n' "$uid_bench_out"
)
