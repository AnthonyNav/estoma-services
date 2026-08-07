package com.estoma.users.application.command;

import java.util.UUID;

/**
 * The as-received {@code CreatePerson} payload, before domain validation.
 * {@code personId} is caller-generated (commands.md §2).
 */
public record CreatePersonCommand(
    UUID personId,
    String firstName,
    String paternalSurname,
    String maternalSurname,
    String institutionalEmail,
    String phone)
    implements PersonCommand {}
