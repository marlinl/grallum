import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

/** Runs explicitly supplied fixture SQL against a separately configured PostgreSQL test database. */
public final class FixtureSql {
  /** Prevents construction of the fixture-only command. */
  private FixtureSql() {}

  /** Reads SQL from stdin, executes it on the supplied JDBC URL and prints only result columns. */
  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !args[0].startsWith("jdbc:postgresql://")) {
      throw new IllegalArgumentException("Supply the isolated fixture JDBC URL");
    }
    var environment = System.getenv();
    try (var connection = DriverManager.getConnection(
        args[0], environment.getOrDefault("DATABASE_USER", "grallum"),
        environment.getOrDefault("DATABASE_PASSWORD", "grallum-local"));
        var statement = connection.createStatement()) {
      boolean rows = statement.execute(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
      if (rows) {
        try (var result = statement.getResultSet()) {
          while (result.next()) {
            for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
              if (column > 1) System.out.print("\t");
              System.out.print(result.getString(column));
            }
            System.out.println();
          }
        }
      }
    }
  }
}
