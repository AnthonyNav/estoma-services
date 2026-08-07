package com.estoma.users.application.command;

import java.util.UUID;

/** The as-received {@code UpdatePerson} payload, before domain validation (commands.md §3). */
public record UpdatePersonCommand(
    UUID personId,
    int expectedVersion,
    String firstName,
    String paternalSurname,
    String maternalSurname,
    String institutionalEmail,
    String phone)
    implements PersonCommand {}
