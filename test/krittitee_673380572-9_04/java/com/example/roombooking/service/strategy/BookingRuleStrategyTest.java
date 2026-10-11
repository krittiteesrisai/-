package com.example.roombooking.service.strategy;

import com.example.roombooking.domain.enums.RoomType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingRuleStrategyTest {

    private final BookingRuleStrategyFactory factory = new BookingRuleStrategyFactory(
            List.of(new VipRoomRuleStrategy(), new StandardRoomRuleStrategy()));

    @Test
    void vipRoom_requiresApproval() {
        assertTrue(new VipRoomRuleStrategy().requiresApproval());
    }

    @Test
    void standardRoom_requiresApproval() {
        // กฎใหม่: ห้องมาตรฐานต้องรอผู้ดูแลระบบอนุมัติเหมือน VIP
        assertTrue(new StandardRoomRuleStrategy().requiresApproval());
    }

    @Test
    void factory_returnsVipStrategyForVipRoom() {
        assertInstanceOf(VipRoomRuleStrategy.class, factory.getStrategy(RoomType.VIP));
    }

    @Test
    void factory_returnsStandardStrategyForStandardRoom() {
        assertInstanceOf(StandardRoomRuleStrategy.class, factory.getStrategy(RoomType.STANDARD));
    }

    @Test
    void factory_noStrategyRegistered_throwsIllegalArgument() {
        BookingRuleStrategyFactory onlyVip =
                new BookingRuleStrategyFactory(List.of(new VipRoomRuleStrategy()));

        assertThrows(IllegalArgumentException.class, () -> onlyVip.getStrategy(RoomType.STANDARD));
    }
}
