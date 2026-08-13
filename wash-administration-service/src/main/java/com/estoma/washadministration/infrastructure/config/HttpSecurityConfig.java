package com.estoma.washadministration.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** The service has no business HTTP API; only operational GETs are public. */
@Configuration
@EnableWebSecurity
public class HttpSecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/**")
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, "/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/health/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/metrics")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/info")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .csrf(csrf -> csrf.disable())
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(
            headers ->
                headers
                    .frameOptions(frameOptions -> frameOptions.deny())
                    .contentTypeOptions(contentTypeOptions -> {}));
    return http.build();
  }
}
