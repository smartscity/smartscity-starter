package com.smartscity.observability.tracing;

import com.smartscity.observability.CorrelationContext;
import org.springframework.core.task.TaskDecorator;

/**
 * Captures correlation at submission without retaining an open scope across threads.
 *
 * @author jack
 */
public final class CorrelationTaskDecorator implements TaskDecorator {
  private final TracerAccess tracer;

  public CorrelationTaskDecorator() {
    this(null);
  }

  public CorrelationTaskDecorator(TracerAccess tracer) {
    this.tracer = tracer;
  }

  @Override
  public Runnable decorate(Runnable task) {
    CorrelationContext snapshot = CorrelationScope.current();
    Runnable decorated =
        () -> {
          try (CorrelationScope scope = CorrelationScope.open(snapshot)) {
            task.run();
          }
        };
    return tracer == null ? decorated : tracer.wrap(decorated);
  }

  /**
   * Composes with the application's existing decorator without replacing it.
   *
   * @param delegate application-owned decorator
   * @return combined decorator
   */
  public TaskDecorator compose(TaskDecorator delegate) {
    return task -> delegate.decorate(decorate(task));
  }
}
