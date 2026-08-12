package com.estoma.academic.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class InfrastructureConfig {
  @Bean
  @Primary
  RabbitTemplate requiredRouteRabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMandatory(true);
    return template;
  }

  @Bean
  RabbitTemplate eventRabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMandatory(false);
    return template;
  }

  @Bean
  SimpleRabbitListenerContainerFactory manualAckContainerFactory(
      ConnectionFactory connectionFactory,
      @Value("${estoma.messaging.listeners-enabled:true}") boolean listenersEnabled,
      @Value("${spring.rabbitmq.listener.simple.auto-startup:true}") boolean autoStartup) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setDefaultRequeueRejected(false);
    factory.setAutoStartup(listenersEnabled && autoStartup);
    return factory;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/health", "/health/**", "/metrics", "/info").permitAll().anyRequest().denyAll())
        .csrf(csrf -> csrf.disable()).httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }
}
