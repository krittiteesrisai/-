package com.example.roombooking.controller.web.support;

import com.example.roombooking.domain.enums.*;
import com.example.roombooking.dto.response.EquipmentResponse;
import com.example.roombooking.dto.response.RoomResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.chrono.ThaiBuddhistChronology;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;

/** Presentation helpers shared by the server-rendered website. */
@Component("webUi")
@Profile("web")
public class WebUi {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("th-TH"))
            .withChronology(ThaiBuddhistChronology.INSTANCE);
    public String date(TemporalAccessor value) { return value == null ? "—" : DATE.format(value); }
    public String time(TemporalAccessor value) { return value == null ? "—" : DateTimeFormatter.ofPattern("HH:mm").format(value); }
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
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    public String equipmentImage(String name) {
        String text = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (text.contains("โปรเจ") || text.contains("โปรเจค") || text.contains("projector")) return "/assets/img/projector.png";
        if (text.contains("ไมโคร") || text.contains("ไมค์") || text.contains("microphone")) return "/assets/img/microphone.png";
        if (text.contains("จอ") || text.contains("monitor")) return "/assets/img/monitor.png";
        return "/assets/img/equipment-placeholder.svg";
    }
}
