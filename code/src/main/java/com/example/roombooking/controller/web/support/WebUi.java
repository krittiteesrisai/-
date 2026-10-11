package com.example.roombooking.controller.web.support;

import com.example.roombooking.domain.enums.*;
import com.example.roombooking.dto.response.EquipmentResponse;
import com.example.roombooking.dto.response.RoomResponse;
import com.example.roombooking.service.ImageStorageService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.chrono.ThaiBuddhistChronology;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;

/** Presentation helpers shared by the server-rendered website. */
@Component("webUi")
@Profile("web")
public class WebUi {
    private final ImageStorageService images;
    public WebUi(ImageStorageService images) { this.images = images; }
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("th-TH"))
            .withChronology(ThaiBuddhistChronology.INSTANCE);
    public String date(TemporalAccessor value) { return value == null ? "—" : DATE.format(value); }
    public String time(TemporalAccessor value) { return value == null ? "—" : DateTimeFormatter.ofPattern("HH:mm").format(value); }
    // วันนี้ตามเวลาไทย ใช้เป็น min ของช่องเลือกวันจอง (เซิร์ฟเวอร์รันเป็น UTC)
    public String today() { return LocalDate.now(ZoneId.of("Asia/Bangkok")).toString(); }
    public String bookingCode(Long id) { return id == null ? "—" : String.format("BK-%04d", id); }
    public String roomTypeLabel(RoomType type) { return type == RoomType.VIP ? "VIP" : "มาตรฐาน"; }
    public String statusLabel(BookingStatus status) {
        if (status == null) return "—";
        return switch (status) {
            case PENDING -> "รออนุมัติ"; case APPROVED -> "อนุมัติแล้ว"; case REJECTED -> "ไม่อนุมัติ";
            case CANCELLED -> "ยกเลิกแล้ว"; case COMPLETED -> "ใช้งานเสร็จแล้ว";
        };
    }
    public String statusLabel(RoomStatus status) { return status == RoomStatus.AVAILABLE ? "เปิดให้จอง" : "ปิดปรับปรุง"; }
    public String statusClass(BookingStatus status) { return status == null ? "" : "status-" + status.name().toLowerCase(Locale.ROOT); }
    public String statusClass(RoomStatus status) { return status == RoomStatus.AVAILABLE ? "status-approved" : "status-cancelled"; }
    public String roleLabel(Role role) {
        if (role == null) return "—";
        return switch (role) { case USER -> "สมาชิก"; case STAFF -> "เจ้าหน้าที่"; case ADMIN -> "ผู้ดูแลระบบ"; };
    }
    // ใช้ลิงก์รูปที่แอดมินตั้งไว้ก่อน ถ้าไม่มีค่อยเดารูปจากชื่อแบบเดิม
    public String equipmentImage(EquipmentResponse equipment) {
        if (equipment == null) return equipmentImage((String) null);
        if (hasText(equipment.getImageUrl())) return equipment.getImageUrl();
        return equipmentImage(equipment.getName());
    }
    public String roomImage(RoomResponse room) {
        return room != null && hasText(room.getImageUrl()) ? room.getImageUrl() : "/assets/img/room-focus.png";
    }
    // รูปสำหรับสไลด์หน้ารายละเอียด: รูปที่อัปโหลดทั้งหมด ถ้าไม่มีใช้รูปเดียวแบบเดิม
    public List<String> roomGallery(RoomResponse room) {
        List<String> uploads = room == null ? List.of() : gallery(ImageStorageService.Kind.ROOM, room.getId());
        return uploads.isEmpty() ? List.of(roomImage(room)) : uploads;
    }
    public List<String> equipmentGallery(EquipmentResponse equipment) {
        List<String> uploads = equipment == null ? List.of() : gallery(ImageStorageService.Kind.EQUIPMENT, equipment.getId());
        return uploads.isEmpty() ? List.of(equipmentImage(equipment)) : uploads;
    }
    private List<String> gallery(ImageStorageService.Kind kind, Long id) {
        return id == null ? List.of() : images.imageIds(kind, id).stream().map(ImageStorageService::url).toList();
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    public String equipmentImage(String name) {
        String text = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (text.contains("โปรเจ") || text.contains("โปรเจค") || text.contains("projector")) return "/assets/img/projector.png";
        if (text.contains("ไมโคร") || text.contains("ไมค์") || text.contains("microphone")) return "/assets/img/microphone.png";
        if (text.contains("จอ") || text.contains("monitor")) return "/assets/img/monitor.png";
        return "/assets/img/equipment-placeholder.svg";
    }
}
