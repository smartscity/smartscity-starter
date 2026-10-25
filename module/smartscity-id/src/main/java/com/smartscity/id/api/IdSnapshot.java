package com.smartscity.id.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Immutable approximate observation. Latency percentiles are power-of-two bucket upper bounds.
 *
 * @author jack
 */
public final class IdSnapshot {
  private final long startedAtMillis;
  private final boolean ready;
  private final String state;
  private final long rawWorkerId;
  private final long workerId;
  private final long generated;
  private final long waits;
  private final long waitNanos;
  private final long latencyNanos;
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
    startedAtMillis = b.startedAtMillis;
    ready = b.ready;
    state = b.state;
    rawWorkerId = b.rawWorkerId;
    workerId = b.workerId;
    generated = b.generated;
    waits = b.waits;
    waitNanos = b.waitNanos;
    latencyNanos = b.latencyNanos;
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

  public long getLatencyNanos() {
    return latencyNanos;
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
    private long startedAtMillis;

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
