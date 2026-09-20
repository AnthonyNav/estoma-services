package com.estoma.platformbff.api.registros;

import com.estoma.platformbff.application.commands.PublicCommandAdapter;
import com.estoma.platformbff.application.registros.RegistrosQueryService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import com.estoma.platformbff.contracts.http.registros.RegistroHttpRequests.CrearRegistroRequest;
import com.estoma.platformbff.contracts.http.registros.RegistroHttpRequests.RechazarRegistroRequest;
import com.estoma.platformbff.contracts.http.registros.RegistroResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prácticas Profesionales — Registros. The Alumno's own pre-registro/cancelación requires {@code
 * ADMINISTRADOR_PRACTICAS}-free {@code ALUMNO} access (see {@code requirePracticesStudent}); the
 * Pasante's validation queue and decisions require {@code ADMINISTRADOR_PRACTICAS} (or {@code
 * SUPERADMIN}) — both scoped to the {@code PRACTICAS_PROFESIONALES} system.
 */
@RestController
@RequestMapping("/api/v1/registros")
public class RegistrosController {
  private final RegistrosQueryService registros;
  private final PublicCommandAdapter commands;
  private final LocalAuthorizationService authorization;

  public RegistrosController(RegistrosQueryService registros, PublicCommandAdapter commands,
      LocalAuthorizationService authorization) {
    this.registros = registros;
    this.commands = commands;
    this.authorization = authorization;
  }

  @GetMapping("/mios")
  public List<RegistroResponse> misRegistros() {
    UUID alumnoId = authorization.requirePracticesStudent();
    return registros.misRegistros(alumnoId);
  }

  @GetMapping("/jornada/{jornadaId}")
  public List<RegistroResponse> registrosDeJornada(@PathVariable UUID jornadaId) {
    authorization.requirePracticesAdministrator();
    return registros.registrosDeJornada(jornadaId);
  }

  @PostMapping
  public ResponseEntity<AcceptedOperationResponse> crear(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CrearRegistroRequest request) {
    UUID alumnoId = authorization.requirePracticesStudent();
    int semestreAlumno = registros.currentSemester(alumnoId)
        .orElseThrow(() -> new IllegalArgumentException("current semester is not available for this account"));
    return accepted(commands.submit(alumnoId, "practicas-crear-registro", "CrearRegistro",
        payload("jornadaId", request.jornadaId(), "alumnoId", alumnoId, "semestreAlumno", semestreAlumno),
        idempotencyKey, null, null));
  }

  @PostMapping("/{registroId}/confirmar")
  public ResponseEntity<AcceptedOperationResponse> confirmar(@PathVariable UUID registroId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    UUID administradorId = authorization.requirePracticesAdministrator();
    return accepted(commands.submit(administradorId, "practicas-confirmar-registro", "ConfirmarRegistro",
        payload("registroId", registroId), idempotencyKey, registroId, null));
  }

  @PostMapping("/{registroId}/rechazar")
  public ResponseEntity<AcceptedOperationResponse> rechazar(@PathVariable UUID registroId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody RechazarRegistroRequest request) {
    UUID administradorId = authorization.requirePracticesAdministrator();
    return accepted(commands.submit(administradorId, "practicas-rechazar-registro", "RechazarRegistro",
        payload("registroId", registroId, "motivo", request.motivo()), idempotencyKey, registroId, null));
  }

  @PostMapping("/{registroId}/cancelar")
  public ResponseEntity<AcceptedOperationResponse> cancelar(@PathVariable UUID registroId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    authorization.requirePracticesStudentOwnsRegistro(registroId);
    UUID alumnoId = authorization.requireAuthenticatedAccount();
    return accepted(commands.submit(alumnoId, "practicas-cancelar-registro", "CancelarRegistroPorAlumno",
        payload("registroId", registroId, "alumnoId", alumnoId), idempotencyKey, registroId, null));
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
