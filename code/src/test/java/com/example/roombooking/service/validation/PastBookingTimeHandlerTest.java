package com.example.roombooking.service.validation;

import com.example.roombooking.domain.entity.User;
import com.example.roombooking.dto.request.BookingCreateRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ห้ามจองช่วงเวลาที่เริ่มไปแล้ว โดยเทียบกับเวลาไทยแม้เซิร์ฟเวอร์จะเป็น UTC */
class PastBookingTimeHandlerTest {
    // 2026-10-11 03:00 UTC = 10:00 เวลาไทย
    private final PastBookingTimeHandler handler = new PastBookingTimeHandler(
            Clock.fixed(Instant.parse("2026-10-11T03:00:00Z"), ZoneOffset.UTC));

    @Test
    void rejectsAStartTimeThatHasAlreadyPassedInThailand() {
        // 09:00 ไทย ผ่านไปแล้ว แม้จะยังอยู่ "ในอนาคต" ถ้าเทียบกับนาฬิกา UTC (03:00)
        BookingValidationContext earlierToday = context(LocalDateTime.of(2026, 10, 11, 9, 0));
        BookingValidationContext yesterday = context(LocalDateTime.of(2026, 10, 10, 15, 0));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> handler.doValidate(earlierToday));
        assertTrue(failure.getMessage().contains("ผ่านมาแล้ว"));
        assertThrows(IllegalArgumentException.class, () -> handler.doValidate(yesterday));
    }

    @Test
    void acceptsNowAndLaterAndLeavesMissingTimesToTheOverlapHandler() {
        assertDoesNotThrow(() -> handler.doValidate(context(LocalDateTime.of(2026, 10, 11, 10, 0))));
        assertDoesNotThrow(() -> handler.doValidate(context(LocalDateTime.of(2026, 10, 12, 9, 0))));
        assertDoesNotThrow(() -> handler.doValidate(context(null)));
    }

    private BookingValidationContext context(LocalDateTime start) {
        BookingCreateRequest request = new BookingCreateRequest();
        request.setStartTime(start);
        request.setEndTime(start == null ? null : start.plusHours(1));
        return new BookingValidationContext(request, new User());
    }
}
