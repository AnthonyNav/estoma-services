package com.estoma.washexecution.application.port;

public interface JsonCodec {
  String write(Object value);
  String fingerprint(Object value);
}
