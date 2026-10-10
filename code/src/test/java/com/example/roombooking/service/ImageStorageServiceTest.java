package com.example.roombooking.service;

import com.example.roombooking.controller.web.ImageViewController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** เก็บ/อ่าน/ล้างไฟล์รูปบน schema.sql จริง (H2 โหมด PostgreSQL) และส่งรูปผ่าน /images/... */
class ImageStorageServiceTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    private DriverManagerDataSource database;
    private Connection schemaConnection;
    private JdbcTemplate jdbc;
    private ImageStorageService images;

    @BeforeEach
    void createSchemaFromProductionScript() throws Exception {
        database = new DriverManagerDataSource("jdbc:h2:mem:images-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        // H2 ต้องเปิด connection ที่สร้าง schema ค้างไว้ ไม่งั้น CHECK constraint ใช้ไม่ได้ (เหมือน PostgresqlSchemaTest)
        schemaConnection = database.getConnection();
        ScriptUtils.executeSqlScript(schemaConnection, new ClassPathResource("db/postgresql/schema.sql"));
        jdbc = new JdbcTemplate(database);
        jdbc.update("INSERT INTO meeting_rooms (id, name, capacity, floor, room_type, status) VALUES (1, 'A', 4, '1', 'STANDARD', 'AVAILABLE')");
        jdbc.update("INSERT INTO equipment (id, name, total_quantity, category) VALUES (2, 'Mic', 3, 'AV')");
        images = new ImageStorageService(jdbc);
    }

    @AfterEach
    void shutdown() throws Exception {
        try {
            schemaConnection.close();
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void detectsOnlySupportedImageTypesFromTheirBytes() {
        assertEquals("image/png", ImageStorageService.detectContentType(PNG).orElseThrow());
        assertEquals("image/jpeg", ImageStorageService.detectContentType(JPEG).orElseThrow());
        assertTrue(ImageStorageService.detectContentType("<svg onload=alert(1)></svg>".getBytes()).isEmpty());
        assertTrue(ImageStorageService.detectContentType(new byte[3]).isEmpty());
    }

    @Test
    void savePointsImageUrlAtTheStoredFileAndLoadReturnsIt() {
        String url = images.save(ImageStorageService.Kind.ROOMS, 1L, PNG);

        assertTrue(url.startsWith("/images/rooms/1?v="));
        assertEquals(url, jdbc.queryForObject("SELECT image_url FROM meeting_rooms WHERE id = 1", String.class));
        ImageStorageService.StoredImage stored = images.load(ImageStorageService.Kind.ROOMS, 1L).orElseThrow();
        assertEquals("image/png", stored.getContentType());
        assertArrayEquals(PNG, stored.getData());
        assertTrue(images.load(ImageStorageService.Kind.EQUIPMENT, 2L).isEmpty());
    }

    @Test
    void clearKeepsTheFileWhileTheLinkStillPointsAtIt() {
        String url = images.save(ImageStorageService.Kind.EQUIPMENT, 2L, JPEG);

        images.clearUnlessStored(ImageStorageService.Kind.EQUIPMENT, 2L, url);
        assertTrue(images.load(ImageStorageService.Kind.EQUIPMENT, 2L).isPresent());

        images.clearUnlessStored(ImageStorageService.Kind.EQUIPMENT, 2L, "https://example.com/mic.png");
        assertTrue(images.load(ImageStorageService.Kind.EQUIPMENT, 2L).isEmpty());
    }

    @Test
    void rejectsUnsupportedOrOversizedFiles() {
        assertThrows(IllegalArgumentException.class,
                () -> images.save(ImageStorageService.Kind.ROOMS, 1L, "not an image".getBytes()));
        byte[] big = new byte[(int) ImageStorageService.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThrows(IllegalArgumentException.class, () -> images.save(ImageStorageService.Kind.ROOMS, 1L, big));
    }

    @Test
    void imageEndpointServesStoredBytesAndReturns404Otherwise() throws Exception {
        images.save(ImageStorageService.Kind.ROOMS, 1L, PNG);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ImageViewController(images)).build();

        mvc.perform(get("/images/rooms/1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));
        mvc.perform(get("/images/equipment/2")).andExpect(status().isNotFound());
        mvc.perform(get("/images/users/1")).andExpect(status().isNotFound());
    }
}
