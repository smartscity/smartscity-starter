package com.smartscity.observability.http;

import com.smartscity.observability.CaptureBudget;
import com.smartscity.observability.CorrelationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns bounded HTTP capture and a single completion claim across dispatch callbacks.
 *
 * @author jack
 */
public final class ExchangeObservation implements AutoCloseable {
  private final ObservationRuntime runtime;
  private final CorrelationContext context;
  private final boolean client;
  private final boolean logging;
  private final long start = System.nanoTime();
  private final Map<String, String> fields = new LinkedHashMap<>();
  private final AtomicBoolean complete = new AtomicBoolean();
  private CaptureBudget.Capture request;
  private CaptureBudget.Capture response;
  private String requestType;
  private String responseType;
  private volatile boolean failed;
  private Runnable completed = () -> {};

  public synchronized void onCompletion(Runnable hook) {
    if (complete.get()) {
      throw new IllegalStateException("Exchange already completed");
    }
    this.completed = java.util.Objects.requireNonNull(hook);
  }

  public synchronized void resetResponse() {
    if (response != null) {
      response.close();
      response = null;
    }
  }

  public ExchangeObservation(
      ObservationRuntime r, CorrelationContext c, String path, String peer, boolean client) {
    runtime = r;
    context = c;
    this.client = client;
    logging = r.logs(path);
    fields.put("uri", path);
    fields.put("clientIP", peer);
    fields.put("remoteIp", peer);
    if (logging && !client) {
      r.emit("_com_request_in", c, fields);
    }
  }

  public CorrelationContext context() {
    return context;
  }

  public boolean isComplete() {
    return complete.get();
  }

  public synchronized void fail() {
    if (!complete.get()) {
      failed = true;
    }
  }

  public synchronized CaptureBudget.Capture request(String path, String type, String encoding) {
    if (complete.get()) {
      return null;
    }
    if (request == null && !complete.get() && logging) {
      request = runtime.capture(path, type, encoding);
      requestType = type;
    }
    return request;
  }

  public synchronized CaptureBudget.Capture response(String path, String type, String encoding) {
    if (complete.get()) {
      return null;
    }
    if (response == null && !complete.get() && logging) {
      response = runtime.capture(path, type, encoding);
      responseType = type;
    }
    return response;
  }

  public synchronized void finish(int status, boolean requestComplete, boolean responseComplete) {
    if (!complete.compareAndSet(false, true)) {
      return;
    }
    try {
      if (logging) {
        Map<String, String> end = new LinkedHashMap<>(fields);
        end.put("proc_time", Long.toString((System.nanoTime() - start) / 1_000_000));
        String payload = runtime.body(response, responseType, responseComplete);
        Boolean result = failed ? Boolean.FALSE : runtime.result(status, payload);
        end.put("result", result == null ? null : result.toString());
        end.put("errno", status > 0 ? Integer.toString(status) : null);
        end.put("errmsg", failed ? "transport-failure" : null);
        if (request != null) {
          end.put("request", runtime.body(request, requestType, requestComplete));
        }
        if (response != null) {
          end.put("response", payload);
        }
        runtime.emit(
            client
                ? (Boolean.TRUE.equals(result) ? "_com_http_success" : "_com_http_failure")
                : "_com_request_out",
            context,
            end);
      }
    } finally {
      try {
        if (request != null) {
          request.close();
        }
        if (response != null) {
          response.close();
        }
      } finally {
        try {
          completed.run();
        } catch (RuntimeException e) {
          runtime.recordFailure();
        }
      }
    }
  }

  /** Aborts an unfinished observation and releases its captures and owned span exactly once. */
  @Override
  public synchronized void close() {
    fail();
    finish(0, false, false);
  }
}
