package com.estoma.platformbff.api.jornadas;

import com.estoma.platformbff.application.jornadas.JornadasQueryService;
import com.estoma.platformbff.contracts.http.jornadas.JornadaResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only public surface for the Jornadas catalog (Prácticas Profesionales). No
 * authentication required — same trust boundary as other public catalog data; the
 * published Jornada listing carries no personal data.
 *
 * <p>Write operations (publish/edit/cancel) are intentionally NOT exposed here yet.
 * They durable-command through {@code PublicCommandAdapter}, which always builds an
 * ACCOUNT-mode {@code ActorContext} tied to a real authenticated session, and operation
 * polling in {@code OperationsController} is actor-scoped — neither has an anonymous or
 * fixed-test-actor path today. Wiring writes safely means either standing up real
 * authentication end to end, or adding a genuine anonymous-command path shared with the
 * rest of the platform; both are out of scope for this read-only V1 slice. See
 * jornadas-service/README.md and registros-service/README.md for the V1 simplifications
 * on the owning services' side.
 */
@RestController
@RequestMapping("/api/v1/jornadas")
public class JornadasController {
  private final JornadasQueryService jornadas;

  public JornadasController(JornadasQueryService jornadas) {
    this.jornadas = jornadas;
  }

  @GetMapping
  public List<JornadaResponse> list() {
    return jornadas.listJornadas();
  }
}
