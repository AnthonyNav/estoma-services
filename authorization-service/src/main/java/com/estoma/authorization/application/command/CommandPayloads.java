package com.estoma.authorization.application.command;

import com.estoma.authorization.application.support.CanonicalJson;
import com.estoma.authorization.domain.model.ActorRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CommandPayloads {

  private CommandPayloads() {}

  static String write(AuthorizationCommand command) {
    return CanonicalJson.write(values(command));
  }

  static String fingerprint(CommandMetadata metadata, AuthorizationCommand command) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("operationType", command.operationType());
    values.put("producer", metadata.producer());
    values.put("actor", actor(metadata.actor()));
    values.put("payload", values(command));
    return CanonicalJson.sha256(values);
  }

  static Map<String, Object> values(AuthorizationCommand command) {
    Map<String, Object> values = new LinkedHashMap<>();
    if (command instanceof AssignAccountRoleCommand assign) {
      values.put("accountId", assign.accountId());
      values.put("roleCode", assign.roleCode());
      values.put("importExecution", importValues(assign.importExecution()));
    } else if (command instanceof ChangeAccountRoleCommand change) {
      values.put("accountId", change.accountId());
      values.put("newRoleCode", change.newRoleCode());
      values.put("expectedVersion", change.expectedVersion());
    } else if (command instanceof GrantSystemAccessCommand grant) {
      values.put("systemAccessId", grant.systemAccessId());
      values.put("accountId", grant.accountId());
      values.put("systemCode", grant.systemCode());
      values.put("importExecution", importValues(grant.importExecution()));
    } else if (command instanceof SuspendSystemAccessCommand suspend) {
      values.put("systemAccessId", suspend.systemAccessId());
      values.put("expectedVersion", suspend.expectedVersion());
      values.put("reasonCode", suspend.reasonCode());
      values.put("reasonDetail", suspend.reasonDetail());
    } else if (command instanceof RevokeSystemAccessCommand revoke) {
      values.put("systemAccessId", revoke.systemAccessId());
      values.put("expectedVersion", revoke.expectedVersion());
      values.put("reasonCode", revoke.reasonCode());
      values.put("reasonDetail", revoke.reasonDetail());
    } else if (command instanceof RestoreSystemAccessCommand restore) {
      values.put("systemAccessId", restore.systemAccessId());
      values.put("expectedVersion", restore.expectedVersion());
    }
    return values;
  }

  static Map<String, Object> importValues(ImportExecution execution) {
    if (execution == null) {
      return null;
    }
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("importId", execution.importId());
    values.put("planItemId", execution.planItemId());
    values.put("executionGeneration", execution.executionGeneration());
    values.put("authorizedByAccountId", execution.authorizedByAccountId());
    values.put("scope", execution.scope());
    List<Map<String, Object>> dependencies = new ArrayList<>();
    for (CausalDependency dependency : execution.causalDependencies()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("priorCommandType", dependency.priorCommandType());
      item.put("priorResultMessageId", dependency.priorResultMessageId());
      item.put("priorResultStatus", dependency.priorResultStatus());
      item.put("expectedEventType", dependency.expectedEventType());
      item.put("expectedAggregateId", dependency.expectedAggregateId());
      item.put("minimumSourceVersion", dependency.minimumSourceVersion());
      dependencies.add(item);
    }
    values.put("causalDependencies", dependencies);
    List<Map<String, Object>> evidence = new ArrayList<>();
    for (AuthoritativeEvidence item : execution.authoritativeEvidence()) {
      Map<String, Object> evidenceItem = new LinkedHashMap<>();
      evidenceItem.put("sourceService", item.sourceService());
      evidenceItem.put("sourceOperation", item.sourceOperation());
      evidenceItem.put("sourceResultStatus", item.sourceResultStatus());
      evidenceItem.put("sourceResultMessageId", item.sourceResultMessageId());
      evidenceItem.put("entityType", item.entityType());
      evidenceItem.put("entityId", item.entityId());
      evidenceItem.put("evidenceVersion", item.evidenceVersion());
      evidence.add(evidenceItem);
    }
    values.put("authoritativeEvidence", evidence);
    return values;
  }

  static Map<String, Object> actor(ActorRef actor) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("type", actor.type());
    values.put("accountId", actor.accountId());
    values.put("service", actor.service());
    return values;
  }
}
