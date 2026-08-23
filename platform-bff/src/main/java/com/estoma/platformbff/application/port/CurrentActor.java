package com.estoma.platformbff.application.port;

import java.util.Optional;
import java.util.UUID;

public interface CurrentActor {
  Optional<UUID> accountId();
}
