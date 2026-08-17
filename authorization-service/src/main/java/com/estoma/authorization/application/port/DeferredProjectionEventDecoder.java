package com.estoma.authorization.application.port;

import com.estoma.authorization.application.projection.ProjectionEvent;

public interface DeferredProjectionEventDecoder {

  ProjectionEvent decode(ProcessedMessageRepository.ProcessedMessage message);
}
