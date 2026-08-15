package com.estoma.academic.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class StreamingImportWorkbookParserTest {

  @Test
  void acceptsCanonicalWorkbook() throws Exception {
    ImportWorkbook parsed = parser(200L * 1024 * 1024).parse(new ByteArrayInputStream(workbook(false, false, false)));

    assertThat(parsed.courseSections()).hasSize(1);
    assertThat(parsed.students()).hasSize(1);
    assertThat(parsed.enrollments()).hasSize(1);
  }

  @Test
  void rejectsHiddenRequiredWorksheet() throws Exception {
    assertInvalid(workbook(true, false, false), 200L * 1024 * 1024);
  }

  @Test
  void rejectsNumericTextIdentifiers() throws Exception {
    assertInvalid(workbook(false, true, false), 200L * 1024 * 1024);
  }

  @Test
  void rejectsTextSemester() throws Exception {
    assertInvalid(workbook(false, false, true), 200L * 1024 * 1024);
  }

  @Test
  void rejectsExcessivelySparseDeclaredRows() throws Exception {
    byte[] bytes;
    try (XSSFWorkbook workbook = canonicalWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var students = workbook.getSheet("ALUMNOS");
      var normal = students.getRow(1);
      students.removeRow(normal);
      var sparse = students.createRow(25001);
      sparse.createCell(0).setCellValue("ST-1");
      sparse.createCell(1).setCellValue("Ada");
      sparse.createCell(2).setCellValue("Lovelace");
      sparse.createCell(3).setCellValue("");
      sparse.createCell(4).setCellValue("ada@estoma.mx");
      sparse.createCell(5).setCellValue(6);
      workbook.write(output);
      bytes = output.toByteArray();
    }
    assertInvalid(bytes, 200L * 1024 * 1024);
  }

  @Test
  void rejectsMacroAndExternalLinkParts() throws Exception {
    byte[] base = workbook(false, false, false);
    assertInvalid(withZipEntry(base, "xl/vbaProject.bin", new byte[] {1, 2, 3}), 200L * 1024 * 1024);
    assertInvalid(withZipEntry(base, "xl/externalLinks/externalLink1.xml", "<externalLink/>".getBytes()),
        200L * 1024 * 1024);
  }

  @Test
  void rejectsExpandedArchiveOverBudget() throws Exception {
    assertThatThrownBy(() -> parser(1024).parse(new ByteArrayInputStream(workbook(false, false, false))))
        .isInstanceOf(InvalidImportWorkbookException.class)
        .extracting(ex -> ((InvalidImportWorkbookException) ex).code())
        .isEqualTo("INVALID_IMPORT_FILE_SIZE");
  }

  private void assertInvalid(byte[] bytes, long expandedBudget) {
    assertThatThrownBy(() -> parser(expandedBudget).parse(new ByteArrayInputStream(bytes)))
        .isInstanceOf(InvalidImportWorkbookException.class);
  }

  private static StreamingImportWorkbookParser parser(long expandedBudget) {
    return new StreamingImportWorkbookParser(new StudentImportProperties(
        new StudentImportProperties.Storage(false, null, null, null, null),
        20 * 1024 * 1024,
        expandedBudget,
        10_000,
        25_000,
        200_000,
        4_096,
        Duration.ofSeconds(60)));
  }

  private static byte[] workbook(boolean hidden, boolean numericStudentNumber, boolean textSemester) throws Exception {
    try (XSSFWorkbook workbook = canonicalWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      if (hidden) workbook.setSheetHidden(workbook.getSheetIndex("ALUMNOS"), true);
      var student = workbook.getSheet("ALUMNOS").getRow(1);
      if (numericStudentNumber) student.getCell(0).setCellValue(12345);
      if (textSemester) student.getCell(5).setCellValue("6");
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static XSSFWorkbook canonicalWorkbook() {
    XSSFWorkbook workbook = new XSSFWorkbook();
    var nrc = workbook.createSheet("NRC");
    row(nrc, 0, "nrc", "nombre_materia");
    row(nrc, 1, "NRC-1", "Clinical");

    var students = workbook.createSheet("ALUMNOS");
    row(students, 0, "matricula", "nombres", "apellido_paterno", "apellido_materno",
        "correo_institucional", "semestre");
    var student = students.createRow(1);
    student.createCell(0).setCellValue("ST-1");
    student.createCell(1).setCellValue("Ada");
    student.createCell(2).setCellValue("Lovelace");
    student.createCell(3).setCellValue("");
    student.createCell(4).setCellValue("ada@estoma.mx");
    student.createCell(5).setCellValue(6);

    var enrollments = workbook.createSheet("INSCRIPCIONES");
    row(enrollments, 0, "matricula", "nrc");
    row(enrollments, 1, "ST-1", "NRC-1");
    return workbook;
  }

  private static void row(org.apache.poi.ss.usermodel.Sheet sheet, int rowNumber, String... values) {
    var row = sheet.createRow(rowNumber);
    for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
  }

  private static byte[] withZipEntry(byte[] original, String name, byte[] data) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(original));
         ZipOutputStream zip = new ZipOutputStream(output)) {
      for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
        zip.putNextEntry(new ZipEntry(entry.getName()));
        input.transferTo(zip);
        zip.closeEntry();
        input.closeEntry();
      }
      zip.putNextEntry(new ZipEntry(name));
      zip.write(data);
      zip.closeEntry();
    }
    return output.toByteArray();
  }
}
