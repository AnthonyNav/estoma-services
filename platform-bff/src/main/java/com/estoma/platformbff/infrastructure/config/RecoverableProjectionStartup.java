package com.estoma.platformbff.infrastructure.config;

import com.estoma.platformbff.application.projection.AccountPresentationRecoveryService;
import com.estoma.platformbff.application.projection.AuthorizationRecoveryService;
import com.estoma.platformbff.application.projection.SystemCatalogRecoveryService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Starts the owner snapshot obligations exactly once for a pristine BFF projection database. */
@Component
@Order(20)
@ConditionalOnProperty(prefix = "platform.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RecoverableProjectionStartup implements ApplicationRunner {
  private final AuthorizationRecoveryService authorization;
  private final AccountPresentationRecoveryService accountPresentation;
  private final SystemCatalogRecoveryService systemCatalog;
  private final PlatformProperties properties;

  public RecoverableProjectionStartup(AuthorizationRecoveryService authorization,
      AccountPresentationRecoveryService accountPresentation, SystemCatalogRecoveryService systemCatalog,
      PlatformProperties properties) {
    this.authorization = authorization;
    this.accountPresentation = accountPresentation;
    this.systemCatalog = systemCatalog;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    startAuthorizationIfBootstrapping();
    accountPresentation.startFullIfBootstrapping();
    systemCatalog.startFullIfBootstrapping();
    for (String systemCode : properties.recovery().systemCatalogRefreshCodes()) {
      systemCatalog.startEntity(systemCode);
    }
  }

  private void startAuthorizationIfBootstrapping() {
    // Existing Authorization recovery owns the same initial status. It needs no new snapshot wire contract.
    // A second process can race here safely because later generations fence stale Results.
    authorization.startFullIfBootstrapping();
  }
}
