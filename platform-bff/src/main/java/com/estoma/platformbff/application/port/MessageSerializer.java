package com.estoma.platformbff.application.port;

public interface MessageSerializer {
  String write(Object value);
}
