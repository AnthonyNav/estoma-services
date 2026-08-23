package com.estoma.appointments.application.recovery;

import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.WashExecutionEvidenceRepository;
import com.estoma.appointments.application.port.out.WashExecutionTransitionRepository;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentStatus;
import com.estoma.appointments.domain.model.CancellationSubreason;
import com.estoma.appointments.domain.model.CancellationType;
import com.estoma.appointments.domain.service.AppointmentEvidenceMerger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Converges Appointment-owned execution evidence/state with an authoritative current-state snapshot. */
@Service
public class WashExecutionSnapshotReconciler {
  private final AppointmentRepository appointments;
  private final WashExecutionEvidenceRepository evidence;
  private final WashExecutionTransitionRepository transitions;

  public WashExecutionSnapshotReconciler(AppointmentRepository appointments,
      WashExecutionEvidenceRepository evidence, WashExecutionTransitionRepository transitions) {
    this.appointments = appointments;
    this.evidence = evidence;
    this.transitions = transitions;
  }

  @Transactional
  public void reconcile(ExecutionSnapshot snapshot, Instant snapshotGeneratedAt) {
    Appointment current = appointments.findByIdForUpdate(snapshot.appointmentId())
        .orElseThrow(() -> new IllegalStateException(
            "Execution snapshot references unknown Appointment " + snapshot.appointmentId()));

    if (snapshot.arrivedAt() != null) {
      evidence.recordArrival(new WashExecutionEvidenceRepository.EvidenceRecord(
          synthetic(snapshot,"arrival"),snapshot.washExecutionId(),snapshot.appointmentId(),
          snapshot.arrivedAt(),snapshot.executionVersion(),null,null,synthetic(snapshot,"arrival"),
          "SNAPSHOT_ARRIVAL",snapshotGeneratedAt));
    }
    if (snapshot.entryAuthorizedAt() != null) {
      evidence.recordEntryAuthorization(new WashExecutionEvidenceRepository.EvidenceRecord(
          synthetic(snapshot,"entry"),snapshot.washExecutionId(),snapshot.appointmentId(),
          null,null,snapshot.entryAuthorizedAt(),snapshot.executionVersion(),synthetic(snapshot,"entry"),
          "SNAPSHOT_ENTRY_AUTHORIZED",snapshotGeneratedAt));
    }

    var latest = transitions.findLatestForExecution(snapshot.washExecutionId()).orElse(null);
    if (latest != null && latest.executionVersion() >= snapshot.executionVersion()) return;

    if (snapshot.entryAuthorizedAt() != null && current.entryAuthorizedAt() == null) {
      current = update(current, AppointmentEvidenceMerger.addEntryAuthorization(current,
          snapshot.entryAuthorizedAt()));
    }

    current = switch (snapshot.status()) {
      case "PENDING_ENTRY" -> requireState(current, AppointmentStatus.SCHEDULED, snapshot);
      case "PENDING_REASSIGNMENT" -> requireState(current, AppointmentStatus.SCHEDULED, snapshot);
      case "IN_PROGRESS", "EXIT_SUBMITTED" -> ensureStarted(current, snapshot);
      case "COMPLETED" -> ensureCompleted(current, snapshot);
      case "ENTRY_REJECTED" -> ensureRejected(current, snapshot);
      case "CANCELLED" -> ensureCancelled(current, snapshot);
      default -> throw new IllegalStateException("unsupported WashExecution snapshot status " + snapshot.status());
    };

    transitions.record(new WashExecutionTransitionRepository.TransitionRecord(
        synthetic(snapshot,"state"),snapshot.washExecutionId(),snapshot.appointmentId(),
        "SNAPSHOT_" + snapshot.status(),snapshot.executionVersion(),synthetic(snapshot,"state"),
        current.status().name(),actionAt(snapshot,snapshotGeneratedAt),snapshotGeneratedAt));
  }

  private Appointment ensureStarted(Appointment current, ExecutionSnapshot snapshot) {
    if (current.status() == AppointmentStatus.IN_PROGRESS) return current;
    if (current.status() != AppointmentStatus.SCHEDULED) return requireState(current,
        AppointmentStatus.IN_PROGRESS,snapshot);
    if (snapshot.startedAt() == null) throw incomplete(snapshot,"startedAt");
    return update(current,current.start(snapshot.startedAt()));
  }

