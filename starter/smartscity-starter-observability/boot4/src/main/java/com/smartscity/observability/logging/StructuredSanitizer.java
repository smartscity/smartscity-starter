package com.smartscity.observability.logging;

/**
 * Converts bounded payloads to sanitized text without changing application-owned bytes.
 *
 * @author jack
 */
public interface StructuredSanitizer {
  /**
   * Sanitizes a complete body; failures must never return raw input.
   *
   * @param bytes complete bounded payload
   * @param contentType declared media type
   * @return protected representation or omission marker
   */
  String sanitize(byte[] bytes, String contentType);
}
