package com.estoma.authentication.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Locks down the entire HTTP surface except the documented operational
 * endpoints (system_contract.md "Operational HTTP Interface"): {@code
 * /health}, {@code /health/**}, {@code /metrics}, {@code /info}. Everything
 * else -- including Actuator endpoints not on the exposure allow-list in
 * {@code application.yml} -- is denied.
 *
 * <p>This service has no business REST API and no authenticated HTTP users;
 * every functional operation arrives over RabbitMQ. Sessions are stateless
 * and CSRF/basic-auth/form-login are all disabled accordingly. {@code
 * UserDetailsServiceAutoConfiguration} is excluded in {@code
 * AuthenticationApplication} for the same reason -- it would otherwise
 * generate and log a random password on every boot.
 */
@Configuration
@EnableWebSecurity
public class HttpSecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
