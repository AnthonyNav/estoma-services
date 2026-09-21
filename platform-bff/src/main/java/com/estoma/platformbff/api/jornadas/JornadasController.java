package com.estoma.platformbff.api.jornadas;

import com.estoma.platformbff.application.commands.PublicCommandAdapter;
import com.estoma.platformbff.application.jornadas.JornadasQueryService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import com.estoma.platformbff.contracts.http.jornadas.JornadaHttpRequests.CancelarJornadaRequest;
import com.estoma.platformbff.contracts.http.jornadas.JornadaHttpRequests.ConfigurarTipoJornadaRequest;
import com.estoma.platformbff.contracts.http.jornadas.JornadaHttpRequests.EditarJornadaRequest;
import com.estoma.platformbff.contracts.http.jornadas.JornadaHttpRequests.PublicarJornadaRequest;
import com.estoma.platformbff.contracts.http.jornadas.JornadaResponse;
import com.estoma.platformbff.contracts.http.jornadas.TipoJornadaResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public surface for the Jornadas catalog (Prácticas Profesionales). {@code GET /jornadas} is
 * read-only and requires no authentication (see {@code SecurityConfig}) — same trust boundary as
 * other public catalog data; the published Jornada listing carries no personal data. Every other
 * endpoint here requires an authenticated session; writes additionally require {@code
 * ADMINISTRADOR_PRACTICAS} (or {@code SUPERADMIN}) with active access to the {@code
 * PRACTICAS_PROFESIONALES} system — see {@code LocalAuthorizationService.requirePracticesAdministrator}.
 */
@RestController
@RequestMapping("/api/v1/jornadas")
public class JornadasController {
  private final JornadasQueryService jornadas;
  private final PublicCommandAdapter commands;
  private final LocalAuthorizationService authorization;

  public JornadasController(JornadasQueryService jornadas, PublicCommandAdapter commands,
      LocalAuthorizationService authorization) {
    this.jornadas = jornadas;
    this.commands = commands;
    this.authorization = authorization;
  }

  @GetMapping
  public List<JornadaResponse> list() {
    return jornadas.listJornadas();
  }

  @GetMapping("/tipos")
  public List<TipoJornadaResponse> listTipos() {
    return jornadas.listTipos();
  }

  @PostMapping
  public ResponseEntity<AcceptedOperationResponse> publicar(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody PublicarJornadaRequest request) {
    UUID administradorId = authorization.requirePracticesAdministrator();
    return accepted(commands.submit(administradorId, "practicas-publicar-jornada", "PublicarJornada",
        payload(
            "tipoJornadaId", request.tipoJornadaId(),
            "nombre", request.nombre(),
            "fecha", request.fecha(),
            "horaInicio", request.horaInicio(),
            "horaFin", request.horaFin(),
            "lugar", request.lugar(),
            "cupoTotal", request.cupoTotal(),
            "fechaLimiteDocumentos", request.fechaLimiteDocumentos(),
            "descripcion", request.descripcion()),
        idempotencyKey, null, null));
  }

  @PutMapping("/{jornadaId}")
  public ResponseEntity<AcceptedOperationResponse> editar(@PathVariable UUID jornadaId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody EditarJornadaRequest request) {
    UUID administradorId = authorization.requirePracticesAdministrator();
    return accepted(commands.submit(administradorId, "practicas-editar-jornada", "EditarJornada",
        payload(
            "nombre", request.nombre(),
            "fecha", request.fecha(),
            "horaInicio", request.horaInicio(),
            "horaFin", request.horaFin(),
            "lugar", request.lugar(),
            "cupoTotal", request.cupoTotal(),
            "fechaLimiteDocumentos", request.fechaLimiteDocumentos(),
            "descripcion", request.descripcion()),
        idempotencyKey, jornadaId, (int) request.expectedVersion()));
  }

  @PostMapping("/{jornadaId}/cancelar")
  public ResponseEntity<AcceptedOperationResponse> cancelar(@PathVariable UUID jornadaId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CancelarJornadaRequest request) {
    UUID administradorId = authorization.requirePracticesAdministrator();
    return accepted(commands.submit(administradorId, "practicas-cancelar-jornada", "CancelarJornada",
        payload("motivo", request.motivo()), idempotencyKey, jornadaId, null));
  }

  /**
   * Tipos de Jornada (Intramuros, Externa, etc. — HU-J-22) are configured through this generic
   * command, not hardcoded: semestres permitidos per tipo remain an open Facultad decision (PRD
   * Q02). No catalog listing is exposed yet — the caller must retain the returned {@code
   * tipoJornadaId} (see the operation's polled result) to reference it from {@code
   * PublicarJornadaRequest.tipoJornadaId}.
   */
  @PostMapping("/tipos")
  public ResponseEntity<AcceptedOperationResponse> configurarTipo(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ConfigurarTipoJornadaRequest request) {
    UUID administradorId = authorization.requirePracticesAdministrator();
    return accepted(commands.submit(administradorId, "practicas-configurar-tipo-jornada",
        "ConfigurarTipoJornada",
        payload(
            "tipoJornadaId", request.tipoJornadaId(),
            "nombre", request.nombre(),
            "semestresPermitidos", request.semestresPermitidos()),
        idempotencyKey, request.tipoJornadaId(), null));
  }

  private static ResponseEntity<AcceptedOperationResponse> accepted(AcceptedOperationResponse response) {
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
  }

  private static Map<String, Object> payload(Object... values) {
    if (values.length % 2 != 0) throw new IllegalArgumentException("payload entries must be pairs");
    Map<String, Object> payload = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      payload.put((String) values[index], values[index + 1]);
    }
    return payload;
  }
}
