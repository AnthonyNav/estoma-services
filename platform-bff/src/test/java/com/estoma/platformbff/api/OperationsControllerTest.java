package com.estoma.platformbff.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.estoma.platformbff.api.operations.OperationsController;
import com.estoma.platformbff.application.operations.OperationQueryService;
import com.estoma.platformbff.application.port.ClientOperationRepository;
import com.estoma.platformbff.application.port.OperationDataMapper;
import com.estoma.platformbff.domain.operation.ClientOperation;
import com.estoma.platformbff.domain.operation.OperationStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperationsControllerTest {
  @Test
  void accountReceiptIsVisibleToOwnerButHiddenWithoutActor() throws Exception {
    UUID id = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    Instant now = Instant.parse("2026-09-07T12:00:00Z");
    var repository = mock(ClientOperationRepository.class);
    when(repository.findById(id)).thenReturn(Optional.of(new ClientOperation(
        id, owner, "wash-complete", "CompleteWashExecution", "wash-execution",
        "commands.wash-execution", UUID.randomUUID(), "synthetic-intent", OperationStatus.SUCCEEDED,
        null, null, null, now, now, now, now.plusSeconds(3600))));
    var query = new OperationQueryService(repository);
    var mapper = mock(OperationDataMapper.class);
    var ownerMvc = MockMvcBuilders.standaloneSetup(new OperationsController(
        query, () -> Optional.of(owner), mapper)).build();
    ownerMvc.perform(get("/api/v1/operations/{operationId}", id))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .jsonPath("$.operationId").value(id.toString()));
    var anonymousMvc = MockMvcBuilders.standaloneSetup(new OperationsController(
        query, Optional::empty, mapper)).build();
    anonymousMvc.perform(get("/api/v1/operations/{operationId}", id))
        .andExpect(status().isNotFound()).andExpect(content().string(""));
  }

  @Test
  void unknownAndOtherActorsOperationsReturnTheSameEmpty404() throws Exception {
    UUID id = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    var repository = mock(ClientOperationRepository.class);
    var query = new OperationQueryService(repository);
    var mapper = mock(OperationDataMapper.class);
    var mvc = MockMvcBuilders.standaloneSetup(new OperationsController(
        query, () -> Optional.of(owner), mapper)).build();

    when(repository.findById(id)).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/operations/{operationId}", id))
        .andExpect(status().isNotFound()).andExpect(content().string(""))
        .andExpect(header().doesNotExist("Content-Type"));

    Instant now = Instant.parse("2026-09-07T12:00:00Z");
    when(repository.findById(id)).thenReturn(Optional.of(new ClientOperation(
        id, UUID.randomUUID(), "wash-complete", "CompleteWashExecution", "wash-execution",
        "commands.wash-execution", UUID.randomUUID(), "synthetic-intent", OperationStatus.SUCCEEDED,
        null, null, null, now, now, now, now.plusSeconds(3600))));
    mvc.perform(get("/api/v1/operations/{operationId}", id))
        .andExpect(status().isNotFound()).andExpect(content().string(""))
        .andExpect(header().doesNotExist("Content-Type"));
  }
}
