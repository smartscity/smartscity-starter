package com.smartscity.observability.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Independently owned exporter switches; destinations use native management properties.
 *
 * @author jack
 */
@ConfigurationProperties("smartscity.observability.metrics")
public class MetricsProperties {
  private boolean enabled = true;
  private final Export udp = new Export();
  private final Export prometheus = new Export();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Export getUdp() {
    return udp;
  }

  public Export getPrometheus() {
    return prometheus;
  }

  /**
   * Explicit exporter opt-in; mere classpath presence does not enable it.
   *
   * @author jack
   */
  public static class Export {
    private boolean enabled;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }
}
