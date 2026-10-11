package com.example.roombooking.service.strategy;

import com.example.roombooking.domain.enums.RoomType;
import org.springframework.stereotype.Component;

@Component
public class StandardRoomRuleStrategy implements BookingRuleStrategy {

    @Override
    public RoomType getRoomType() {
        return RoomType.STANDARD;
    }

    @Override
    public boolean requiresApproval() {
        return true;
    }
}