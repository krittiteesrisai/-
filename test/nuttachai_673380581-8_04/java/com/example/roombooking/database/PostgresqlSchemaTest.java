package com.example.roombooking.database;

import com.example.roombooking.domain.entity.Booking;
import com.example.roombooking.domain.entity.BookingEquipment;
import com.example.roombooking.domain.entity.BookingStatusHistory;
import com.example.roombooking.domain.entity.Equipment;
import com.example.roombooking.domain.entity.ItemImage;
import com.example.roombooking.domain.entity.MeetingRoom;
import com.example.roombooking.domain.entity.User;
import com.example.roombooking.domain.entity.UserProfile;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the deployment SQL first; Hibernate only validates and never generates the schema.
 * H2 compatibility mode checks the portable contract, not PostgreSQL engine behavior. */
class PostgresqlSchemaTest {
    private static final Set<String> TABLES = Set.of("users", "user_profile", "meeting_rooms", "equipment",
            "bookings", "booking_equipment", "booking_status_history",
            // อัลบั้มรูปห้อง/อุปกรณ์ (เพิ่มภายหลัง ไม่มี foreign key จึงไม่อยู่ในชุด FK เจ็ดเส้นด้านล่าง)
            "item_images");
    private DataSource database;
    private Connection schemaConnection;

    @BeforeEach
    void createSchemaFromProductionScript() throws SQLException {
        database = new DriverManagerDataSource("jdbc:h2:mem:postgresql-schema-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        // H2's IN-check comparator retains its DDL session. Keep that session
        // open while exercising constraints through separate JDBC connections.
        schemaConnection = database.getConnection();
        schema();
    }

    @AfterEach
    void closeFixtureDatabase() throws SQLException {
        try {
            if (schemaConnection != null) schemaConnection.close();
        } finally {
            if (database != null) execute("SHUTDOWN");
        }
    }

    @Test
    void bootstrapCreatesExactlySevenEmptyTablesWithoutDemoData() throws SQLException {
        Set<String> actual = new HashSet<>();
        try (var connection = database.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT table_name FROM information_schema.tables "
                     + "WHERE table_schema='public' AND table_type='BASE TABLE'")) {
            while (rows.next()) actual.add(rows.getString(1));
        }
        assertEquals(TABLES, actual);
        for (String table : TABLES) assertEquals(0, number("SELECT COUNT(*) FROM " + table), table);
    }

    @Test
    void rerunningBootstrapPreservesEveryExistingRowAndGeneratedId() throws SQLException {
        fixtures();
        var before = snapshot();

        schema();
        schema();

        assertEquals(before, snapshot());
        for (String table : TABLES) assertEquals(1, number("SELECT COUNT(*) FROM " + table), table);
    }

    @Test
    void allSevenForeignKeyEdgesRejectMissingParents() throws SQLException {
        Fixture fixture = fixtures();
        List<String> invalidLinks = List.of(
                "INSERT INTO user_profile(user_id) VALUES(-1)",
                bookingInsert(-1, fixture.roomId()),
                bookingInsert(fixture.userId(), -1),
                "INSERT INTO booking_equipment(booking_id,equipment_id,quantity) VALUES(-1,"
                        + fixture.equipmentId() + ",1)",
                "INSERT INTO booking_equipment(booking_id,equipment_id,quantity) VALUES("
                        + fixture.bookingId() + ",-1,1)",
                "INSERT INTO booking_status_history(booking_id,new_status) VALUES(-1,'PENDING')",
                "INSERT INTO booking_status_history(booking_id,new_status,changed_by) VALUES("
                        + fixture.bookingId() + ",'APPROVED',-1)");

        for (String sql : invalidLinks) assertIntegrityViolation(sql);
    }

    @Test
    void requiredColumnsRejectNullValues() throws SQLException {
        fixtures();
        Map<String, List<String>> requiredColumns = Map.of(
                "user_profile", List.of("user_id"),
                "meeting_rooms", List.of("name", "capacity", "room_type", "status"),
                "equipment", List.of("name", "total_quantity"),
                "bookings", List.of("user_id", "room_id", "start_time", "end_time", "status", "created_at"),
                "booking_equipment", List.of("booking_id", "equipment_id", "quantity"),
                "booking_status_history", List.of("booking_id", "new_status"));

        for (var table : requiredColumns.entrySet()) {
            for (String column : table.getValue()) {
                assertIntegrityViolation("UPDATE " + table.getKey() + " SET " + column + "=NULL");
            }
        }
    }

