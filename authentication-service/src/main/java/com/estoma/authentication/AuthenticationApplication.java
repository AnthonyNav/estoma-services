package com.estoma.authentication;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code UserDetailsServiceAutoConfiguration} is excluded: this service has
 * no authenticated HTTP users (see {@link
 * com.estoma.authentication.infrastructure.config.HttpSecurityConfig}), and
 * leaving it enabled generates and logs a random password on every boot for
 * no reason.
 *
 * <p>{@code @EnableScheduling} drives the Outbox Relay's {@code
 * @Scheduled} poll (system_contract.md "Outbox Operational Policy").
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class AuthenticationApplication {

  public static void main(String[] args) {
    SpringApplication.run(AuthenticationApplication.class, args);
  }
}
