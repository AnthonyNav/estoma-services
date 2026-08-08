package com.estoma.communications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code UserDetailsServiceAutoConfiguration} is excluded: this service has
 * no authenticated HTTP users (see {@link
 * com.estoma.communications.infrastructure.config.HttpSecurityConfig}), and
 * leaving it enabled generates and logs a random password on every boot for
 * no reason.
 *
 * <p>{@code @EnableScheduling} powers {@code EmailDeliveryWorker} and
 * {@code InboxExpirationScheduler}, both in-process triggers
 * (system_contract.md "Internal Architecture").
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class CommunicationsApplication {

  public static void main(String[] args) {
    SpringApplication.run(CommunicationsApplication.class, args);
  }
}
