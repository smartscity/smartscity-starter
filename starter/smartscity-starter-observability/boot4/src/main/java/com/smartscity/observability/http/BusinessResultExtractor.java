package com.smartscity.observability.http;

/**
 * Optional business classification; implementations must not perform I/O or consume streams.
 *
 * @author jack
 */
@FunctionalInterface
public interface BusinessResultExtractor {
  /**
   * Classifies an already captured body without changing outer HTTP status.
   *
   * @param status HTTP status
   * @param body sanitized complete body or omission marker
   * @return true/false, or null when business outcome is unknown
   */
  Boolean success(int status, String body);
}
