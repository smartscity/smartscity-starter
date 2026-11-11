package com.smartscity.observability.benchmark;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.smartscity.observability.CorrelationContext;
import com.smartscity.observability.PipeLogEncoder;
import com.smartscity.observability.logging.ObservabilityEncoder;
import com.smartscity.observability.logging.TextSanitizer;
import com.smartscity.observability.tracing.CorrelationScope;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.slf4j.LoggerFactory;

/**
 * Separates text sanitization, encoding of sanitized fields, and a real Logback/MDC path. The
 * bounded last-record sink excludes disk and network I/O and prevents dead-code elimination.
 *
 * @author jack
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class PipelineBenchmark {
  @Param({"1024", "8192"})
  public int inputSize;

  private String input;
  private TextSanitizer sanitizer;
  private PipeLogEncoder pipe;
  private Map<String, String> fields;
  private Instant time;
  private CorrelationContext correlation;
  private Logger logger;
  private RecordSink sink;
  private ObservabilityEncoder encoder;

  @Setup
  public void setup() {
    String prefix = "{\"password\":\"synthetic-secret\",\"message\":\"value|";
    input = prefix + "x".repeat(inputSize - prefix.length() - 2) + "\"}";
    sanitizer = new TextSanitizer(true, Collections.singletonList("synthetic-secret"));
    pipe = new PipeLogEncoder(65536);
    time = Instant.now();
    correlation = CorrelationContext.root("benchmark");
    fields = new LinkedHashMap<>();
    fields.put("traceid", correlation.traceId());
    fields.put("response", sanitizer.sanitize(input));
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    logger = context.getLogger("observability.benchmark." + Thread.currentThread().getId());
    logger.setAdditive(false);
    logger.setLevel(ch.qos.logback.classic.Level.INFO);
    encoder = new ObservabilityEncoder();
    encoder.setContext(context);
    encoder.setMaxRecordBytes(65536);
    encoder.start();
    sink = new RecordSink(encoder);
    sink.setContext(context);
    sink.start();
    logger.addAppender(sink);
    byte[] check = logWithMdc();
    String record = new String(check, java.nio.charset.StandardCharsets.UTF_8);
    if (record.contains("synthetic-secret")
        || !record.contains("%7C")
        || !record.contains(correlation.traceId())) {
      throw new IllegalStateException("Log benchmark does not exercise the intended pipeline");
    }
  }

  @Benchmark
  public String structuredMask() {
    return sanitizer.sanitize(input);
  }

  @Benchmark
  public String pipeEncode() {
    return pipe.encode("INFO", time, "Benchmark.java:1", "_com_request_out", fields);
  }

  @Benchmark
  public byte[] logWithMdc() {
    try (CorrelationScope ignored = CorrelationScope.open(correlation)) {
      logger.info("{}", input);
      return sink.last;
    }
  }

  @TearDown
  public void cleanup() {
    logger.detachAppender(sink);
    sink.stop();
    encoder.stop();
  }

  private static final class RecordSink extends AppenderBase<ILoggingEvent> {
    private final ObservabilityEncoder encoder;
    private volatile byte[] last;

    private RecordSink(ObservabilityEncoder encoder) {
      this.encoder = encoder;
    }

    @Override
    protected void append(ILoggingEvent event) {
      last = encoder.encode(event);
    }
  }
}
