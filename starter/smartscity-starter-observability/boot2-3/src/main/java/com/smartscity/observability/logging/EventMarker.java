package com.smartscity.observability.logging;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Marker;

/**
 * Immutable, already-sanitized event snapshot retained safely by asynchronous appenders.
 *
 * @author jack
 */
public final class EventMarker implements Marker {
  private static final long serialVersionUID = 1L;
  private final String tag;
  private final Map<String, String> fields;

  public EventMarker(String tag, Map<String, String> fields) {
    this.tag = tag;
    this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
  }

  public Map<String, String> fields() {
    return fields;
  }

  public String tag() {
    return tag;
  }

  @Override
  public String getName() {
    return tag;
  }

  @Override
  public void add(Marker marker) {
    throw new UnsupportedOperationException("Immutable event");
  }

  @Override
  public boolean remove(Marker marker) {
    return false;
  }

  @Override
  public boolean hasChildren() {
    return false;
  }

  @Override
  public boolean hasReferences() {
    return false;
  }

  @Override
  public Iterator<Marker> iterator() {
    return Collections.emptyIterator();
  }

  @Override
  public boolean contains(Marker m) {
    return this == m;
  }

  @Override
  public boolean contains(String name) {
    return tag.equals(name);
  }
}
