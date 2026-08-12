package com.estoma.washadministration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class WashAdministrationApplication {

  public static void main(String[] args) {
    SpringApplication.run(WashAdministrationApplication.class, args);
  }
}
