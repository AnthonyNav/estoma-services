package com.estoma.platformbff;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PlatformBffApplication {
  @Bean
  Clock bffClock() {
    return Clock.systemUTC();
  }

  public static void main(String[] args) { SpringApplication.run(PlatformBffApplication.class, args); }
}
