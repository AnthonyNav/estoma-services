package com.estoma.users.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Flyway's migrations under src/main/resources/db/migration are the
 * authoritative schema (docs/system-contract.md §1). docs/data-model.sql is
 * a human-friendly reference copy that must not silently drift from them.
 *
 * <p>This does not require exact byte equality — data-model.sql carries a
 * non-authoritative disclaimer header and reflows some comments — but every
 * named schema object (table, type, index, constraint, trigger, function)
 * declared by V1..V5 must also appear in the reference copy, and vice
 * versa. V6 is excluded: it only grants runtime privileges and defines no
 * shape.
 */
class DataModelReferenceTest {

  private static final List<String> MIGRATIONS =
      List.of(
          "V1__enums.sql",
          "V2__persons.sql",
          "V3__person_status_history.sql",
          "V4__processed_messages.sql",
          "V5__outbox_messages.sql");

  private static final Pattern[] OBJECT_PATTERNS = {
    Pattern.compile("CREATE TABLE (\\w+)"),
    Pattern.compile("CREATE TYPE (\\w+)"),
    Pattern.compile("CREATE (?:UNIQUE )?INDEX (\\w+)"),
    Pattern.compile("CONSTRAINT (\\w+)"),
    Pattern.compile("CREATE TRIGGER (\\w+)"),
    Pattern.compile("CREATE FUNCTION (\\w+)"),
  };

  @Test
  void dataModelSqlDeclaresExactlyTheObjectsTheMigrationsDeclare() {
    Set<String> fromMigrations = new LinkedHashSet<>();
    for (String migration : MIGRATIONS) {
      fromMigrations.addAll(extractObjectNames(readMigration(migration)));
    }

    Set<String> fromReferenceDoc = extractObjectNames(readReferenceDoc());

    assertThat(fromReferenceDoc)
        .as("docs/data-model.sql must declare every object the Flyway migrations declare")
        .containsExactlyInAnyOrderElementsOf(fromMigrations);
  }

  /**
   * The object-name check above does not look inside {@code CREATE TABLE}
   * bodies, so it would not catch a column added to the migration but
   * forgotten in the reference doc (or vice versa). Deliberately not a
   * general SQL parser — just a targeted, named check for the Round 1
   * Outbox fencing columns and objects (docs/system-contract.md §8),
   * since those are exactly the ones this round touches.
   */
  @Test
  void dataModelSqlAndTheOutboxMigrationBothDeclareTheRound1FencingColumns() {
    String migration = readMigration("V5__outbox_messages.sql");
    String referenceDoc = readReferenceDoc();

    List<String> round1Objects =
        List.of("claim_token", "lease_until", "last_error_code", "last_error_at", "ix_outbox_processing_lease", "ck_outbox_claim_state");

    for (String object : round1Objects) {
      assertThat(migration).as("V5__outbox_messages.sql should declare " + object).contains(object);
      assertThat(referenceDoc).as("docs/data-model.sql should declare " + object).contains(object);
    }
  }

  private static Set<String> extractObjectNames(String sql) {
    Set<String> names = new LinkedHashSet<>();
    for (Pattern pattern : OBJECT_PATTERNS) {
      Matcher matcher = pattern.matcher(sql);
      while (matcher.find()) {
        names.add(matcher.group(1));
      }
    }
    return names;
  }

  private static String readMigration(String fileName) {
    Path path =
        Path.of("src", "main", "resources", "db", "migration", fileName).toAbsolutePath();
    return read(path);
  }

  private static String readReferenceDoc() {
    Path path = Path.of("docs", "data-model.sql").toAbsolutePath();
    return read(path);
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + path, e);
    }
  }
}
