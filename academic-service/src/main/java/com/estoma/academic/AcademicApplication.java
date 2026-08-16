package com.estoma.academic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.estoma.academic.config.AcademicOutboxProperties;
import com.estoma.academic.importing.StudentImportProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({AcademicOutboxProperties.class, StudentImportProperties.class})
public class AcademicApplication {
  public static void main(String[] args) { SpringApplication.run(AcademicApplication.class, args); }
}
