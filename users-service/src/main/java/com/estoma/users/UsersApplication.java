package com.estoma.users;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code UserDetailsServiceAutoConfiguration} is excluded: this service
 * has no authenticated HTTP users (see {@link com.estoma.users.config.HttpSecurityConfig}),
 * and leaving it enabled generates and logs a random password on every
 * boot for no reason.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class UsersApplication {

  public static void main(String[] args) {
    SpringApplication.run(UsersApplication.class, args);
  }
}
