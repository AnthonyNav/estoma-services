package com.estoma.academic.importing;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.SAXParserFactory;
import org.apache.poi.ooxml.util.SAXHelper;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLFilter;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

/** Reads the approved three-sheet template without materializing an XSSFWorkbook. */
@Component
public final class StreamingImportWorkbookParser {
  private static final Set<String> SHEETS = Set.of("NRC", "ALUMNOS", "INSCRIPCIONES");
  private final StudentImportProperties properties;

  public StreamingImportWorkbookParser(StudentImportProperties properties) {
    this.properties = properties;
  }

  public ImportWorkbook parse(InputStream input) {
    Instant deadline = Instant.now().plus(properties.parseTimeout());
    try (OPCPackage pkg = OPCPackage.open(input)) {
      XSSFReader reader = new XSSFReader(pkg);
      StylesTable styles = reader.getStylesTable();
      ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
      XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
      Map<String, SheetBuilder> found = new LinkedHashMap<>();
      while (sheets.hasNext()) {
        try (InputStream sheet = sheets.next()) {
          String name = sheets.getSheetName();
          if (!SHEETS.contains(name) || found.put(name, parseSheet(name, sheet, styles, strings, deadline)) != null) {
            throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
          }
        }
      }
      if (!found.keySet().equals(SHEETS)) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      return new ImportWorkbook(found.get("NRC").courseSections, found.get("ALUMNOS").students, found.get("INSCRIPCIONES").enrollments);
    } catch (InvalidImportWorkbookException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
    }
  }

  private SheetBuilder parseSheet(String name, InputStream input, StylesTable styles, ReadOnlySharedStringsTable strings, Instant deadline) throws Exception {
    SheetBuilder builder = new SheetBuilder(name, deadline);
    XSSFSheetXMLHandler handler = new XSSFSheetXMLHandler(styles, null, strings, builder, new DataFormatter(), false);
    XMLReader xml = SAXHelper.newXMLReader();
    XMLFilter guard = new Guard(xml, builder);
    guard.setContentHandler(handler);
    guard.parse(new InputSource(input));
    builder.finish();
    return builder;
  }

  private final class SheetBuilder implements XSSFSheetXMLHandler.SheetContentsHandler {
    private final String name;
    private final Instant deadline;
    private final Map<Integer, Cell> cells = new HashMap<>();
    private final Map<String, String> types = new HashMap<>();
    private final List<ImportWorkbook.CourseSection> courseSections = new ArrayList<>();
    private final List<ImportWorkbook.Student> students = new ArrayList<>();
    private final List<ImportWorkbook.Enrollment> enrollments = new ArrayList<>();
    private Map<String, Integer> headers;
    private int rowNumber;

    SheetBuilder(String name, Instant deadline) {
      this.name = name;
      this.deadline = deadline;
    }

    void type(String reference, String type) { types.put(reference, type == null ? "n" : type); }

    @Override public void startRow(int rowNum) { checkDeadline(); rowNumber = rowNum + 1; cells.clear(); }
    @Override public void endRow(int rowNum) {
      if (blank()) return;
      if (headers == null) { headers = headers(); return; }
      switch (name) {
        case "NRC" -> courseSections.add(new ImportWorkbook.CourseSection(text("nrc", true), text("nombre_materia", true), rowNumber));
        case "ALUMNOS" -> students.add(new ImportWorkbook.Student(text("matricula", true), text("nombres", true),
            text("apellido_paterno", true), text("apellido_materno", false), text("correo_institucional", true), semester(), rowNumber));
        case "INSCRIPCIONES" -> enrollments.add(new ImportWorkbook.Enrollment(text("matricula", true), text("nrc", true), rowNumber));
        default -> throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
      int limit = switch (name) {
        case "NRC" -> properties.maxNrcRows();
        case "ALUMNOS" -> properties.maxStudentRows();
        default -> properties.maxEnrollmentRows();
      };
      if (size() > limit) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
    }
    @Override public void cell(String reference, String value, org.apache.poi.xssf.usermodel.XSSFComment comment) {
      checkDeadline();
      if (value != null && value.length() > properties.maxCellCharacters()) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      cells.put(column(reference), new Cell(value == null ? "" : value, types.getOrDefault(reference, "n")));
    }
    @Override public void headerFooter(String text, boolean isHeader, String tagName) {}

    void finish() { if (headers == null) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA"); }
    private int size() { return switch (name) { case "NRC" -> courseSections.size(); case "ALUMNOS" -> students.size(); default -> enrollments.size(); }; }
    private boolean blank() { return cells.values().stream().allMatch(c -> c.value.isBlank()); }
    private Map<String, Integer> headers() {
      Map<String, Integer> result = new HashMap<>();
      for (var entry : cells.entrySet()) {
        String header = entry.getValue().value.trim();
        if (header.isEmpty() || result.put(header, entry.getKey()) != null) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
      Set<String> expected = switch (name) {
        case "NRC" -> Set.of("nrc", "nombre_materia");
        case "ALUMNOS" -> Set.of("matricula", "nombres", "apellido_paterno", "apellido_materno", "correo_institucional", "semestre");
        default -> Set.of("matricula", "nrc");
      };
      if (!result.keySet().equals(expected)) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      return result;
    }
    private String text(String header, boolean required) {
      Cell cell = cells.get(headers.get(header));
      if (cell == null || ("nrc".equals(header) || "matricula".equals(header)) && "n".equals(cell.type)) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      String value = cell.value.trim();
      if (required && value.isBlank()) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      return value;
    }
    private int semester() {
      Cell cell = cells.get(headers.get("semestre"));
      try {
        int value = Integer.parseInt(cell.value.trim());
        if (value < 1 || value > 14) throw new NumberFormatException();
        return value;
      } catch (RuntimeException ex) { throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA"); }
    }
    private void checkDeadline() { if (Instant.now().isAfter(deadline)) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA"); }
  }

  private static final class Guard extends XMLFilterImpl {
    private final SheetBuilder builder;
    Guard(XMLReader parent, SheetBuilder builder) { super(parent); this.builder = builder; }
    @Override public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
      if ("f".equals(localName) || "f".equals(qName)) throw new SAXException("formula cells are forbidden");
      if ("c".equals(localName) || "c".equals(qName)) builder.type(atts.getValue("r"), atts.getValue("t"));
      super.startElement(uri, localName, qName, atts);
    }
  }

  private record Cell(String value, String type) {}
  private static int column(String ref) {
    int result = 0;
    for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) result = result * 26 + Character.toUpperCase(ref.charAt(i)) - 'A' + 1;
    return result - 1;
  }
}
