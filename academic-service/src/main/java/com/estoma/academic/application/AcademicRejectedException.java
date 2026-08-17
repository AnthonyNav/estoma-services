package com.estoma.academic.application;

/** Internal functional/contract rejection marker. Construction remains owned by Academic application code. */
public final class AcademicRejectedException extends RuntimeException {
  private final String code;

  AcademicRejectedException(String code) {
    super(code);
    this.code = code;
  }

  String code() {
    return code;
  }
}
