package com.smartscity.observability.autoconfigure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Independent capability configuration; exporter destinations use native management properties.
 *
 * @author jack
 */
@ConfigurationProperties("smartscity.observability")
public class ObservabilityProperties {
  private boolean enabled = true;
  private String serviceName;
  private final Correlation correlation = new Correlation();
  private final Logging logging = new Logging();
  private final Masking masking = new Masking();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(String v) {
    serviceName = v;
  }

  public Correlation getCorrelation() {
    return correlation;
  }

  public Logging getLogging() {
    return logging;
  }

  public Masking getMasking() {
    return masking;
  }

  /**
   * Common capability switch.
   *
   * @author jack
   */
  public static class Switch {
    private boolean enabled = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean v) {
      enabled = v;
    }
  }

  /**
   * Correlation trust is an explicit exact-peer/destination allowlist.
   *
   * @author jack
   */
  public static class Correlation extends Switch {
    private String mode = "auto";
    private List<String> trustedCallers = new ArrayList<>();
    private List<String> outboundHosts = new ArrayList<>();

    public String getMode() {
      return mode;
    }

    public void setMode(String v) {
      mode = v;
    }

    public List<String> getTrustedCallers() {
      return trustedCallers;
    }

    public void setTrustedCallers(List<String> v) {
      trustedCallers = v;
    }

    public List<String> getOutboundHosts() {
      return outboundHosts;
    }

    public void setOutboundHosts(List<String> v) {
      outboundHosts = v;
    }
  }

  /**
   * HTTP event and body settings; installing the Logback encoder is application-owned.
   *
   * @author jack
   */
  public static class Logging extends Switch {
    private final Body body = new Body();
    private int maxRecordBytes = 16384;
    private List<String> excludedPaths =
        new ArrayList<>(Arrays.asList("/actuator/health", "/actuator/prometheus"));

    public Body getBody() {
      return body;
    }

    public int getMaxRecordBytes() {
      return maxRecordBytes;
    }

    public void setMaxRecordBytes(int v) {
      maxRecordBytes = v;
    }

    public List<String> getExcludedPaths() {
      return excludedPaths;
    }

    public void setExcludedPaths(List<String> v) {
      excludedPaths = v;
    }
  }

  /**
   * Bounded capture opt-in, restricted to exact paths and JSON/form content types.
   *
   * @author jack
   */
  public static class Body {
    private boolean enabled;
    private int maxBytes = 8192;
    private long totalBudgetBytes = 8388608;
    private List<String> paths = new ArrayList<>();

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean v) {
      enabled = v;
    }

    public int getMaxBytes() {
      return maxBytes;
    }

    public void setMaxBytes(int v) {
      maxBytes = v;
    }

    public long getTotalBudgetBytes() {
      return totalBudgetBytes;
    }

    public void setTotalBudgetBytes(long v) {
      totalBudgetBytes = v;
    }

    public List<String> getPaths() {
      return paths;
    }

    public void setPaths(List<String> v) {
      paths = v;
    }
  }

  /**
   * Mask rules are compiled once and never hot-reloaded.
   *
   * @author jack
   */
  public static class Masking extends Switch {
    private List<String> fields =
        new ArrayList<>(
            Arrays.asList(
                "password",
                "passwd",
                "secret",
                "token",
                "access_token",
                "refresh_token",
                "authorization",
                "cookie",
                "set-cookie",
                "credential"));
    private List<String> paths = new ArrayList<>();
    private List<String> literals = new ArrayList<>();

    public List<String> getFields() {
      return fields;
    }

    public void setFields(List<String> v) {
      fields = v;
    }

    public List<String> getPaths() {
      return paths;
    }

    public void setPaths(List<String> v) {
      paths = v;
    }

    public List<String> getLiterals() {
      return literals;
    }

    public void setLiterals(List<String> v) {
      literals = v;
    }
  }
}
