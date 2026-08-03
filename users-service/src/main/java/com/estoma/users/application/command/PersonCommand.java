package com.estoma.users.application.command;

/** The four commands {@code users-service} accepts (commands.md). */
public sealed interface PersonCommand
    permits CreatePersonCommand, UpdatePersonCommand, ActivatePersonCommand, DeactivatePersonCommand {}