  private Appointment ensureCompleted(Appointment current, ExecutionSnapshot snapshot) {
    if (current.status() == AppointmentStatus.COMPLETED) return current;
    if (current.status() == AppointmentStatus.SCHEDULED) current = ensureStarted(current,snapshot);
    if (current.status() != AppointmentStatus.IN_PROGRESS) return requireState(current,
        AppointmentStatus.COMPLETED,snapshot);
    if (snapshot.completedAt() == null) throw incomplete(snapshot,"completedAt");
    return update(current,current.complete(snapshot.completedByAccountId(),snapshot.completedAt()));
  }

  private Appointment ensureRejected(Appointment current, ExecutionSnapshot snapshot) {
    if (current.status() == AppointmentStatus.ENTRY_REJECTED) return current;
    if (current.status() != AppointmentStatus.SCHEDULED) return requireState(current,
        AppointmentStatus.ENTRY_REJECTED,snapshot);
    if (snapshot.rejectedAt() == null) throw incomplete(snapshot,"rejectedAt");
    if (snapshot.rejectionReason() == null || snapshot.rejectionReason().isBlank()) {
      throw incomplete(snapshot,"rejectionReason");
    }
    return update(current,current.rejectEntry(snapshot.rejectionReason(),snapshot.rejectedByAccountId(),
        snapshot.rejectedAt()));
  }

  private Appointment ensureCancelled(Appointment current, ExecutionSnapshot snapshot) {
    if (current.status() == AppointmentStatus.CANCELLED) {
      if (current.cancellationType() != CancellationType.CLINIC) {
        throw conflict(current,snapshot);
      }
      return current;
    }
    if (current.status() == AppointmentStatus.SCHEDULED && snapshot.startedAt() != null) {
      current = ensureStarted(current,snapshot);
    }
    if (current.status() != AppointmentStatus.SCHEDULED
        && current.status() != AppointmentStatus.IN_PROGRESS) return requireState(current,
            AppointmentStatus.CANCELLED,snapshot);
    if (snapshot.cancelledAt() == null) throw incomplete(snapshot,"cancelledAt");
    if (!"CLINIC".equals(snapshot.cancellationType())) throw incomplete(snapshot,"cancellationType=CLINIC");
    if (!"CAPACITY_LOSS".equals(snapshot.cancellationSubreason())) throw incomplete(snapshot,"cancellationSubreason=CAPACITY_LOSS");
    return update(current,current.cancel(CancellationType.CLINIC,CancellationSubreason.CAPACITY_LOSS,
        snapshot.cancellationReason(),snapshot.cancelledByAccountId(),snapshot.cancelledAt()));
  }

  private Appointment requireState(Appointment current, AppointmentStatus expected,
      ExecutionSnapshot snapshot) {
    if (current.status() == expected) return current;
    throw conflict(current,snapshot);
  }

  private Appointment update(Appointment before, Appointment after) {
    if (after == before) return before;
    if (!appointments.updateIfVersion(after,before.version())) {
      throw new IllegalStateException("Appointment changed during Execution snapshot reconciliation");
    }
    return after;
  }

  private static IllegalStateException incomplete(ExecutionSnapshot snapshot, String field) {
    return new IllegalStateException("Execution snapshot " + snapshot.washExecutionId()
        + " is missing required " + field + " for status " + snapshot.status());
  }
  private static IllegalStateException conflict(Appointment current, ExecutionSnapshot snapshot) {
    return new IllegalStateException("Execution snapshot state " + snapshot.status()
        + " conflicts with Appointment state " + current.status());
  }
  private static Instant actionAt(ExecutionSnapshot s, Instant fallback) {
    if (s.cancelledAt()!=null) return s.cancelledAt();
    if (s.completedAt()!=null) return s.completedAt();
    if (s.rejectedAt()!=null) return s.rejectedAt();
    if (s.startedAt()!=null) return s.startedAt();
    if (s.entryAuthorizedAt()!=null) return s.entryAuthorizedAt();
    if (s.arrivedAt()!=null) return s.arrivedAt();
    return fallback;
  }
  private static UUID synthetic(ExecutionSnapshot snapshot, String component) {
    return UUID.nameUUIDFromBytes(("appointments-execution-snapshot:" + component + ":"
        + snapshot.washExecutionId() + ":" + snapshot.executionVersion())
        .getBytes(StandardCharsets.UTF_8));
  }

  public record ExecutionSnapshot(UUID washExecutionId, UUID appointmentId, String status,
      long executionVersion, Instant arrivedAt, Instant startedAt, Instant entryAuthorizedAt,
      Instant rejectedAt, String rejectionReason, UUID rejectedByAccountId,
      Instant completedAt, UUID completedByAccountId, Instant cancelledAt,
      String cancellationType, String cancellationSubreason, String cancellationReason,
      UUID cancelledByAccountId) {}
}
