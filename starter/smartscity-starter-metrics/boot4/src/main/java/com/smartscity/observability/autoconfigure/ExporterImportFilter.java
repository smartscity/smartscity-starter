package com.smartscity.observability.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Prevents exporter classpath presence from starting an unrequested second sender. Explicit
 * application-native exporters remain application-owned when Smartscity is inactive.
 *
 * @author jack
 */
public final class ExporterImportFilter implements AutoConfigurationImportFilter, EnvironmentAware {
  private Environment environment;

  @Override
  public void setEnvironment(Environment e) {
    environment = e;
  }

  @Override
  public boolean[] match(String[] names, AutoConfigurationMetadata metadata) {
    boolean[] out = new boolean[names.length];
    for (int i = 0; i < names.length; i++) {
      out[i] = true;
      String name = names[i];
      if (name == null) {
        continue;
      }
      boolean active =
          flag("smartscity.observability.enabled", true)
              && flag("smartscity.observability.metrics.enabled", true);
      if (name.endsWith("StatsdMetricsExportAutoConfiguration")) {
        boolean owned = active && flag("smartscity.observability.metrics.udp.enabled", false);
        out[i] =
            !owned
                && (flag("management.statsd.metrics.export.enabled", false)
                    || flag("management.metrics.export.statsd.enabled", false));
      }
      if (name.endsWith("PrometheusMetricsExportAutoConfiguration")) {
        out[i] =
            (active && flag("smartscity.observability.metrics.prometheus.enabled", false))
                || flag("management.prometheus.metrics.export.enabled", false)
                || flag("management.metrics.export.prometheus.enabled", false);
      }
    }
    return out;
  }

  private boolean flag(String key, boolean fallback) {
    return environment == null ? fallback : environment.getProperty(key, Boolean.class, fallback);
  }
}
