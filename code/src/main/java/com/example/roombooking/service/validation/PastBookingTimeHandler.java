package com.example.roombooking.service.validation;

import com.example.roombooking.dto.request.BookingCreateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * ห้ามจอง (หรือแก้การจองให้เป็น) ช่วงเวลาที่เริ่มไปแล้ว
 * เทียบกับเวลาไทย เพราะเวลาที่ผู้ใช้กรอกเป็นเวลาไทย แต่เซิร์ฟเวอร์ (Render) รันเป็น UTC
 */
@Component
@Order(5)
public class PastBookingTimeHandler extends BookingValidationHandler {
    public static final ZoneId BOOKING_ZONE = ZoneId.of("Asia/Bangkok");

    private final Clock clock;

    @Autowired
    public PastBookingTimeHandler() {
        this(Clock.system(BOOKING_ZONE));
    }

    public PastBookingTimeHandler(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doValidate(BookingValidationContext context) {
        BookingCreateRequest request = getRequest(context);
        // เวลาว่าง/กลับด้าน TimeOverlapHandler เป็นคนแจ้ง
        if (request.getStartTime() == null) return;
        if (request.getStartTime().isBefore(LocalDateTime.now(clock.withZone(BOOKING_ZONE)))) {
            throw new IllegalArgumentException("ไม่สามารถจองช่วงเวลาที่ผ่านมาแล้ว กรุณาเลือกวันและเวลาใหม่");
        }
    }
}
