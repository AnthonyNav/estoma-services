package com.estoma.academic.importing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
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
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLFilterImpl;

/** Reads the approved three-sheet template without materializing an XSSFWorkbook. */
@Component
public final class StreamingImportWorkbookParser {
  private static final Set<String> SHEETS = Set.of("NRC", "ALUMNOS", "INSCRIPCIONES");
  // Technical anti-sparsity ceiling. V1 needs at most six columns; 256 leaves ample template freedom.
  private static final int MAX_OBSERVED_COLUMN_INDEX = 255;
  private final StudentImportProperties properties;

  public StreamingImportWorkbookParser(StudentImportProperties properties) {
    this.properties = properties;
  }

  public ImportWorkbook parse(InputStream input) {
    Instant deadline = Instant.now().plus(properties.parseTimeout());
    try {
      byte[] compressed = boundedCompressedBytes(input, deadline);
      validateZipBudget(compressed, deadline);
      try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(compressed))) {
        validatePackageParts(pkg);
        XSSFReader reader = new XSSFReader(pkg);
        WorkbookGuard workbook = workbookGuard(reader, deadline);
        StylesTable styles = reader.getStylesTable();
        ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
        XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
        Map<String, SheetBuilder> found = new LinkedHashMap<>();
        while (sheets.hasNext()) {
          checkDeadline(deadline);
          try (InputStream sheet = sheets.next()) {
            String name = sheets.getSheetName();
            if (!workbook.isVisibleRequiredSheet(name)
                || found.put(name, parseSheet(name, sheet, styles, strings, deadline)) != null) {
              throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
            }
          }
        }
        if (!found.keySet().equals(SHEETS)) {
          throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
        }
        return new ImportWorkbook(
            found.get("NRC").courseSections,
            found.get("ALUMNOS").students,
            found.get("INSCRIPCIONES").enrollments);
      }
    } catch (InvalidImportWorkbookException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
    }
  }

  private byte[] boundedCompressedBytes(InputStream input, Instant deadline) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(properties.maxObjectBytes(), 1024 * 1024));
    byte[] buffer = new byte[8192];
    int total = 0;
    for (int read; (read = input.read(buffer)) >= 0; ) {
      checkDeadline(deadline);
      if (read == 0) continue;
      total += read;
      if (total > properties.maxObjectBytes()) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_FILE_SIZE");
      }
      output.write(buffer, 0, read);
    }
    if (total == 0) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
    return output.toByteArray();
  }

  private void validateZipBudget(byte[] compressed, Instant deadline) throws Exception {
    long expanded = 0;
    int entries = 0;
    byte[] buffer = new byte[8192];
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(compressed))) {
      while (zip.getNextEntry() != null) {
        checkDeadline(deadline);
        entries++;
        for (int read; (read = zip.read(buffer)) >= 0; ) {
          checkDeadline(deadline);
          if (read == 0) continue;
          expanded += read;
          if (expanded > properties.maxExpandedZipBytes()) {
            throw new InvalidImportWorkbookException("INVALID_IMPORT_FILE_SIZE");
          }
        }
        zip.closeEntry();
      }
    }
    if (entries == 0) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
  }

  private static void validatePackageParts(OPCPackage pkg) throws Exception {
    int workbooks = 0;
    for (var part : pkg.getParts()) {
      String name = part.getPartName().getName().toLowerCase(Locale.ROOT);
      String contentType = part.getContentType() == null ? "" : part.getContentType().toLowerCase(Locale.ROOT);
      if ("/xl/workbook.xml".equals(name)) workbooks++;
      if (name.endsWith("/vbaproject.bin") || name.startsWith("/xl/macrosheets/")
          || contentType.contains("macroenabled") || contentType.contains("vba")) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
      if (name.startsWith("/xl/externallinks/") || contentType.contains("externallink")) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
    }
    if (workbooks != 1) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
  }

  private WorkbookGuard workbookGuard(XSSFReader reader, Instant deadline) throws Exception {
    WorkbookGuard guard = new WorkbookGuard(deadline);
    XMLReader xml = SAXHelper.newXMLReader();
    xml.setContentHandler(guard);
    try (InputStream workbook = reader.getWorkbookData()) {
      xml.parse(new InputSource(workbook));
    }
    guard.finish();
    return guard;
  }

  private SheetBuilder parseSheet(
      String name,
      InputStream input,
      StylesTable styles,
      ReadOnlySharedStringsTable strings,
      Instant deadline) throws Exception {
    SheetBuilder builder = new SheetBuilder(name, deadline);
    XSSFSheetXMLHandler handler =
        new XSSFSheetXMLHandler(styles, null, strings, builder, new DataFormatter(), false);
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

    void type(String reference, String type) {
      if (reference == null || reference.isBlank()) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
      validateCellReference(reference);
      types.put(reference, type == null ? "n" : type);
    }

    void validateDimension(String reference) {
      if (reference == null || reference.isBlank()) return;
      String[] bounds = reference.split(":", 2);
      for (String bound : bounds) validateCellReference(bound);
    }

    private void validateCellReference(String reference) {
      int column = column(reference);
      int row = row(reference);
      if (column < 0 || column > MAX_OBSERVED_COLUMN_INDEX || row < 1 || row > maxRows(name) + 1) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
    }

    @Override
    public void startRow(int rowNum) {
      checkDeadline();
      if (rowNum < 0 || rowNum > maxRows(name)) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
      rowNumber = rowNum + 1;
      cells.clear();
      types.clear();
    }

    @Override
    public void endRow(int rowNum) {
      if (blank()) return;
      if (headers == null) {
        headers = headers();
        return;
      }
      switch (name) {
        case "NRC" -> courseSections.add(new ImportWorkbook.CourseSection(
            text("nrc", true), text("nombre_materia", true), rowNumber));
        case "ALUMNOS" -> students.add(new ImportWorkbook.Student(
            text("matricula", true), text("nombres", true), text("apellido_paterno", true),
            text("apellido_materno", false), text("correo_institucional", true), semester(), rowNumber));
        case "INSCRIPCIONES" -> enrollments.add(new ImportWorkbook.Enrollment(
            text("matricula", true), text("nrc", true), rowNumber));
        default -> throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
      if (size() > maxRows(name)) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
    }

    @Override
    public void cell(String reference, String value, org.apache.poi.xssf.usermodel.XSSFComment comment) {
      checkDeadline();
      validateCellReference(reference);
      if (value != null && value.length() > properties.maxCellCharacters()) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
      int column = column(reference);
      String normalized = value == null ? "" : value;
      if (headers != null && !normalized.isBlank() && !headers.containsValue(column)) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
      cells.put(column, new Cell(normalized, types.getOrDefault(reference, "n")));
    }

    @Override
    public void headerFooter(String text, boolean isHeader, String tagName) {}

    void finish() {
      if (headers == null) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
    }

    private int size() {
      return switch (name) {
        case "NRC" -> courseSections.size();
        case "ALUMNOS" -> students.size();
        default -> enrollments.size();
      };
    }

    private boolean blank() {
      return cells.values().stream().allMatch(c -> c.value.isBlank());
    }

    private Map<String, Integer> headers() {
      Map<String, Integer> result = new HashMap<>();
      for (var entry : cells.entrySet()) {
        Cell cell = entry.getValue();
        if (!isTextCell(cell)) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
        String header = cell.value.trim();
        if (header.isEmpty() || result.put(header, entry.getKey()) != null) {
          throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
        }
      }
      Set<String> expected = switch (name) {
        case "NRC" -> Set.of("nrc", "nombre_materia");
        case "ALUMNOS" -> Set.of(
            "matricula", "nombres", "apellido_paterno", "apellido_materno", "correo_institucional", "semestre");
        default -> Set.of("matricula", "nrc");
      };
      if (!result.keySet().equals(expected)) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      return result;
    }

    private String text(String header, boolean required) {
      Cell cell = cells.get(headers.get(header));
      if (cell == null) {
        if (required) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
        return "";
      }
      if (!isTextCell(cell)) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      String value = cell.value.trim();
      if (required && value.isBlank()) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      return value;
    }

    private int semester() {
      Cell cell = cells.get(headers.get("semestre"));
      if (cell == null || !"n".equals(cell.type)) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
      try {
        BigDecimal numeric = new BigDecimal(cell.value.trim()).stripTrailingZeros();
        if (numeric.scale() > 0) throw new ArithmeticException("semester must be an integer");
        int value = numeric.intValueExact();
        if (value < 1 || value > 14) throw new ArithmeticException("semester out of range");
        return value;
      } catch (RuntimeException ex) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
    }

    private void checkDeadline() {
      StreamingImportWorkbookParser.checkDeadline(deadline);
    }
  }

  private static boolean isTextCell(Cell cell) {
    return "s".equals(cell.type) || "inlineStr".equals(cell.type) || "str".equals(cell.type);
  }

  private final class WorkbookGuard extends DefaultHandler {
    private final Instant deadline;
    private final Map<String, String> states = new LinkedHashMap<>();

    WorkbookGuard(Instant deadline) {
      this.deadline = deadline;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) {
      checkDeadline(deadline);
      if (!element(localName, qName, "sheet")) return;
      String name = atts.getValue("name");
      String state = atts.getValue("state");
      if (name == null || states.put(name, state == null ? "visible" : state) != null) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
    }

    void finish() {
      if (!states.keySet().equals(SHEETS)
          || states.values().stream().anyMatch(state -> !"visible".equals(state))) {
        throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
      }
    }

    boolean isVisibleRequiredSheet(String name) {
      return SHEETS.contains(name) && "visible".equals(states.get(name));
    }
  }

  private static final class Guard extends XMLFilterImpl {
    private final SheetBuilder builder;

    Guard(XMLReader parent, SheetBuilder builder) {
      super(parent);
      this.builder = builder;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
      if (element(localName, qName, "f")) throw new SAXException("formula cells are forbidden");
      if (element(localName, qName, "dimension")) builder.validateDimension(atts.getValue("ref"));
      if (element(localName, qName, "c")) builder.type(atts.getValue("r"), atts.getValue("t"));
      super.startElement(uri, localName, qName, atts);
    }
  }

  private static boolean element(String localName, String qName, String expected) {
    return expected.equals(localName) || expected.equals(qName);
  }

  private int maxRows(String sheet) {
    return switch (sheet) {
      case "NRC" -> properties.maxNrcRows();
      case "ALUMNOS" -> properties.maxStudentRows();
      case "INSCRIPCIONES" -> properties.maxEnrollmentRows();
      default -> throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
    };
  }

  private static void checkDeadline(Instant deadline) {
    if (Instant.now().isAfter(deadline)) throw new InvalidImportWorkbookException("INVALID_IMPORT_SCHEMA");
  }

  private record Cell(String value, String type) {}

  private static int column(String reference) {
    int result = 0;
    int letters = 0;
    while (letters < reference.length() && Character.isLetter(reference.charAt(letters))) {
      result = result * 26 + Character.toUpperCase(reference.charAt(letters)) - 'A' + 1;
      letters++;
    }
    return letters == 0 ? -1 : result - 1;
  }

  private static int row(String reference) {
    int index = 0;
    while (index < reference.length() && Character.isLetter(reference.charAt(index))) index++;
    if (index == 0 || index == reference.length()) return -1;
    try {
      return Integer.parseInt(reference.substring(index));
    } catch (NumberFormatException ex) {
      return -1;
    }
  }
}
