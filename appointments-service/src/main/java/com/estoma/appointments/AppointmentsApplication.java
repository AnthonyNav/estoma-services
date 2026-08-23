package com.estoma.appointments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import java.time.Clock;
import com.estoma.appointments.infrastructure.messaging.MessagingProperties;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(MessagingProperties.class)
@EnableScheduling
public class AppointmentsApplication {
  @Bean
  Clock appointmentsClock() { return Clock.systemUTC(); }
  public static void main(String[] args) { SpringApplication.run(AppointmentsApplication.class, args); }
}
