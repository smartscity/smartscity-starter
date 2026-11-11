package com.smartscity.observability.logging;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartscity.observability.autoconfigure.ObservabilityProperties;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strict complete-body JSON/form sanitization with depth and field/path redaction.
 *
 * @author jack
 */
public final class JsonBodySanitizer implements StructuredSanitizer {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Set<String> fields = new HashSet<>();
  private final Set<String> paths;
  private final TextSanitizer text;
  private final boolean enabled;

  public JsonBodySanitizer(ObservabilityProperties.Masking rules, TextSanitizer text) {
    for (String f : rules.getFields()) {
      fields.add(f.toLowerCase(Locale.ROOT));
    }
    paths = new HashSet<>(rules.getPaths());
    if (paths.size() > 128) {
      throw new IllegalArgumentException("Too many mask paths");
    }
    for (String path : paths) {
      if (path == null || path.length() > 4096 || !validPointer(path)) {
        throw new IllegalArgumentException("Invalid mask JSON Pointer");
      }
    }
    this.text = text;
    enabled = rules.isEnabled();
  }

  @Override
  public String sanitize(byte[] bytes, String type) {
    if (bytes == null || bytes.length > 1_048_576) {
      return "[omitted:incomplete]";
    }
    try {
      String source =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
              .decode(java.nio.ByteBuffer.wrap(bytes))
              .toString();
      if (type != null
          && type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
        ObjectNode form = mapper.createObjectNode();
        for (String pair : source.split("&", -1)) {
          String[] kv = pair.split("=", 2);
          String k = URLDecoder.decode(kv[0], "UTF-8");
          String v = kv.length == 2 ? URLDecoder.decode(kv[1], "UTF-8") : "";
          form.put(
              k, enabled && fields.contains(k.toLowerCase(Locale.ROOT)) ? "***" : text.sanitize(v));
        }
        return mapper.writeValueAsString(form);
      }
      if (type == null
          || !(type.toLowerCase(Locale.ROOT).startsWith("application/json")
              || type.toLowerCase(Locale.ROOT).split(";", 2)[0].endsWith("+json"))) {
        return "[omitted:content-type]";
      }
      try (JsonParser parser = mapper.getFactory().createParser(source)) {
        int depth = 0;
        JsonToken token;
        while ((token = parser.nextToken()) != null) {
          if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            if (++depth > 32) {
              return "[omitted:depth]";
            }
          } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
            depth--;
          }
        }
      }
      try (JsonParser parser = mapper.getFactory().createParser(source)) {
        JsonNode node = mapper.readTree(parser);
        if (node == null || parser.nextToken() != null) {
          return "[omitted:invalid-json]";
        }
        if (enabled && paths.contains("")) {
          return "\"***\"";
        }
        if (node.isTextual()) {
          node = mapper.getNodeFactory().textNode(text.sanitize(node.textValue()));
        } else {
          redact(node, "");
        }
        return text.sanitize(mapper.writeValueAsString(node));
      }
    } catch (Exception e) {
      return "[omitted:invalid-payload]";
    }
  }

  private static boolean validPointer(String path) {
    if (!path.isEmpty() && path.charAt(0) != '/') {
      return false;
    }
    for (int i = 0; i < path.length(); i++) {
      if (path.charAt(i) == '~') {
        if (++i == path.length() || (path.charAt(i) != '0' && path.charAt(i) != '1')) {
          return false;
        }
      }
    }
    return true;
  }

  private void redact(JsonNode node, String path) {
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> it = node.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        String next = path + "/" + e.getKey().replace("~", "~0").replace("/", "~1");
        if (enabled
            && (fields.contains(e.getKey().toLowerCase(Locale.ROOT)) || paths.contains(next))) {
          ((ObjectNode) node).put(e.getKey(), "***");
        } else if (e.getValue().isTextual()) {
          ((ObjectNode) node).put(e.getKey(), text.sanitize(e.getValue().textValue()));
        } else {
          redact(e.getValue(), next);
        }
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        String next = path + "/" + i;
        if (enabled && paths.contains(next)) {
          ((ArrayNode) node).set(i, mapper.getNodeFactory().textNode("***"));
        } else if (node.get(i).isTextual()) {
          ((ArrayNode) node)
              .set(i, mapper.getNodeFactory().textNode(text.sanitize(node.get(i).textValue())));
        } else {
          redact(node.get(i), next);
        }
      }
    }
  }
}
