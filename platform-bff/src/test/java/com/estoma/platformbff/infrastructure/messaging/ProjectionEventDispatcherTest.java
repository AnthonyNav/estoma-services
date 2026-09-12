package com.estoma.platformbff.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.projection.ProjectionEvent;
import com.estoma.platformbff.application.projection.ProjectionEventRuntime;
import com.estoma.platformbff.contracts.messaging.EventEnvelope;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ProjectionEventDispatcherTest {
  @Mock ProjectionEventRuntime runtime;

  @Test
  void dispatchPreservesExplicitJsonNullWithoutTurningItIntoATechnicalFailure() throws Exception {
    var payload = JsonMapper.builder().build().readTree("""
        {"personId":"f09ee70b-e718-4a7d-84bf-a91fb5f26e8d","preferredName":null}
        """);
    EventEnvelope envelope = new EventEnvelope(
        UUID.randomUUID(), "PersonUpdated", 1, MessageKind.EVENT, "users", Instant.now(),
        UUID.randomUUID(), UUID.randomUUID(), null, null, UUID.randomUUID(), 2, null, payload);
    when(runtime.dispatch(any())).thenReturn(ProjectionEventRuntime.DispatchOutcome.APPLIED);

    ProjectionEventDispatcher.DispatchOutcome outcome =
        new ProjectionEventDispatcher(runtime).dispatch(envelope);

    assertThat(outcome).isEqualTo(ProjectionEventDispatcher.DispatchOutcome.APPLIED);
    ArgumentCaptor<ProjectionEvent> captured = ArgumentCaptor.forClass(ProjectionEvent.class);
    verify(runtime).dispatch(captured.capture());
    assertThat(captured.getValue().payload()).containsEntry("preferredName", null);
  }
}
