# Reject incomplete/mismatched measurements before generating a CI summary.
def nonnegative: type == "number" and isfinite and . >= 0;
def count: nonnegative and floor == .;
if $kind == "jmh" then
  if type != "array" or length != 1 then error("Expected exactly one JMH result") else .[0] end
  | if .benchmark != "com.smartscity.id.benchmark.IdBenchmark.generate"
      or .params.profile != $profile or .threads != $threads or .mode != "thrpt"
      or .primaryMetric.scoreUnit != "ops/s"
      or ([.primaryMetric.score, .secondaryMetrics.ids.score, .secondaryMetrics.timeouts.score,
           .secondaryMetrics["gc.alloc.rate.norm"].score] | all(nonnegative) | not)
      or .secondaryMetrics.ids.score <= 0
    then error("Invalid or mismatched JMH metrics") else . end
  | {kind: "jmh", profile: .params.profile, threads,
     attemptsPerSecond: .primaryMetric.score,
     successfulIdsPerSecond: .secondaryMetrics.ids.score,
     timeoutsPerSecond: .secondaryMetrics.timeouts.score,
     bytesPerAttempt: .secondaryMetrics["gc.alloc.rate.norm"].score}
elif $kind == "fixed" then
  if .seconds != 20 or .threads != 4 or .targetTps != 100000 or .scheduled != 2000000
      or ([.scheduled, .attempted, .successful, .timeouts, .missed, .late, .duplicates] | all(count) | not)
      or .duplicates != 0 or .successful + .timeouts + .missed + .late != .scheduled
      or .attempted != .successful + .timeouts + .late
      or (.successPerSecond | type != "array" or length != 20 or (all(count) | not))
      or (.successPerSecond | add) != .successful
      or ([.successfulTps, .callP50Us, .callP95Us, .callP99Us, .scheduledP99Us] | all(nonnegative) | not)
      or (.successfulTps - .successful / .seconds | fabs) > 0.01
      or .targetMet != (.successful == .scheduled and .duplicates == 0)
    then error("Invalid fixed-rate counts, target status or duplicate IDs") else . + {kind: "fixed"} end
else error("Unknown result kind") end
