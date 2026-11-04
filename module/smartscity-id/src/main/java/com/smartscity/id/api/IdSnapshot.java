package com.smartscity.id.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Immutable approximate observation. Latency percentiles are sample bucket upper bounds.
 *
 * <p>Latency sums and percentiles describe sampled completions only. Check {@link
 * #getLatencySamples()} before interpreting them: zero samples means no data, and percentile
 * getters return zero as a sentinel. Outcome counts are independent of sampling; concurrent
 * snapshots remain approximate.
 *
 * <p>Logical timestamps describe published allocation time, not event time. Remaining whole seconds
 * exclude the partially used current second. Registry capacity has its own sampling time/status;
 * unavailable-claim counters describe observations and timeout stages, not scheduling root causes.
 *
 * @author jack
 */
public final class IdSnapshot {
  private final long workerSlots;
  private final long logicalTimeMillis;
  private final long futureTimeMillis;
  private final long remainingTimeSeconds;
  private final long emptyPolls;
  private final long contendedPolls;
  private final long consumerParks;
  private final long emptyTimeouts;
  private final long contentionTimeouts;
  private final long deadlineTimeouts;
  private final RegistryCapacity registryCapacity;
  private final long startedAtMillis;
  private final boolean ready;
  private final String state;
  private final long rawWorkerId;
  private final long workerId;
  private final long generated;
  private final long waits;
  private final long waitNanos;
  private final long latencyNanos;
  private final long latencySamples;
  private final int latencySampleInterval;
  private final long clockDeviationMillis;
  private final long oldestAgeMillis;
  private final long refillNanos;
  private final long refillFailures;
  private final long latencyP50Nanos;
  private final long latencyP95Nanos;
  private final long latencyP99Nanos;
  private final int buffered;
  private final Map<IdException.Reason, Long> failures;

  private IdSnapshot(Builder b) {
    workerSlots = b.workerSlots;
    logicalTimeMillis = b.logicalTimeMillis;
    futureTimeMillis = b.futureTimeMillis;
    remainingTimeSeconds = b.remainingTimeSeconds;
    emptyPolls = b.emptyPolls;
    contendedPolls = b.contendedPolls;
    consumerParks = b.consumerParks;
    emptyTimeouts = b.emptyTimeouts;
    contentionTimeouts = b.contentionTimeouts;
    deadlineTimeouts = b.deadlineTimeouts;
    registryCapacity = b.registryCapacity;
    startedAtMillis = b.startedAtMillis;
    ready = b.ready;
    state = b.state;
    rawWorkerId = b.rawWorkerId;
    workerId = b.workerId;
    generated = b.generated;
    waits = b.waits;
    waitNanos = b.waitNanos;
    latencyNanos = b.latencyNanos;
    latencySamples = b.latencySamples;
    latencySampleInterval = b.latencySampleInterval;
    clockDeviationMillis = b.clockDeviationMillis;
    oldestAgeMillis = b.oldestAgeMillis;
    refillNanos = b.refillNanos;
    refillFailures = b.refillFailures;
    latencyP50Nanos = b.latencyP50Nanos;
    latencyP95Nanos = b.latencyP95Nanos;
    latencyP99Nanos = b.latencyP99Nanos;
    buffered = b.buffered;
    EnumMap<IdException.Reason, Long> copy = new EnumMap<>(IdException.Reason.class);
    copy.putAll(b.failures);
    failures = Collections.unmodifiableMap(copy);
  }

  public long getWorkerSlots() {
    return workerSlots;
  }

  public long getLogicalTimeMillis() {
    return logicalTimeMillis;
  }

  public long getFutureTimeMillis() {
    return futureTimeMillis;
  }

  public long getRemainingTimeSeconds() {
    return remainingTimeSeconds;
  }

  public long getEmptyPolls() {
    return emptyPolls;
  }

  public long getContendedPolls() {
    return contendedPolls;
  }

  public long getConsumerParks() {
    return consumerParks;
  }

  public long getEmptyTimeouts() {
    return emptyTimeouts;
  }

  public long getContentionTimeouts() {
    return contentionTimeouts;
  }

  public long getDeadlineTimeouts() {
    return deadlineTimeouts;
  }

  public RegistryCapacity getRegistryCapacity() {
    return registryCapacity;
  }

  public long getStartedAtMillis() {
    return startedAtMillis;
  }

  public boolean isReady() {
    return ready;
  }

  public String getState() {
    return state;
  }

  public long getRawWorkerId() {
    return rawWorkerId;
  }

  public long getWorkerId() {
    return workerId;
  }

  public long getGenerated() {
    return generated;
  }

  public long getWaits() {
    return waits;
  }

  public long getWaitNanos() {
    return waitNanos;
  }

  /** Returns the sum of sampled durations only; divide by sample count, not generated IDs. */
  public long getLatencyNanos() {
    return latencyNanos;
  }

  /** Returns observed successful and failed completion durations; zero means no latency data. */
  public long getLatencySamples() {
    return latencySamples;
  }

  /** Returns inverse sampling probability; 1 means all calls, not an estimated sample count. */
  public int getLatencySampleInterval() {
    return latencySampleInterval;
  }

  public long getClockDeviationMillis() {
    return clockDeviationMillis;
  }

  public long getOldestAgeMillis() {
    return oldestAgeMillis;
  }

  public long getRefillNanos() {
    return refillNanos;
  }

  public long getRefillFailures() {
    return refillFailures;
  }

  public long getLatencyP50Nanos() {
    return latencyP50Nanos;
  }

  public long getLatencyP95Nanos() {
    return latencyP95Nanos;
  }

  public long getLatencyP99Nanos() {
    return latencyP99Nanos;
  }

  public int getBuffered() {
    return buffered;
  }

  public Map<IdException.Reason, Long> getFailures() {
    return failures;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Mutable observation assembly; callers must not share the builder across threads. */
  public static final class Builder {
    private long workerSlots = -1;
    private long logicalTimeMillis = 0;
    private long futureTimeMillis = 0;
    private long remainingTimeSeconds = -1;
    private long emptyPolls = 0;
    private long contendedPolls = 0;
    private long consumerParks = 0;
    private long emptyTimeouts = 0;
    private long contentionTimeouts = 0;
    private long deadlineTimeouts = 0;
    private RegistryCapacity registryCapacity = RegistryCapacity.unknown();
    private long startedAtMillis;

    public Builder workerSlots(long value) {
      workerSlots = value;
      return this;
    }

    public Builder logicalTimeMillis(long value) {
      logicalTimeMillis = value;
      return this;
    }

    public Builder futureTimeMillis(long value) {
      futureTimeMillis = value;
      return this;
    }

    public Builder remainingTimeSeconds(long value) {
      remainingTimeSeconds = value;
      return this;
    }

    public Builder emptyPolls(long value) {
      emptyPolls = value;
      return this;
    }

    public Builder contendedPolls(long value) {
      contendedPolls = value;
      return this;
    }

    public Builder consumerParks(long value) {
      consumerParks = value;
      return this;
    }

    public Builder emptyTimeouts(long value) {
      emptyTimeouts = value;
      return this;
    }

    public Builder contentionTimeouts(long value) {
      contentionTimeouts = value;
      return this;
    }

    public Builder deadlineTimeouts(long value) {
      deadlineTimeouts = value;
      return this;
    }

    public Builder registryCapacity(RegistryCapacity value) {
      registryCapacity = value;
      return this;
    }

    public Builder startedAtMillis(long value) {
      startedAtMillis = value;
      return this;
    }

    private boolean ready;
    private String state;
    private long rawWorkerId;
    private long workerId;
    private long generated;
    private long waits;
    private long waitNanos;
    private long latencyNanos;
    private long latencySamples;
    private int latencySampleInterval = 1;
    private long clockDeviationMillis;
    private long oldestAgeMillis;
    private long refillNanos;
    private long refillFailures;
    private long latencyP50Nanos;
    private long latencyP95Nanos;
    private long latencyP99Nanos;
    private int buffered;
    private Map<IdException.Reason, Long> failures = new EnumMap<>(IdException.Reason.class);

    public Builder ready(boolean value) {
      ready = value;
      return this;
    }

    public Builder state(String value) {
      state = value;
      return this;
    }

    public Builder rawWorkerId(long value) {
      rawWorkerId = value;
      return this;
    }

    public Builder workerId(long value) {
      workerId = value;
      return this;
    }

    public Builder generated(long value) {
      generated = value;
      return this;
    }

    public Builder waits(long value) {
      waits = value;
      return this;
    }

    public Builder waitNanos(long value) {
      waitNanos = value;
      return this;
    }

    public Builder latencyNanos(long value) {
      latencyNanos = value;
      return this;
    }

    public Builder latencySamples(long value) {
      latencySamples = value;
      return this;
    }

    public Builder latencySampleInterval(int value) {
      latencySampleInterval = value;
      return this;
    }

    public Builder clockDeviationMillis(long value) {
      clockDeviationMillis = value;
      return this;
    }

    public Builder oldestAgeMillis(long value) {
      oldestAgeMillis = value;
      return this;
    }

    public Builder refillNanos(long value) {
      refillNanos = value;
      return this;
    }

    public Builder refillFailures(long value) {
      refillFailures = value;
      return this;
    }

    public Builder latencyP50Nanos(long value) {
      latencyP50Nanos = value;
      return this;
    }

    public Builder latencyP95Nanos(long value) {
      latencyP95Nanos = value;
      return this;
    }

    public Builder latencyP99Nanos(long value) {
      latencyP99Nanos = value;
      return this;
    }

    public Builder buffered(int value) {
      buffered = value;
      return this;
    }

    public Builder failures(Map<IdException.Reason, Long> value) {
      failures = value;
      return this;
    }

    public IdSnapshot build() {
      return new IdSnapshot(this);
    }
  }
}
