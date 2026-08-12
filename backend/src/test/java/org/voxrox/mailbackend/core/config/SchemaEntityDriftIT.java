package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hibernate.Session;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Guards the one invariant that {@code ddl-auto=none} silently removes: that
 * {@code V1__init.sql} and the JPA entities still describe the same schema.
 *
 * <p>
 * The app never lets Hibernate touch the schema — Flyway owns it — so a column
 * an entity maps but the migration does not create produces no startup error at
 * all. It surfaces later as a runtime SQL failure on whichever query happens to
 * touch that column, and only for the users whose workflow reaches it. Until
 * now the only thing standing in the way was whether some unrelated repository
 * test happened to select the drifted column.
 *
 * <p>
 * After the first public release this matters more, not less: drift then has to
 * be repaired by a {@code V2+} migration on databases that already hold user
 * data, and a build that maps a column its own migration never created cannot
 * be fixed through the updater. See {@link FlywayBaselineChecksumTest} for the
 * companion guard on editing the baseline itself.
 *
 * <p>
 * <b>Why not {@code ddl-auto=validate}:</b> Hibernate's validator compares
 * declared type <em>names</em>, which SQLite does not have in any meaningful
 * sense — it stores type affinity, so {@code INTEGER} and {@code BIGINT} are
 * the same column. Validation therefore fails on every {@code Long} the schema
 * declares as {@code INTEGER}, and the primary keys cannot be renamed to
 * {@code BIGINT} to appease it: only a literal {@code INTEGER PRIMARY KEY} is
 * an alias for the rowid, which {@code AUTOINCREMENT} and the FTS5
 * {@code content_rowid} both depend on. It also walks every table in the file,
 * including the FTS5 shadow tables, whose columns have no declared type at all
 * and crash its extractor. So this test compares what actually carries meaning
 * here: that every mapped table and column exists.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
class SchemaEntityDriftIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "SchemaEntityDriftIT", UUID.randomUUID().toString()).toAbsolutePath().normalize();

    private static final List<String> NAMING_STRATEGY_KEYS = List.of("hibernate.physical_naming_strategy",
            "hibernate.implicit_naming_strategy");

    @DynamicPropertySource
    static void configureSqliteDatasource(DynamicPropertyRegistry registry) {
        try {
            Files.createDirectories(DB_DIR);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create directory for SQLite test DB: " + DB_DIR, e);
        }
        Path dbFile = DB_DIR.resolve("test.db");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + dbFile.toAbsolutePath() + "?foreign_keys=ON&busy_timeout=5000");
    }

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("every table and column the entities map exists in the migrated schema")
    void mappedColumnsExistInTheMigratedSchema() {
        Map<String, Set<String>> mapped = mappedTables();
        assertThat(mapped).as("""
                No mapped tables were collected, so this guard proved nothing. \
                Check that the @DataJpaTest entity scan still reaches org.voxrox.mailbackend..entity.\
                """).isNotEmpty();

        List<String> missing = new ArrayList<>();
        // The 'it' profile pins the pool to a single connection and @DataJpaTest is
        // holding it for the test transaction, so the schema has to be read through
        // that same connection rather than borrowed from the pool.
        entityManager.unwrap(Session.class).doWork(connection -> {
            for (Map.Entry<String, Set<String>> entry : mapped.entrySet()) {
                String table = entry.getKey();
                Set<String> actual = actualColumns(connection, table);
                if (actual.isEmpty()) {
                    missing.add("table " + table + " is mapped by an entity but V1__init.sql never creates it");
                    continue;
                }
                for (String column : entry.getValue()) {
                    if (!actual.contains(column)) {
                        missing.add(table + "." + column + " is mapped but missing from the schema (schema has: "
                                + new TreeSet<>(actual) + ")");
                    }
                }
            }
        });

        assertThat(missing).as("""
                The JPA entities and db/migration/V1__init.sql have drifted apart. Every entry below \
                would fail at runtime on the first query touching it — silently, because ddl-auto=none \
                means nothing checks this at startup.

                Before the first public release: fix V1__init.sql and re-pin PINNED_V1_CHECKSUM in \
                FlywayBaselineChecksumTest. After it: add a V2__*.sql migration.\
                """).isEmpty();
    }

    /**
     * Table name (lower-cased) to the set of column names the entities map.
     *
     * <p>
     * Built from a standalone Hibernate {@code Metadata} rather than the running
     * SessionFactory, which exposes no such view. The naming strategies are copied
     * off the live EntityManagerFactory instead of being restated here — they are
     * what turns {@code accountName} into {@code account_name}, so a hardcoded
     * guess that fell out of step with Boot's default would make this guard report
     * drift that does not exist.
     */
    private Map<String, Set<String>> mappedTables() {
        Map<String, Object> runtimeProperties = entityManager.getEntityManagerFactory().getProperties();
        StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder().applySetting("hibernate.dialect",
                "org.hibernate.community.dialect.SQLiteDialect");
        for (String key : NAMING_STRATEGY_KEYS) {
            Object value = runtimeProperties.get(key);
            assertThat(value)
                    .as("%s is not exposed by the running EntityManagerFactory, so this guard would compare "
                            + "raw property names against snake_case columns and report drift that does not exist", key)
                    .isNotNull();
            builder.applySetting(key, value);
        }

        StandardServiceRegistry registry = builder.build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            entityManager.getMetamodel().getEntities().stream().map(jakarta.persistence.metamodel.Type::getJavaType)
                    .forEach(sources::addAnnotatedClass);
            Metadata metadata = sources.buildMetadata();

            Map<String, Set<String>> tables = new LinkedHashMap<>();
            for (Table table : metadata.collectTableMappings()) {
                Set<String> columns = new LinkedHashSet<>();
                for (Column column : table.getColumns()) {
                    columns.add(column.getName().toLowerCase(Locale.ROOT));
                }
                tables.put(table.getName().toLowerCase(Locale.ROOT), columns);
            }
            return tables;
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /**
     * Column names of a table as the migrated database actually has them.
     *
     * <p>
     * Read through the {@code pragma_table_info} table-valued function, not the
     * pragma statement of the same name: only the function form accepts a bind
     * parameter. The statement form would have to interpolate the table name into
     * the SQL string, which is a concatenated query however trusted the name is (it
     * comes from the entity mappings).
     */
    private Set<String> actualColumns(Connection connection, String table) {
        Set<String> columns = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM pragma_table_info(?)")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString("name").toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the schema of table " + table, e);
        }
        return columns;
    }
}
