package com.estoma.washadministration.application.port;

/**
 * Structured-data serialization needed by durable persistence.
 *
 * <p>The Application contract is framework-neutral; Jackson types never cross this port.
 */
public interface JsonCodec {

  String write(Object value);

  Object parse(String value);

  /** Returns a stable representation suitable for a functional request fingerprint. */
  String canonical(Object value);
}
