package com.smartscity.id.benchmark;

import com.smartscity.id.api.IdException;
import com.smartscity.id.core.CachedUniqueIdGenerator;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

/** One SDK shared by all consumers; attempts and successful IDs are distinct metrics. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class IdBenchmark {
    @State(Scope.Benchmark)
    public static class Sdk {
        @Param({"standard", "javascript", "high-throughput"}) public String profile;
        CachedUniqueIdGenerator generator;
        @Setup(Level.Trial) public void start() { generator = BenchmarkGenerator.create(profile); }
        @TearDown(Level.Trial) public void stop() { generator.close(); }
    }
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class Outcomes {
        public long ids;
        public long timeouts;
    }
    @Benchmark public long generate(Sdk sdk, Outcomes outcomes) {
        try {
            long id = sdk.generator.getUniqueId();
            outcomes.ids++;
            return id;
        } catch (IdException e) {
            if (e.getReason() != IdException.Reason.TIMEOUT) throw e;
            outcomes.timeouts++;
            return 0;
        }
    }
}