    @Test
    void enumChecksRejectUnknownValuesInEveryMappedEnumColumn() throws SQLException {
        fixtures();
        Map<String, List<String>> enums = Map.of(
                "users", List.of("role"),
                "meeting_rooms", List.of("room_type", "status"),
                "bookings", List.of("status"),
                "booking_status_history", List.of("old_status", "new_status"));

        for (var table : enums.entrySet()) {
            for (String column : table.getValue()) {
                assertIntegrityViolation("UPDATE " + table.getKey() + " SET " + column + "='UNKNOWN'");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void equipmentQuantityCheckRejectsNonPositiveValues(int quantity) throws SQLException {
        fixtures();
        assertIntegrityViolation("UPDATE booking_equipment SET quantity=" + quantity);
        assertEquals(2, number("SELECT quantity FROM booking_equipment"));
    }

    @Test
    void oneToOneProfileRejectsASecondProfileForTheSameUser() throws SQLException {
        Fixture fixture = fixtures();
        assertIntegrityViolation("INSERT INTO user_profile(user_id) VALUES(" + fixture.userId() + ")");
        assertEquals(1, number("SELECT COUNT(*) FROM user_profile"));
    }

    @Test
    void auditHistoryAllowsAnInitialStatusWithoutAnActorOrTimestamp() throws SQLException {
        Fixture fixture = fixtures();
        long historyId = insert("INSERT INTO booking_status_history(booking_id,new_status) VALUES(?,?)",
                fixture.bookingId(), "PENDING");

        assertEquals(1, number("SELECT COUNT(*) FROM booking_status_history WHERE id=" + historyId
                + " AND old_status IS NULL AND changed_by IS NULL AND changed_at IS NULL"));
    }

    @Test
    void referencedParentsCannotBeDeletedByImplicitDatabaseCascade() throws SQLException {
        Fixture fixture = fixtures();
        assertIntegrityViolation("DELETE FROM bookings WHERE id=" + fixture.bookingId());
        assertIntegrityViolation("DELETE FROM equipment WHERE id=" + fixture.equipmentId());
        assertIntegrityViolation("DELETE FROM users WHERE id=" + fixture.userId());
        for (String table : TABLES) assertEquals(1, number("SELECT COUNT(*) FROM " + table), table);
    }

    @Test
    void equipmentReplacementCanBeInsertedBeforeTheOldOrphanIsDeleted() throws SQLException {
        Fixture fixture = fixtures();
        long replacementId = insert("INSERT INTO booking_equipment(booking_id,equipment_id,quantity) VALUES(?,?,?)",
                fixture.bookingId(), fixture.equipmentId(), 3);
        assertEquals(2, number("SELECT COUNT(*) FROM booking_equipment"));

        execute("DELETE FROM booking_equipment WHERE id=" + fixture.linkId());

        assertEquals(1, number("SELECT COUNT(*) FROM booking_equipment"));
        assertEquals(3, number("SELECT quantity FROM booking_equipment WHERE id=" + replacementId));
    }

    @Test
    void hibernatePostgresqlMappingsValidateAgainstTheScriptCreatedSchema() throws SQLException {
        validateJpaSchema();
        for (String table : TABLES) assertEquals(0, number("SELECT COUNT(*) FROM " + table), table);
    }

    @Test
    void validationFailsForAMissingMappedColumnWithoutRecreatingIt() throws SQLException {
        execute("ALTER TABLE bookings DROP COLUMN purpose");

        SchemaManagementException failure = assertThrows(SchemaManagementException.class, this::validateJpaSchema);

        assertTrue(failure.getMessage().contains("purpose"), failure.getMessage());
        assertEquals(0, number("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='bookings' AND column_name='purpose'"));
    }

    private void validateJpaSchema() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.datasource", database)
                .applySetting("hibernate.dialect", PostgreSQLDialect.class.getName())
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.default_schema", "public")
                .applySetting("hibernate.physical_naming_strategy", PhysicalNamingStrategySnakeCaseImpl.class.getName())
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .build();
        try {
            var metadata = new MetadataSources(registry)
                    .addAnnotatedClass(User.class).addAnnotatedClass(UserProfile.class)
                    .addAnnotatedClass(MeetingRoom.class).addAnnotatedClass(Equipment.class)
                    .addAnnotatedClass(Booking.class).addAnnotatedClass(BookingEquipment.class)
                    .addAnnotatedClass(BookingStatusHistory.class).addAnnotatedClass(ItemImage.class).buildMetadata();
            try (var sessionFactory = metadata.buildSessionFactory()) {
                assertTrue(sessionFactory.isOpen());
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private Fixture fixtures() throws SQLException {
        long userId = insert("INSERT INTO users(username,email,password,role,active,created_at) VALUES(?,?,?,?,?,?)",
                "schema-fixture", "schema-fixture@example.invalid", "test-only-not-a-login-hash", "USER", true,
                LocalDate.of(2026, 10, 1));
        insert("INSERT INTO user_profile(user_id,full_name,phone,department) VALUES(?,?,?,?)",
                userId, "Schema fixture member", "0000000000", "Tests");
        long roomId = insert("INSERT INTO meeting_rooms(name,capacity,floor,room_type,status) VALUES(?,?,?,?,?)",
                "Schema fixture room", 4, "2", "VIP", "AVAILABLE");
        long equipmentId = insert("INSERT INTO equipment(name,total_quantity,category) VALUES(?,?,?)",
                "Schema fixture projector", 5, "Tests");
        long bookingId = insert(bookingInsert(userId, roomId));
        long linkId = insert("INSERT INTO booking_equipment(booking_id,equipment_id,quantity) VALUES(?,?,?)",
                bookingId, equipmentId, 2);
        insert("INSERT INTO booking_status_history(booking_id,old_status,new_status,changed_by,changed_at) "
                        + "VALUES(?,?,?,?,?)", bookingId, "PENDING", "APPROVED", userId,
                LocalDateTime.of(2026, 10, 1, 8, 1));
        insert("INSERT INTO item_images(item_type,item_id,sort_order,content_type,data) VALUES(?,?,?,?,?)",
                "ROOM", roomId, 0, "image/png", new byte[]{1, 2, 3});
        return new Fixture(userId, roomId, equipmentId, bookingId, linkId);
    }

    private String bookingInsert(long userId, long roomId) {
        return "INSERT INTO bookings(user_id,room_id,start_time,end_time,status,purpose,created_at) VALUES("
                + userId + "," + roomId + ",TIMESTAMP '2026-12-01 09:00:00',TIMESTAMP '2026-12-01 10:00:00',"
                + "'APPROVED','Schema fixture',TIMESTAMP '2026-10-01 08:00:00')";
    }

    private Map<String, List<List<Object>>> snapshot() throws SQLException {
        Map<String, List<List<Object>>> result = new HashMap<>();
        for (String table : TABLES) {
            try (var connection = database.getConnection(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
                List<List<Object>> values = new ArrayList<>();
                while (rows.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                        Object value = rows.getObject(column);
                        // byte[] (คอลัมน์รูป) เทียบด้วย equals ไม่ได้ จึงเก็บเป็น hex แทน
                        row.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : value);
                    }
                    values.add(row);
                }
                result.put(table, values);
            }
        }
        return result;
    }

    private void schema() throws SQLException {
        var resource = new ClassPathResource("db/postgresql/schema.sql");
        assertTrue(resource.exists(), "The production PostgreSQL schema resource must exist");
        ScriptUtils.executeSqlScript(schemaConnection, resource);
    }

    private void assertIntegrityViolation(String sql) {
        SQLException failure = assertThrows(SQLException.class, () -> execute(sql), sql);
        assertNotNull(failure.getSQLState(), sql);
        assertTrue(failure.getSQLState().startsWith("23"),
                "Expected an integrity constraint failure, not a syntax/missing-object error: " + sql + "; " + failure);
    }

    private long insert(String sql, Object... values) throws SQLException {
        try (var connection = database.getConnection();
             var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            assertEquals(1, statement.executeUpdate());
            try (var keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next(), "The schema must generate the identity without an explicit ID");
                long id = keys.getLong(1);
                assertTrue(id > 0);
                return id;
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long number(String sql) throws SQLException {
        try (var connection = database.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private record Fixture(long userId, long roomId, long equipmentId, long bookingId, long linkId) { }
}
