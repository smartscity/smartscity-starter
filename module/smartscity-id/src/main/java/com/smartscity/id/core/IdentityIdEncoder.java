package com.smartscity.id.core;

import com.smartscity.id.api.IdEncoder;

/**
 * Identity representation for positive standard IDs.
 *
 * @author jack
 */
public final class IdentityIdEncoder implements IdEncoder {
  public static final String VERSION = "standard-v1";

  @Override
  public long encode(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("ID must be positive");
    }
    return id;
  }

  @Override
  public long decode(long id) {
    return encode(id);
  }
}
