package com.estoma.academic.importing;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Opens only the opaque object named by a frozen import reference. */
public interface StudentImportFileStore {
  InputStream open(UUID fileReference) throws IOException;
}
