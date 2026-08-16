package com.estoma.authorization.application.command;

import com.estoma.authorization.application.support.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared helpers for canonical terminal Result data. */
public final class CommandResultSupport {

  private CommandResultSupport() {}

  public static Map<String, Object> withImportExecution(
      AuthorizationCommand command, Map<String, ?> data) {
    LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
    if (data != null) {
      data.forEach(merged::put);
    }
    ImportExecution execution = command.importExecution();
    if (execution != null) {
      merged.put("importId", execution.importId());
      merged.put("planItemId", execution.planItemId());
      merged.put("executionGeneration", execution.executionGeneration());
    }
    return merged;
  }

  /**
   * Rejected/failed Academic Results still carry import-plan correlation data. Successful paths are
   * enriched when they are built so their business data is preserved without reparsing JSON.
   */
  public static ApplicationResult ensureImportExecutionData(
      AuthorizationCommand command, ApplicationResult result) {
    if (command.importExecution() == null || result.dataJson() != null) {
      return result;
    }
    return new ApplicationResult(
        result.status(),
        CanonicalJson.write(withImportExecution(command, Map.of())),
        result.error());
  }
}
