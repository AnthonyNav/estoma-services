package com.estoma.academic.application;

class AcademicRejectedException extends RuntimeException {
  private final String code;
  AcademicRejectedException(String code) { super(code); this.code = code; }
  String code() { return code; }
}
