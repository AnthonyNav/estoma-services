package com.estoma.communications.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class RabbitMqConfigurationTest {

  @Test
  void configuresVirtualHostFromEnvironmentAndDefaultsToLocalRoot() {
    assertThat(resolveVirtualHost(Map.of())).isEqualTo("/");
    assertThat(resolveVirtualHost(Map.of("COMM_RABBITMQ_VIRTUAL_HOST", "estoma-develop")))
        .isEqualTo("estoma-develop");
  }

  private String resolveVirtualHost(Map<String, Object> environmentValues) {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("communications-test-environment", environmentValues));
    environment
        .getPropertySources()
        .addLast(new MapPropertySource("communications-application", applicationProperties()));
    return environment.resolveRequiredPlaceholders(
        environment.getRequiredProperty("spring.rabbitmq.virtual-host"));
  }

  private Map<String, Object> applicationProperties() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));
    Properties properties = yaml.getObject();
    Map<String, Object> values = new HashMap<>();
    properties.forEach((key, value) -> values.put(String.valueOf(key), value));
    return values;
  }
}
