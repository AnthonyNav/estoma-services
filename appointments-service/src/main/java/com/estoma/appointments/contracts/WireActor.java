package com.estoma.appointments.contracts;

import java.util.UUID;

public record WireActor(String type, UUID accountId, String service) {}
