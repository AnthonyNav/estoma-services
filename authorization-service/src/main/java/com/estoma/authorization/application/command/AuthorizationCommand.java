package com.estoma.authorization.application.command;

import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemAccessReasonCode;
import com.estoma.authorization.domain.model.SystemCode;
import java.util.UUID;

public sealed interface AuthorizationCommand
    permits AssignAccountRoleCommand,
        ChangeAccountRoleCommand,
        GrantSystemAccessCommand,
        SuspendSystemAccessCommand,
        RevokeSystemAccessCommand,
        RestoreSystemAccessCommand {

  String operationType();

  UUID targetAccountId();

  ImportExecution importExecution();

  record Marker() {}
}
