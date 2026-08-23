package com.estoma.platformbff.application.port;

public interface MessageSerializer {
  String write(Object value);
  <T> T read(String json, Class<T> type);
}
