package com.estoma.authorization.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.estoma.authorization.application.port.SystemCatalogRepository;
import com.estoma.authorization.domain.model.CatalogStatus;
import com.estoma.authorization.domain.model.SystemCatalog;
import com.estoma.authorization.domain.model.SystemCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SystemCatalogSnapshotQueryServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

  @Test
  void returnsVersionedPointAndKeysetPagesOnlyToBff() {
    SystemCatalogRepository systems = mock(SystemCatalogRepository.class);
    SystemCatalog wash = catalog(SystemCode.LAVADO_ULTRASONICO, CatalogStatus.ACTIVE, 2);
    SystemCatalog practices = catalog(SystemCode.PRACTICAS_PROFESIONALES, CatalogStatus.INACTIVE, 7);
    when(systems.findByCode(SystemCode.LAVADO_ULTRASONICO.name())).thenReturn(Optional.of(wash));
    when(systems.findAll()).thenReturn(List.of(practices, wash));
    SystemCatalogSnapshotQueryService service = new SystemCatalogSnapshotQueryService(
        systems, Clock.fixed(NOW, ZoneOffset.UTC));

    var point = service.get("bff", SystemCode.LAVADO_ULTRASONICO);
    assertThat(point.status()).isEqualTo(SystemCatalogSnapshotQueryService.Status.FOUND);
    assertThat(point.items()).singleElement().satisfies(item -> {
      assertThat(item.systemId()).isEqualTo(wash.id());
      assertThat(item.status()).isEqualTo(CatalogStatus.ACTIVE);
      assertThat(item.sourceVersion()).isEqualTo(2);
    });

    var firstPage = service.list("bff", null, 1);
    assertThat(firstPage.status()).isEqualTo(SystemCatalogSnapshotQueryService.Status.SUCCEEDED);
    assertThat(firstPage.items()).extracting(item -> item.systemCode())
        .containsExactly(SystemCode.LAVADO_ULTRASONICO);
    assertThat(firstPage.hasMore()).isTrue();
    assertThat(firstPage.nextCursor()).isEqualTo(SystemCode.LAVADO_ULTRASONICO.name());

    var secondPage = service.list("bff", firstPage.nextCursor(), 1);
    assertThat(secondPage.items()).extracting(item -> item.systemCode())
        .containsExactly(SystemCode.PRACTICAS_PROFESIONALES);
    assertThat(secondPage.items()).extracting(item -> item.sourceVersion()).containsExactly(7);
    assertThat(secondPage.hasMore()).isFalse();

    assertThatThrownBy(() -> service.get("academic", SystemCode.LAVADO_ULTRASONICO))
        .isInstanceOf(AuthorizationSnapshotQueryService.QueryRejectedException.class)
        .hasMessage("QUERY_PRODUCER_NOT_ALLOWED");
  }

  private static SystemCatalog catalog(SystemCode code, CatalogStatus status, int version) {
    return new SystemCatalog(
        UUID.randomUUID(), code, code.name(), status, version, NOW, NOW);
  }
}
