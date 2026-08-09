package com.estoma.communications.contracts.command;

import java.util.UUID;

public record ResolveIndeterminateEmailDeliveryCommand(UUID emailDeliveryId, Resolution resolution, boolean riskAcknowledged, String reason) {
  public ResolveIndeterminateEmailDeliveryCommand(UUID emailDeliveryId, Resolution resolution) {
    this(emailDeliveryId, resolution, false, null);
  }
  public enum Resolution { RECONCILED_ACCEPTED, RECONCILED_FAILED, RESEND_AUTHORIZED }
}
