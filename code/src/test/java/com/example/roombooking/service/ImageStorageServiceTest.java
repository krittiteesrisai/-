package com.example.roombooking.service;

import com.example.roombooking.controller.web.ImageViewController;
import com.example.roombooking.service.ImageStorageService.Kind;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** อัลบั้มรูปบน schema.sql จริง (H2 โหมด PostgreSQL) และการส่งรูปผ่าน /images/{id} */
class ImageStorageServiceTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    private Connection schemaConnection;
    private JdbcTemplate jdbc;
    private ImageStorageService images;

    @BeforeEach
    void createSchemaFromProductionScript() throws Exception {
        DriverManagerDataSource database = new DriverManagerDataSource("jdbc:h2:mem:images-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        // H2 ต้องเปิด connection ที่สร้าง schema ค้างไว้ ไม่งั้น CHECK constraint ใช้ไม่ได้ (เหมือน PostgresqlSchemaTest)
        schemaConnection = database.getConnection();
        ScriptUtils.executeSqlScript(schemaConnection, new ClassPathResource("db/postgresql/schema.sql"));
        jdbc = new JdbcTemplate(database);
        jdbc.update("INSERT INTO meeting_rooms (id, name, capacity, floor, room_type, status) VALUES (1, 'A', 4, '1', 'STANDARD', 'AVAILABLE')");
        jdbc.update("INSERT INTO equipment (id, name, total_quantity, category) VALUES (1, 'Mic', 3, 'AV')");
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
    void albumKeepsUploadOrderAndIsSeparatePerItem() {
        long first = images.add(Kind.ROOM, 1L, PNG);
        long second = images.add(Kind.ROOM, 1L, JPEG);
        long equipmentImage = images.add(Kind.EQUIPMENT, 1L, PNG);

        assertEquals(List.of(first, second), images.imageIds(Kind.ROOM, 1L));
        assertEquals(List.of(equipmentImage), images.imageIds(Kind.EQUIPMENT, 1L));
        ImageStorageService.StoredImage stored = images.load(second).orElseThrow();
        assertEquals("image/jpeg", stored.getContentType());
        assertArrayEquals(JPEG, stored.getData());
    }

    @Test
    void coverFollowsTheFirstImageAndFallsBackToTheExternalLink() {
        assertEquals("https://example.com/a.png", images.syncCover(Kind.ROOM, 1L, "https://example.com/a.png"));
        long first = images.add(Kind.ROOM, 1L, PNG);
        long second = images.add(Kind.ROOM, 1L, PNG);

        assertEquals("/images/" + first, images.syncCover(Kind.ROOM, 1L, "https://example.com/a.png"));
        images.makeCover(Kind.ROOM, 1L, second);
        assertEquals(List.of(second, first), images.imageIds(Kind.ROOM, 1L));
        assertEquals("/images/" + second, images.syncCover(Kind.ROOM, 1L, null));
        assertEquals("/images/" + second, jdbc.queryForObject("SELECT image_url FROM meeting_rooms WHERE id = 1", String.class));

        images.delete(Kind.ROOM, 1L, second);
        images.delete(Kind.ROOM, 1L, first);
        assertNull(images.syncCover(Kind.ROOM, 1L, null));
        assertNull(jdbc.queryForObject("SELECT image_url FROM meeting_rooms WHERE id = 1", String.class));
    }

    @Test
    void deleteOnlyTouchesTheGivenItem() {
        long roomImage = images.add(Kind.ROOM, 1L, PNG);
        images.add(Kind.EQUIPMENT, 1L, PNG);

        images.delete(Kind.EQUIPMENT, 1L, roomImage);
        assertEquals(List.of(roomImage), images.imageIds(Kind.ROOM, 1L));

        images.deleteAll(Kind.EQUIPMENT, 1L);
        assertTrue(images.imageIds(Kind.EQUIPMENT, 1L).isEmpty());
        assertEquals(1, images.imageIds(Kind.ROOM, 1L).size());
    }

    @Test
    void rejectsUnsupportedOversizedOrTooManyFiles() {
        assertThrows(IllegalArgumentException.class, () -> images.add(Kind.ROOM, 1L, "not an image".getBytes()));
        byte[] big = new byte[(int) ImageStorageService.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThrows(IllegalArgumentException.class, () -> images.add(Kind.ROOM, 1L, big));
        for (int i = 0; i < ImageStorageService.MAX_PER_ITEM; i++) images.add(Kind.ROOM, 1L, PNG);
        assertThrows(IllegalStateException.class, () -> images.add(Kind.ROOM, 1L, PNG));
    }

    @Test
    void imageEndpointServesStoredBytesAndReturns404Otherwise() throws Exception {
        long id = images.add(Kind.ROOM, 1L, PNG);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ImageViewController(images)).build();

        mvc.perform(get("/images/" + id))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));
        mvc.perform(get("/images/" + (id + 100))).andExpect(status().isNotFound());
    }
}
