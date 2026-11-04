package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.api.IdSnapshot;
import com.smartscity.id.api.RegistryCapacity;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.ToLongFunction;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;

/**
 * Binds approximate local generator metrics when Micrometer is available.
 *
 * @author jack
 */
@AutoConfiguration(after = IdAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnSingleCandidate(IdGenerator.class)
@ConditionalOnProperty(
    prefix = "smartscity.id",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class IdMetricsAutoConfiguration {
  /**
   * Creates a binder for approximate local counters and readiness observations.
   *
   * <p>Binding does not transfer generator ownership. Meter callbacks may run concurrently with
   * generation and shutdown; observing them does not allocate IDs or wait for external I/O.
   * Registry capacity is sampled asynchronously on demand and retains its observation timestamp.
   *
   * @param generator shared generator whose thread-safe snapshots supply metric values
   * @param properties configuration supplying the group label; do not mutate during binding
   * @return a binder that registers gauges and counters when given a registry
   */
  @Bean
  @ConditionalOnMissingBean(name = "smartscityIdMetrics")
  public MeterBinder smartscityIdMetrics(IdGenerator generator, IdProperties properties) {
    return registry -> {
      String group = properties.getGroup();
      Gauge.builder("smartscity.id.worker.capacity", generator, g -> g.snapshot().getWorkerSlots())
          .tag("group", group)
          .register(registry);
      Gauge.builder(
              "smartscity.id.worker.remaining",
              generator,
              g -> {
                long remaining = g.snapshot().getRegistryCapacity().getRemaining();
                return remaining < 0 ? Double.NaN : remaining;
              })
          .tag("group", group)
          .register(registry);
      Gauge.builder(
              "smartscity.id.worker.capacity.observed.milliseconds",
              generator,
              g -> {
                RegistryCapacity capacity = g.snapshot().getRegistryCapacity();
                return capacity.getRemaining() < 0 ? Double.NaN : capacity.getObservedAtMillis();
              })
          .tag("group", group)
          .register(registry);
      Gauge.builder(
              "smartscity.id.worker.capacity.sample.success",
              generator,
              g ->
                  g.snapshot().getRegistryCapacity().getStatus() == RegistryCapacity.Status.OBSERVED
                      ? 1
                      : 0)
          .tag("group", group)
          .register(registry);
      Gauge.builder(
              "smartscity.id.time.logical.milliseconds",
              generator,
              g -> g.snapshot().getLogicalTimeMillis())
          .tag("group", group)
          .register(registry);
      Gauge.builder(
              "smartscity.id.time.future.milliseconds",
              generator,
              g -> g.snapshot().getFutureTimeMillis())
          .tag("group", group)
          .register(registry);
      Gauge.builder(
              "smartscity.id.time.remaining.seconds",
              generator,
              g -> g.snapshot().getRemainingTimeSeconds())
          .tag("group", group)
          .register(registry);
      FunctionCounter.builder(
              "smartscity.id.polls.empty", generator, g -> g.snapshot().getEmptyPolls())
          .tag("group", group)
          .register(registry);
      FunctionCounter.builder(
              "smartscity.id.polls.contended", generator, g -> g.snapshot().getContendedPolls())
          .tag("group", group)
          .register(registry);
      FunctionCounter.builder(
              "smartscity.id.consumer.parks", generator, g -> g.snapshot().getConsumerParks())
          .tag("group", group)
          .register(registry);
      FunctionCounter.builder(
              "smartscity.id.timeouts", generator, g -> g.snapshot().getEmptyTimeouts())
          .tag("group", group)
          .tag("stage", "empty")
          .register(registry);
      FunctionCounter.builder(
              "smartscity.id.timeouts", generator, g -> g.snapshot().getContentionTimeouts())
          .tag("group", group)
          .tag("stage", "contention")
          .register(registry);
      FunctionCounter.builder(
              "smartscity.id.timeouts", generator, g -> g.snapshot().getDeadlineTimeouts())
          .tag("group", group)
          .tag("stage", "after_claim")
          .register(registry);

      Gauge.builder("smartscity.id.worker.raw", generator, g -> g.snapshot().getRawWorkerId())
          .tag("group", group)
          .register(registry);
      Gauge.builder("smartscity.id.worker.slot", generator, g -> g.snapshot().getWorkerId())
          .tag("group", group)
          .register(registry);
      Gauge.builder(
              "smartscity.id.started.milliseconds",
              generator,
              g -> g.snapshot().getStartedAtMillis())
          .tag("group", group)
          .register(registry);
      FunctionCounter.builder(
              "smartscity.id.generated", generator, g -> g.snapshot().getGenerated())
          .tag("group", group)
          .register(registry);
      FunctionCounter.builder("smartscity.id.waits", generator, g -> g.snapshot().getWaits())
          .tag("group", group)
          .register(registry);
      for (IdException.Reason reason : IdException.Reason.values()) {
        FunctionCounter.builder(
                "smartscity.id.failures",
                generator,
                g -> g.snapshot().getFailures().getOrDefault(reason, 0L))
            .tag("group", group)
            .tag("reason", reason.name())
            .register(registry);
      }
      FunctionCounter.builder(
              "smartscity.id.wait.nanoseconds", generator, g -> g.snapshot().getWaitNanos())
          .tag("group", group)
          .register(registry);
      FunctionCounter.builder(
              "smartscity.id.refill.nanoseconds", generator, g -> g.snapshot().getRefillNanos())
          .tag("group", group)
          .register(registry);
      FunctionCounter.builder(
              "smartscity.id.refill.failures", generator, g -> g.snapshot().getRefillFailures())
          .tag("group", group)
          .register(registry);
      FunctionCounter.builder(
              "smartscity.id.latency.samples", generator, g -> g.snapshot().getLatencySamples())
          .tag("group", group)
          .register(registry);
      Gauge.builder(
              "smartscity.id.latency.sample.interval",
              generator,
              g -> g.snapshot().getLatencySampleInterval())
          .tag("group", group)
          .register(registry);
      Gauge.builder("smartscity.id.cache.age", generator, g -> g.snapshot().getOldestAgeMillis())
          .tag("group", group)
          .register(registry);
      Gauge.builder(
              "smartscity.id.latency.upper.nanoseconds",
              generator,
              g -> observedLatency(g.snapshot(), IdSnapshot::getLatencyP50Nanos))
          .tag("group", group)
          .tag("quantile", "0.50")
          .register(registry);
      Gauge.builder(
              "smartscity.id.latency.upper.nanoseconds",
              generator,
              g -> observedLatency(g.snapshot(), IdSnapshot::getLatencyP95Nanos))
          .tag("group", group)
          .tag("quantile", "0.95")
          .register(registry);
      Gauge.builder(
              "smartscity.id.latency.upper.nanoseconds",
              generator,
              g -> observedLatency(g.snapshot(), IdSnapshot::getLatencyP99Nanos))
          .tag("group", group)
          .tag("quantile", "0.99")
          .register(registry);
      Gauge.builder("smartscity.id.buffered", generator, g -> g.snapshot().getBuffered())
          .tag("group", group)
          .register(registry);
      Gauge.builder("smartscity.id.ready", generator, g -> g.snapshot().isReady() ? 1 : 0)
          .tag("group", group)
          .register(registry);
      Gauge.builder(
              "smartscity.id.clock.deviation",
              generator,
              g -> g.snapshot().getClockDeviationMillis())
          .tag("group", group)
          .register(registry);
    };
  }

  private static double observedLatency(IdSnapshot snapshot, ToLongFunction<IdSnapshot> quantile) {
    return snapshot.getLatencySamples() == 0 ? Double.NaN : quantile.applyAsLong(snapshot);
  }
}
