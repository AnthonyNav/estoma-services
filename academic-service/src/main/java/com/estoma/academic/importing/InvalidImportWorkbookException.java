package com.estoma.academic.importing;

/** A controlled functional rejection, safe to expose only by its stable code. */
public final class InvalidImportWorkbookException extends RuntimeException {
  private final String code;

  public InvalidImportWorkbookException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
