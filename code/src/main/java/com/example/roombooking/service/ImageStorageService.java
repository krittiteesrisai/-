package com.example.roombooking.service;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * เก็บไฟล์รูปของห้อง/อุปกรณ์ไว้ในคอลัมน์ image_data ของตารางเดิม
 * ไม่ map เข้า entity เพื่อไม่ให้หน้า list ต้องโหลดไบต์รูปมาด้วยทุกครั้ง
 */
@Service
public class ImageStorageService {

    public static final long MAX_BYTES = 2L * 1024 * 1024;

    public enum Kind {
        ROOMS("rooms", "meeting_rooms"),
        EQUIPMENT("equipment", "equipment");

        private final String path;
        private final String table;

        Kind(String path, String table) {
            this.path = path;
            this.table = table;
        }

        public String getPath() {
            return path;
        }

        public static Optional<Kind> fromPath(String path) {
            for (Kind kind : values()) {
                if (kind.path.equals(path)) return Optional.of(kind);
            }
            return Optional.empty();
        }
    }

    public static class StoredImage {
        private final String contentType;
        private final byte[] data;

        public StoredImage(String contentType, byte[] data) {
            this.contentType = contentType;
            this.data = data;
        }

        public String getContentType() {
            return contentType;
        }

        public byte[] getData() {
            return data;
        }
    }

    private final JdbcTemplate jdbc;

    public ImageStorageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** ดูชนิดไฟล์จากไบต์จริง (ไม่เชื่อ content type ที่เบราว์เซอร์ส่งมา) รับแค่ png, jpeg, gif, webp */
    public static Optional<String> detectContentType(byte[] data) {
        if (data == null || data.length < 12) return Optional.empty();
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return Optional.of("image/png");
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) return Optional.of("image/jpeg");
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') return Optional.of("image/gif");
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return Optional.of("image/webp");
        return Optional.empty();
    }

    /** บันทึกรูปและตั้ง image_url ให้ชี้มาที่รูปนี้ คืนค่า URL ที่ตั้งไว้ */
    public String save(Kind kind, Long id, byte[] data) {
        String contentType = detectContentType(data)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported image type"));
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("Image is too large");
        // ?v= เปลี่ยนทุกครั้งที่อัปโหลดใหม่ เบราว์เซอร์จะได้ไม่ใช้รูปเก่าจากแคช
        String url = "/images/" + kind.path + "/" + id + "?v=" + System.currentTimeMillis();
        jdbc.update("UPDATE " + kind.table + " SET image_data = ?, image_content_type = ?, image_url = ? WHERE id = ?",
                data, contentType, url, id);
        return url;
    }

    /** ลบไฟล์รูปที่เก็บไว้ ถ้า imageUrl ใหม่ไม่ได้ชี้มาที่รูปที่อัปโหลด */
    public void clearUnlessStored(Kind kind, Long id, String imageUrl) {
        if (imageUrl != null && imageUrl.startsWith("/images/" + kind.path + "/" + id)) return;
        jdbc.update("UPDATE " + kind.table + " SET image_data = NULL, image_content_type = NULL "
                + "WHERE id = ? AND image_data IS NOT NULL", id);
    }

    public Optional<StoredImage> load(Kind kind, Long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT image_content_type, image_data FROM " + kind.table + " WHERE id = ? AND image_data IS NOT NULL",
                    (rs, row) -> new StoredImage(rs.getString(1), rs.getBytes(2)), id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
}
