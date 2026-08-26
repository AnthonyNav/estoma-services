package com.estoma.jornadas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JornadasApplication {
  public static void main(String[] args) {
    SpringApplication.run(JornadasApplication.class, args);
  }
}
