package com.estoma.communications.contracts.consumed;

import java.util.UUID;

/** Owner closure fact used to remove a no-longer-active period from the local read model. */
public record AcademicPeriodClosedPayload(UUID academicPeriodId, String status) {}
