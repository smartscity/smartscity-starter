package com.smartscity.id.core;

import com.smartscity.id.api.IdEncoder;

/**
 * Version one permutation of the positive 30/18/15 payload, independent of epoch. Numeric order
 * changes. Version metadata must be stored outside the positive long.
 *
 * @author jack
 */
public final class RoaringIdEncoder implements IdEncoder {
  public static final String VERSION = "roaring-30-18-15-v1";

  /** Creates version one; other widths and versions are not implicitly supported. */
  public RoaringIdEncoder() {}

  /**
   * Resolves persisted metadata without guessing or reinterpreting another encoding.
   *
   * @param version persisted version, never inferred from an ID
   * @return version one encoder
   * @throws IllegalArgumentException if version is unknown, including null
   */
  public static RoaringIdEncoder forVersion(String version) {
    if (!VERSION.equals(version)) {
      throw new IllegalArgumentException("Unsupported Roaring encoding: " + version);
    }
    return new RoaringIdEncoder();
  }

  @Override
  public long encode(long id) {
    requirePositive(id);
    long t = id >>> 33, w = (id >>> 15) & 0x3ffffL, s = id & 0x7fffL;
    return (w << 45) | ((t >>> 15) << 30) | ((s >>> 1) << 16) | ((t & 0x7fffL) << 1) | (s & 1L);
  }

  @Override
  public long decode(long id) {
    requirePositive(id);
    long w = id >>> 45,
        t = (((id >>> 30) & 0x7fffL) << 15) | ((id >>> 1) & 0x7fffL),
        s = (((id >>> 16) & 0x3fffL) << 1) | (id & 1L);
    return (t << 33) | (w << 15) | s;
  }

  private static void requirePositive(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("ID must be positive");
    }
  }
}
