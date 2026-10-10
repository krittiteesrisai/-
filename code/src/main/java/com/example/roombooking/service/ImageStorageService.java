package com.example.roombooking.service;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * อัลบั้มรูปของห้อง/อุปกรณ์ (ตาราง item_images ห้องหรืออุปกรณ์ละหลายรูป)
 * ไม่ map เข้า entity เพื่อไม่ให้หน้า list ต้องโหลดไบต์รูปมาด้วยทุกครั้ง
 * image_url ของห้อง/อุปกรณ์ใช้เป็น "รูปปก" และถูกอัปเดตให้ชี้รูปแรกของอัลบั้มผ่าน syncCover
 */
@Service
public class ImageStorageService {

    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final int MAX_PER_ITEM = 6;

    public enum Kind {
        ROOM("meeting_rooms"),
        EQUIPMENT("equipment");

        private final String table;

        Kind(String table) {
            this.table = table;
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

    public static String url(long imageId) {
        return "/images/" + imageId;
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

    /** id ของรูปทั้งหมดในอัลบั้ม เรียงตามลำดับ (ตัวแรก = รูปปก) */
    public List<Long> imageIds(Kind kind, Long itemId) {
        return jdbc.queryForList("SELECT id FROM item_images WHERE item_type = ? AND item_id = ? ORDER BY sort_order, id",
                Long.class, kind.name(), itemId);
    }

    /** เพิ่มรูปท้ายอัลบั้ม คืนค่า id ของรูปใหม่ */
    public long add(Kind kind, Long itemId, byte[] data) {
        String contentType = detectContentType(data)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported image type"));
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("Image is too large");
        if (imageIds(kind, itemId).size() >= MAX_PER_ITEM) throw new IllegalStateException("Too many images");
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM item_images WHERE item_type = ? AND item_id = ?",
                Integer.class, kind.name(), itemId);
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO item_images (item_type, item_id, sort_order, content_type, data) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            insert.setString(1, kind.name());
            insert.setLong(2, itemId);
            insert.setInt(3, next);
            insert.setString(4, contentType);
            insert.setBytes(5, data);
            return insert;
        }, key);
        return key.getKey().longValue();
    }

    public Optional<StoredImage> load(long imageId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT content_type, data FROM item_images WHERE id = ?",
                    (rs, row) -> new StoredImage(rs.getString(1), rs.getBytes(2)), imageId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public void delete(Kind kind, Long itemId, long imageId) {
        jdbc.update("DELETE FROM item_images WHERE id = ? AND item_type = ? AND item_id = ?", imageId, kind.name(), itemId);
    }

    /** ย้ายรูปไปไว้หน้าสุดของอัลบั้ม (เป็นรูปปก) */
    public void makeCover(Kind kind, Long itemId, long imageId) {
        jdbc.update("UPDATE item_images SET sort_order = (SELECT COALESCE(MIN(sort_order), 0) - 1 FROM item_images "
                        + "WHERE item_type = ? AND item_id = ?) WHERE id = ? AND item_type = ? AND item_id = ?",
                kind.name(), itemId, imageId, kind.name(), itemId);
    }

    public void deleteAll(Kind kind, Long itemId) {
        jdbc.update("DELETE FROM item_images WHERE item_type = ? AND item_id = ?", kind.name(), itemId);
    }

    /**
     * ตั้ง image_url (รูปปก) ให้ชี้รูปแรกของอัลบั้ม ถ้าอัลบั้มว่างใช้ลิงก์ภายนอกที่แอดมินกรอก
     * (ลิงก์ /images/... ที่ไม่มีรูปแล้วจะถูกล้างเป็น null ให้หน้าเว็บกลับไปใช้รูปเดิมของระบบ)
     */
    public String syncCover(Kind kind, Long itemId, String externalUrl) {
        List<Long> ids = imageIds(kind, itemId);
        String cover = !ids.isEmpty() ? url(ids.get(0))
                : externalUrl == null || externalUrl.isBlank() || externalUrl.startsWith("/images/") ? null
                : externalUrl.strip();
        jdbc.update("UPDATE " + kind.table + " SET image_url = ? WHERE id = ?", cover, itemId);
        return cover;
    }
}
