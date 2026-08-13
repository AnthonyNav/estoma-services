package com.estoma.washadministration.domain.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washadministration.domain.shared.DisplayName;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import com.estoma.washadministration.domain.shared.PositiveCapacity;
import com.estoma.washadministration.domain.shared.ResourceCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void cabinDoesNotExposeCapacityAndRetirementRequiresRetiredTanks() {
    Cabin cabin = Cabin.createNew(UUID.randomUUID(), new ResourceCode("C1"), new DisplayName("Cabin"), CabinType.NORMAL, ACCOUNT_ID, NOW);

    assertThat(Cabin.class.getDeclaredFields()).noneMatch(field -> field.getName().toLowerCase().contains("capacity"));
    assertThatThrownBy(() -> cabin.retire(ACCOUNT_ID, false, NOW.plusSeconds(1)))
        .isInstanceOf(DomainRuleViolationException.class);
    assertThat(cabin.retire(ACCOUNT_ID, true, NOW.plusSeconds(1)).status()).isEqualTo(ResourceStatus.RETIRED);
  }

  @Test
  void cabinDeactivationDoesNotChangeTankState() {
    Cabin cabin = Cabin.createNew(UUID.randomUUID(), new ResourceCode("C1"), new DisplayName("Cabin"), CabinType.NORMAL, ACCOUNT_ID, NOW);
    Tank tank = Tank.createNew(UUID.randomUUID(), cabin.id(), new ResourceCode("T1"), new DisplayName("Tank"), new PositiveCapacity(2), ACCOUNT_ID, NOW);

    assertThat(cabin.deactivate(ACCOUNT_ID, NOW.plusSeconds(1)).status()).isEqualTo(ResourceStatus.INACTIVE);
    assertThat(tank.status()).isEqualTo(ResourceStatus.ACTIVE);
  }

  @Test
  void tankCapacityIsPositiveAndRetiredTankCannotBeReactivated() {
    assertThatThrownBy(() -> new PositiveCapacity(-1)).isInstanceOf(DomainRuleViolationException.class);
    Tank retired =
        Tank.createNew(UUID.randomUUID(), UUID.randomUUID(), new ResourceCode("T1"), new DisplayName("Tank"), new PositiveCapacity(2), ACCOUNT_ID, NOW)
            .retire(ACCOUNT_ID, NOW.plusSeconds(1));

    assertThatThrownBy(() -> retired.activate(ACCOUNT_ID, NOW.plusSeconds(2)))
        .isInstanceOf(DomainRuleViolationException.class);
  }
}
