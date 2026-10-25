#!/usr/bin/env bash
# Linux orchestration only. Measurements are existing Java JMH/FixedRateProbe.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
out=${1:?Usage: bash testing/run-benchmark-ci.sh NEW_OUTPUT_DIRECTORY}
require_100k=${REQUIRE_100K:-false}
case "$require_100k" in true|false) ;; *) echo 'REQUIRE_100K must be true or false' >&2; exit 2 ;; esac
for dependency in java jq timeout sha256sum lscpu; do command -v "$dependency" >/dev/null; done
jar="$root/smartscity-id-benchmark/target/benchmarks.jar"
test -f "$jar"
# Refuse stale result directories, especially on persistent self-hosted runners.
mkdir "$out"
out=$(cd "$out" && pwd)
status=PARTIAL
SECONDS=0
shopt -s nullglob

finish() {
    code=$?
    trap - EXIT
    rows=("$out"/*.row.json)
    jq -s --arg status "$status" --argjson exitCode "$code" --argjson seconds "$SECONDS" '
      {status: $status, exitCode: $exitCode, elapsedSeconds: $seconds,
       jmh: map(select(.kind == "jmh")), fixedRate: map(select(.kind == "fixed"))}
      | .all100kTargetsMet = (if (.fixedRate | length) == 3 then (.fixedRate | all(.targetMet)) else null end)
    ' "${rows[@]}" </dev/null > "$out/result.json"
    {
        printf '## ID benchmark — %s\n\n' "$status"
        printf 'Process exit: %s. Measurements only; hosted runner results are not production capacity certification.\n\n' "$code"
        printf '| Layout | Threads | Successful ID/s | Timeout/s | Bytes/attempt |\n|---|---:|---:|---:|---:|\n'
        jq -r '.jmh[] | "| \(.profile) | \(.threads) | \(.successfulIdsPerSecond) | \(.timeoutsPerSecond) | \(.bytesPerAttempt) |"' "$out/result.json"
        printf '\n| Fixed-rate trial | ID/s | Timeouts | Missed | Late | Duplicates | Call p99 us | Scheduled p99 us | 100k target |\n|---|---:|---:|---:|---:|---:|---:|---:|---|\n'
        jq -r '.fixedRate | to_entries[] | .key as $trial | .value | "| \($trial + 1) | \(.successfulTps) | \(.timeouts) | \(.missed) | \(.late) | \(.duplicates) | \(.callP99Us) | \(.scheduledP99Us) | \(if .targetMet then "MET" else "NOT_MET" end) |"' "$out/result.json"
        printf '\nPartial/missing measurements are not PASS. Raw JSON, logs and machine details are in the artifact.\n'
    } > "$out/summary.md"
    if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then cat "$out/summary.md" >> "$GITHUB_STEP_SUMMARY"; fi
    exit "$code"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

{
    date -u '+UTC %Y-%m-%dT%H:%M:%SZ'
    git rev-parse HEAD
    git status --short
    uname -a
    lscpu
    for info in /etc/os-release /proc/meminfo; do
        if [[ -r $info ]]; then cat "$info"; fi
    done
    java -XshowSettings:vm -version
    printf 'Runner: %s; environment: %s; arch: %s\n' "${RUNNER_NAME:-local}" "${RUNNER_ENVIRONMENT:-local}" "${RUNNER_ARCH:-unknown}"
    printf 'Build JDK: 21; fork JVM: -Xms512m -Xmx512m; one SDK; one ID/call; budget: 480s\n'
    sha256sum "$jar"
} > "$out/environment.txt" 2>&1
git ls-files -z -- 'smartscity-id-*/src/*' '*pom.xml' testing/benchmark-result.jq testing/run-benchmark-ci.sh .github/workflows/id-benchmark.yml |
    xargs -0 sha256sum > "$out/sources.sha256"

run_java() {
    local log=$1 limit=$2 remaining
    shift 2
    remaining=$((480 - SECONDS))
    if (( remaining <= 0 )); then echo 'Benchmark budget exhausted' >&2; return 124; fi
    if (( remaining < limit )); then limit=$remaining; fi
    printf '%q ' java "$@" > "$log"
    printf '\n' >> "$log"
    # GNU timeout owns the process group, including JMH child JVMs.
    timeout --signal=TERM --kill-after=5s "${limit}s" java "$@" >> "$log" 2>&1
}

for profile in standard javascript high-throughput; do
    for threads in 1 4 8; do
        name="$profile-$threads"
        echo "JMH $name"
        run_java "$out/$name.txt" 40 -Xms128m -Xmx256m -jar "$jar" IdBenchmark.generate \
            -p "profile=$profile" -t "$threads" -f 1 -wi 2 -w 2s -i 3 -r 3s \
            -foe true -prof gc -rf json -rff "$out/$name.json"
        jq -e --arg kind jmh --arg profile "$profile" --argjson threads "$threads" \
            -f testing/benchmark-result.jq "$out/$name.json" > "$out/$name.row.tmp"
        mv "$out/$name.row.tmp" "$out/$name.row.json"
    done
done
for trial in 1 2 3; do
    echo "Fixed-rate 100k/s trial $trial"
    run_java "$out/fixed-$trial.txt" 40 -Xms512m -Xmx512m -cp "$jar" \
        com.smartscity.id.benchmark.FixedRateProbe 20
    # Java prints one JSON record; the log also contains the command line.
    sed -n '/^{/p' "$out/fixed-$trial.txt" > "$out/fixed-$trial.json"
    jq -s -e 'if length == 1 then .[0] else error("Expected one fixed-rate result") end' \
        "$out/fixed-$trial.json" |
        jq -e --arg kind fixed --arg profile '' --argjson threads 4 -f testing/benchmark-result.jq \
            > "$out/fixed-$trial.row.tmp"
    mv "$out/fixed-$trial.row.tmp" "$out/fixed-$trial.row.json"
done
status=MEASURED
if [[ $require_100k == true ]] && ! jq -s -e 'length == 3 and all(.targetMet)' "$out"/fixed-*.row.json >/dev/null; then
    echo '100k target NOT_MET; strict target checking was requested' >&2
    exit 2
fi
