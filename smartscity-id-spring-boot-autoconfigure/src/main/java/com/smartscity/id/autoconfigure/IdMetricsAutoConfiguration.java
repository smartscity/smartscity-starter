package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = IdAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnSingleCandidate(IdGenerator.class)
@ConditionalOnProperty(prefix = "smartscity.id", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IdMetricsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "smartscityIdMetrics")
    public MeterBinder smartscityIdMetrics(IdGenerator generator, IdProperties properties) {
        return registry -> {
            String group = properties.getGroup();
            Gauge.builder("smartscity.id.worker.raw", generator, g -> g.snapshot().getRawWorkerId()).tag("group", group).register(registry);
            Gauge.builder("smartscity.id.worker.slot", generator, g -> g.snapshot().getWorkerId()).tag("group", group).register(registry);
            Gauge.builder("smartscity.id.started.milliseconds", generator, g -> g.snapshot().getStartedAtMillis()).tag("group", group).register(registry);
            FunctionCounter.builder("smartscity.id.generated", generator, g -> g.snapshot().getGenerated()).tag("group", group).register(registry);
            FunctionCounter.builder("smartscity.id.waits", generator, g -> g.snapshot().getWaits()).tag("group", group).register(registry);
            for (IdException.Reason reason : IdException.Reason.values()) {
                FunctionCounter.builder("smartscity.id.failures", generator, g -> g.snapshot().getFailures().getOrDefault(reason, 0L))
                        .tag("group", group).tag("reason", reason.name()).register(registry);
            }
            FunctionCounter.builder("smartscity.id.wait.nanoseconds", generator, g -> g.snapshot().getWaitNanos()).tag("group", group).register(registry);
            FunctionCounter.builder("smartscity.id.refill.nanoseconds", generator, g -> g.snapshot().getRefillNanos()).tag("group", group).register(registry);
            FunctionCounter.builder("smartscity.id.refill.failures", generator, g -> g.snapshot().getRefillFailures()).tag("group", group).register(registry);
            Gauge.builder("smartscity.id.cache.age", generator, g -> g.snapshot().getOldestAgeMillis()).tag("group", group).register(registry);
            Gauge.builder("smartscity.id.latency.upper.nanoseconds", generator, g -> g.snapshot().getLatencyP50Nanos()).tag("group", group).tag("quantile", "0.50").register(registry);
            Gauge.builder("smartscity.id.latency.upper.nanoseconds", generator, g -> g.snapshot().getLatencyP95Nanos()).tag("group", group).tag("quantile", "0.95").register(registry);
            Gauge.builder("smartscity.id.latency.upper.nanoseconds", generator, g -> g.snapshot().getLatencyP99Nanos()).tag("group", group).tag("quantile", "0.99").register(registry);
            Gauge.builder("smartscity.id.buffered", generator, g -> g.snapshot().getBuffered()).tag("group", group).register(registry);
            Gauge.builder("smartscity.id.ready", generator, g -> g.snapshot().isReady() ? 1 : 0).tag("group", group).register(registry);
            Gauge.builder("smartscity.id.clock.deviation", generator, g -> g.snapshot().getClockDeviationMillis()).tag("group", group).register(registry);
        };
    }
}
