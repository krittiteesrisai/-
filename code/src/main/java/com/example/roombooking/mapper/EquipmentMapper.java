package com.example.roombooking.mapper;

import com.example.roombooking.domain.entity.Equipment;
import com.example.roombooking.dto.request.EquipmentCreateRequest;
import com.example.roombooking.dto.response.EquipmentResponse;
import org.springframework.stereotype.Component;

@Component
public class EquipmentMapper {

    public Equipment toEntity(EquipmentCreateRequest request) {
        Equipment equipment = new Equipment();
        equipment.setName(request.getName());
        equipment.setTotalQuantity(request.getTotalQuantity());
        equipment.setCategory(request.getCategory());
        equipment.setImageUrl(RoomMapper.normalizeImageUrl(request.getImageUrl()));
        return equipment;
    }

    public EquipmentResponse toResponse(Equipment entity) {
        EquipmentResponse response = new EquipmentResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setTotalQuantity(entity.getTotalQuantity());
        response.setCategory(entity.getCategory());
        response.setImageUrl(entity.getImageUrl());
        return response;
    }
}