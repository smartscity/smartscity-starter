package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.IdGenerator;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Assembles the default generator while respecting user beans and explicit DataSource opt-in.
 *
 * @author jack
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
    })
@ConditionalOnProperty(
    prefix = "smartscity.id",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(IdProperties.class)
public class IdAutoConfiguration {
  /**
   * Registers and starts the application generator after binding its configuration.
   *
   * <p>Spring owns the returned generator and calls close at context shutdown. An application
   * DataSource is resolved only after explicit opt-in and remains owned by the application.
   *
   * @param properties bound settings; diagnostic defaults are filled from the environment
   * @param dataSources provider used only for opted-in unique or primary DataSource selection
   * @param environment source of optional application-name and hostname hints
   * @return the ready generator whose lifecycle belongs to this application context
   * @throws com.smartscity.id.api.IdException if configuration or external startup fails
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(IdGenerator.class)
  public IdGenerator smartscityIdGenerator(
      IdProperties properties,
      ObjectProvider<DataSource> dataSources,
      org.springframework.core.env.Environment environment) {
    if (properties.getInstance().getApplicationName() == null) {
      properties
          .getInstance()
          .setApplicationName(environment.getProperty("spring.application.name"));
    }
    if (properties.getInstance().getHostname() == null) {
      properties.getInstance().setHostname(environment.getProperty("HOSTNAME"));
    }
    DataSource supplied =
        properties.getDatabase().isUseApplicationDataSource() ? dataSources.getIfUnique() : null;
    return new IdGeneratorFactory().create(properties, supplied);
  }
}
