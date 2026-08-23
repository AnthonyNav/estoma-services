package com.estoma.platformbff.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * PR1 keeps the existing public health surface available. Authentication and
 * JWT enforcement are introduced in PR3, behind the same filter-chain seam.
 */
@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain platformSecurity(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }
}
