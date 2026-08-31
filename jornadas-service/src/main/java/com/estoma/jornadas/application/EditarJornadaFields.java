package com.estoma.jornadas.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Every field is optional: null means "not present in this EditarJornada command". */
public record EditarJornadaFields(
    String nombre,
    LocalDate fecha,
    LocalTime horaInicio,
    LocalTime horaFin,
    String lugar,
    Integer cupoTotal,
    LocalDate fechaLimiteDocumentos,
    String descripcion) {

  public List<String> camposPresentes() {
    List<String> campos = new ArrayList<>();
    if (nombre != null) campos.add("nombre");
    if (fecha != null) campos.add("fecha");
    if (horaInicio != null) campos.add("horaInicio");
    if (horaFin != null) campos.add("horaFin");
    if (lugar != null) campos.add("lugar");
    if (cupoTotal != null) campos.add("cupoTotal");
    if (fechaLimiteDocumentos != null) campos.add("fechaLimiteDocumentos");
    if (descripcion != null) campos.add("descripcion");
    return campos;
  }
}
