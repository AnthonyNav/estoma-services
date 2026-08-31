package com.estoma.platformbff.infrastructure.config;

import com.estoma.platformbff.infrastructure.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
  private final JwtAuthenticationFilter jwt;

  public SecurityConfig(JwtAuthenticationFilter jwt) {
    this.jwt = jwt;
  }

  @Bean
  SecurityFilterChain platformSecurity(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(
                "/health", "/version", "/actuator/health", "/actuator/health/**",
                "/actuator/prometheus",
                "/api/v1/auth/login", "/api/v1/auth/refresh",
                "/api/v1/auth/password-recovery",
                "/api/v1/auth/password-recovery/validate",
                "/api/v1/auth/password-recovery/reset",
                "/api/v1/openapi",
                "/api/v1/operations/**",
                "/api/v1/jornadas")
              .permitAll()
            .requestMatchers("/api/v1/auth/logout", "/api/v1/me/password")
              .authenticated()
            .requestMatchers("/api/v1/**")
              .hasAuthority("AUTH_STATE_NORMAL")
            .anyRequest().permitAll())
        .build();
  }
}
