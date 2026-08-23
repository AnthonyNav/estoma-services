package com.estoma.washexecution.application.command;

public record CommandRequest(CommandMetadata metadata, WashExecutionCommand command) {}
