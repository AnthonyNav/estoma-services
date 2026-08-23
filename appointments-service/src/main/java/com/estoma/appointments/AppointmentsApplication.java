package com.estoma.appointments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AppointmentsApplication {
  public static void main(String[] args) { SpringApplication.run(AppointmentsApplication.class, args); }
}
