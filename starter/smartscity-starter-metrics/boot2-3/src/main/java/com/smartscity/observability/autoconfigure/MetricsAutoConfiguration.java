package com.smartscity.observability.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Binds only metrics configuration without requiring logging, masking or an application name.
 *
 * @author jack
 */
@AutoConfiguration
@EnableConfigurationProperties(MetricsProperties.class)
@ConditionalOnProperty(
    prefix = "smartscity.observability",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class MetricsAutoConfiguration {}
