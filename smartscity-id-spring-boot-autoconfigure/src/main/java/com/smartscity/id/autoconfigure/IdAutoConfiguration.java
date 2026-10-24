package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.IdGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import javax.sql.DataSource;

@AutoConfiguration(afterName = {"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
@ConditionalOnProperty(prefix = "smartscity.id", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdProperties.class)
public class IdAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(IdGenerator.class)
    public IdGenerator smartscityIdGenerator(IdProperties properties, ObjectProvider<DataSource> dataSources) {
        return new IdGeneratorFactory().create(properties, dataSources.getIfUnique());
    }
}
