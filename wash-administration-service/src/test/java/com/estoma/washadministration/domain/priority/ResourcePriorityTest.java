package com.estoma.washadministration.domain.priority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washadministration.domain.resource.CabinType;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourcePriorityTest {

  @Test
  void acceptsExactlyTheFiveFixedRows() {
    assertThat(ResourcePriority.createNew(UUID.randomUUID(), AppointmentType.NORMAL, CabinType.NORMAL, UsageMode.PREFERRED, 1, Instant.now())).isNotNull();
    assertThat(ResourcePriority.createNew(UUID.randomUUID(), AppointmentType.NORMAL, CabinType.JOURNEY, UsageMode.ALTERNATIVE, 2, Instant.now())).isNotNull();
    assertThat(ResourcePriority.createNew(UUID.randomUUID(), AppointmentType.JOURNEY, CabinType.JOURNEY, UsageMode.PREFERRED, 1, Instant.now())).isNotNull();
    assertThat(ResourcePriority.createNew(UUID.randomUUID(), AppointmentType.JOURNEY, CabinType.NORMAL, UsageMode.ALTERNATIVE, 2, Instant.now())).isNotNull();
    assertThat(ResourcePriority.createNew(UUID.randomUUID(), AppointmentType.IMMUNOCOMPROMISED, CabinType.IMMUNOCOMPROMISED, UsageMode.EXCLUSIVE, 1, Instant.now())).isNotNull();
  }

  @Test
  void rejectsUnsupportedCombination() {
    assertThatThrownBy(
            () -> ResourcePriority.createNew(UUID.randomUUID(), AppointmentType.NORMAL, CabinType.IMMUNOCOMPROMISED, UsageMode.PREFERRED, 1, Instant.now()))
        .isInstanceOf(DomainRuleViolationException.class);
  }
}
