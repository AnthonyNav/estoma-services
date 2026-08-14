package com.estoma.washadministration.application.priority;

import java.time.Instant;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public final class ResourcePriorityBootstrapRunner implements ApplicationRunner {

  private final ResourcePriorityConfigurator configurator;

  public ResourcePriorityBootstrapRunner(ResourcePriorityConfigurator configurator) {
    this.configurator = configurator;
  }

  @Override
  public void run(ApplicationArguments args) {
    configurator.configureAll(Instant.now());
  }
}
