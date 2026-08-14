package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.domain.model.ActorRef;
import java.util.UUID;

final class ActorJdbcCodec {

  private ActorJdbcCodec() {}

  static ActorRef read(java.sql.ResultSet resultSet, String typeColumn, String accountColumn, String serviceColumn)
      throws java.sql.SQLException {
    String type = resultSet.getString(typeColumn);
    if (type == null) {
      return null;
    }
    return "ACCOUNT".equals(type)
        ? ActorRef.account(resultSet.getObject(accountColumn, UUID.class))
        : ActorRef.system(resultSet.getString(serviceColumn));
  }
}
