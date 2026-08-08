/**
 * Adapters that translate a consumed integration event into a self-issued
 * durable command, in-process, when a documented condition is met (for
 * example, {@code AppointmentCancelled} -&gt; {@code RequestCommunication}
 * when {@code cancellationType = CLINIC} and {@code cancellationSubreason =
 * CAPACITY_LOSS}). No equivalent package exists in authentication-service:
 * this service is the first to need it (system_contract.md "Internal
 * Architecture").
 */
package com.estoma.communications.application.adapter;
