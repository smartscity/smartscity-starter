package com.smartscity.id.api;

/**
 * Stateless, thread-safe reversible representation of positive standard IDs. Storage must carry the
 * encoding version separately from the numeric key.
 *
 * @author jack
 */
public interface IdEncoder {
  /**
   * Encodes a positive original ID.
   *
   * @param originalId positive original ID in the supported layout
   * @return positive encoded key
   * @throws IllegalArgumentException if the input is outside the supported domain
   */
  long encode(long originalId);

  /**
   * Restores a positive original ID.
   *
   * @param encodedId positive key in this encoding version
   * @return positive original ID
   * @throws IllegalArgumentException if the input is outside the supported domain
   */
  long decode(long encodedId);
}
