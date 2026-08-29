package com.estoma.platformbff.infrastructure.config;

import com.estoma.platformbff.application.projection.ProjectionGenesisService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Deployment must set both flags only for the initial, attested-fresh environment. */
@Component
public class ProjectionGenesisStartup implements ApplicationRunner {
  private final PlatformProperties properties;
  private final ProjectionGenesisService genesis;

  public ProjectionGenesisStartup(PlatformProperties properties, ProjectionGenesisService genesis) {
    this.properties = properties;
    this.genesis = genesis;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    PlatformProperties.Genesis settings = properties.genesis();
    if (!settings.enabled()) return;
    if (!settings.environmentFresh()) {
      throw new IllegalStateException("BFF genesis requires an explicit fresh-environment attestation");
    }
    genesis.establishFreshBaseline();
  }
}
