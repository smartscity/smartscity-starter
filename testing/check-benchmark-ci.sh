#!/usr/bin/env bash
# Fast orchestration checks with synthetic process/results. No performance claims.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin"
export CI_CHECK_FIXTURES=$work
jq -n '[{benchmark:"com.smartscity.id.benchmark.IdBenchmark.generate", params:{profile:"standard"}, threads:1,
  mode:"thrpt", primaryMetric:{score:100, scoreUnit:"ops/s"},
  secondaryMetrics:{ids:{score:99}, timeouts:{score:1}, "gc.alloc.rate.norm":{score:0}}}]' > "$work/jmh.json"
jq -n '{seconds:20,threads:4,targetTps:100000,scheduled:2000000,attempted:2000000,
  successful:2000000,timeouts:0,missed:0,late:0,duplicates:0,successfulTps:100000,
  successPerSecond:[range(20)|100000],callP50Us:1,callP95Us:2,callP99Us:3,scheduledP99Us:4,targetMet:true}' > "$work/fixed.json"

check_jmh() { jq -e --arg kind jmh --arg profile standard --argjson threads 1 -f testing/benchmark-result.jq "$1" >/dev/null 2>&1; }
check_fixed() { jq -e --arg kind fixed --arg profile '' --argjson threads 4 -f testing/benchmark-result.jq "$1" >/dev/null 2>&1; }
check_jmh "$work/jmh.json"
for mutation in '[]' '.[0].threads=8' '.[0].params.profile="unknown"' 'del(.[0].secondaryMetrics.ids)' '.[0].secondaryMetrics.ids.score="NaN"'; do
    jq "$mutation" "$work/jmh.json" > "$work/bad.json"
    if check_jmh "$work/bad.json"; then echo "Accepted invalid JMH: $mutation" >&2; exit 1; fi
done
check_fixed "$work/fixed.json"
for mutation in '.duplicates=1' '.successful=1' '.targetMet=false' '.successPerSecond=[]'; do
    jq "$mutation" "$work/fixed.json" > "$work/bad.json"
    if check_fixed "$work/bad.json"; then echo "Accepted invalid fixed rate: $mutation" >&2; exit 1; fi
done

# Replace external process execution, not the script or its result checks.
cat > "$work/bin/java" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case " $* " in *' -version '*) echo 'synthetic JVM for orchestration tests'; exit 0 ;; esac
if [[ ${CI_CHECK_FAULT:-} == crash ]]; then exit 7; fi
if [[ " $* " == *' FixedRateProbe '* || " $* " == *' com.smartscity.id.benchmark.FixedRateProbe '* ]]; then
    if [[ ${CI_CHECK_FAULT:-} == duplicate ]]; then jq -c '.duplicates=1' "$CI_CHECK_FIXTURES/fixed.json"
    elif [[ ${CI_CHECK_FAULT:-} == missed ]]; then
        jq -c '.successful-=1 | .attempted-=1 | .missed=1 | .successfulTps=99999.95 | .successPerSecond[0]-=1 | .targetMet=false' "$CI_CHECK_FIXTURES/fixed.json"
    else jq -c . "$CI_CHECK_FIXTURES/fixed.json"; fi
else
    while (( $# )); do
        case "$1" in
            -p) profile=${2#profile=}; shift ;;
            -t) threads=$2; shift ;;
            -rff) output=$2; shift ;;
        esac
        shift
    done
    if [[ ${CI_CHECK_FAULT:-} == missing ]]; then exit 0; fi
    jq --arg profile "$profile" --argjson threads "$threads" \
        '.[0].params.profile=$profile | .[0].threads=$threads' "$CI_CHECK_FIXTURES/jmh.json" > "$output"
fi
MOCK
cat > "$work/bin/timeout" <<'MOCK'
#!/usr/bin/env bash
if [[ ${CI_CHECK_FAULT:-} == timeout ]]; then exit 124; fi
shift 3
exec "$@"
MOCK
cat > "$work/bin/lscpu" <<'MOCK'
#!/usr/bin/env bash
echo 'synthetic CPU for orchestration tests'
MOCK
cat > "$work/bin/sha256sum" <<'MOCK'
#!/usr/bin/env bash
exec shasum -a 256 "$@"
MOCK
chmod +x "$work/bin/"*
export PATH="$work/bin:$PATH"
export GITHUB_STEP_SUMMARY="$work/summary.md"
run_case() {
    local name=$1 fault=$2 strict=$3 expected=$4 actual=0
    CI_CHECK_FAULT="$fault" REQUIRE_100K="$strict" bash testing/run-benchmark-ci.sh "$work/$name" > "$work/$name.txt" 2>&1 || actual=$?
    if [[ $actual != "$expected" ]]; then cat "$work/$name.txt"; echo "$name: expected $expected got $actual" >&2; exit 1; fi
    jq -e --argjson code "$expected" '.exitCode == $code' "$work/$name/result.json" >/dev/null
}
run_case success '' false 0
jq -e '.status=="MEASURED" and (.jmh|length)==9 and (.fixedRate|length)==3 and .all100kTargetsMet' "$work/success/result.json" >/dev/null
run_case missed missed false 0
jq -e '.status=="MEASURED" and .all100kTargetsMet==false' "$work/missed/result.json" >/dev/null
run_case strict missed true 2
run_case crash crash false 7
run_case timeout timeout false 124
run_case missing missing false 2
run_case duplicate duplicate false 5
jq -e '.status=="PARTIAL" and (.jmh|length)==9 and (.fixedRate|length)==0 and .all100kTargetsMet==null' "$work/duplicate/result.json" >/dev/null
if bash testing/run-benchmark-ci.sh "$work/success" >/dev/null 2>&1; then echo 'Reused stale directory' >&2; exit 1; fi
echo 'PASS: 11 result validation checks; 7 runner scenarios; stale output rejected. Synthetic results only.'
