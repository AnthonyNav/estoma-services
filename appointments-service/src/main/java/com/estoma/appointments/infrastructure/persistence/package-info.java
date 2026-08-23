/**
 * JDBC adapters use a stable lock order for multi-aggregate application
 * transactions: appointment_time_slots, appointment_capacity_projections,
 * exceptional_appointment_authorizations, appointments, then execution
 * evidence/technical rows. Single-row optimistic updates remain conditional
 * on the persisted version, and claimers use FOR UPDATE SKIP LOCKED.
 */
package com.estoma.appointments.infrastructure.persistence;
