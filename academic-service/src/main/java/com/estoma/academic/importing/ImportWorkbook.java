package com.estoma.academic.importing;

import java.util.List;

/** Normalized, bounded representation produced by the streaming XLSX reader. */
public record ImportWorkbook(List<CourseSection> courseSections, List<Student> students, List<Enrollment> enrollments) {
  public record CourseSection(String nrc, String name, int rowNumber) {}
  public record Student(String studentNumber, String firstName, String paternalSurname, String maternalSurname,
                        String institutionalEmail, int semester, int rowNumber) {}
  public record Enrollment(String studentNumber, String nrc, int rowNumber) {}
}
