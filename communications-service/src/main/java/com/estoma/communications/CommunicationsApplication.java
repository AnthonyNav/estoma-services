package com.estoma.communications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * {@code UserDetailsServiceAutoConfiguration} is excluded: this service has
 * no authenticated HTTP users (see {@link
 * com.estoma.communications.infrastructure.config.HttpSecurityConfig}), and
 * leaving it enabled generates and logs a random password on every boot for
 * no reason.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class CommunicationsApplication {

  public static void main(String[] args) {
    SpringApplication.run(CommunicationsApplication.class, args);
  }
}
