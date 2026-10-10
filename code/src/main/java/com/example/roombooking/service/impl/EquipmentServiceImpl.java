package com.example.roombooking.service.impl;

import com.example.roombooking.domain.entity.Equipment;
import com.example.roombooking.dto.request.EquipmentCreateRequest;
import com.example.roombooking.dto.response.EquipmentResponse;
import com.example.roombooking.exception.ConflictException;
import com.example.roombooking.exception.ResourceNotFoundException;
import com.example.roombooking.mapper.EquipmentMapper;
import com.example.roombooking.mapper.RoomMapper;
import com.example.roombooking.repository.EquipmentRepository;
import com.example.roombooking.service.EquipmentService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EquipmentServiceImpl implements EquipmentService {

    private final EquipmentRepository equipmentRepository;
    private final EquipmentMapper equipmentMapper;

    public EquipmentServiceImpl(EquipmentRepository equipmentRepository, EquipmentMapper equipmentMapper) {
        this.equipmentRepository = equipmentRepository;
        this.equipmentMapper = equipmentMapper;
    }

    @Override
    @Transactional
    public EquipmentResponse createEquipment(EquipmentCreateRequest request) {
        if (request.getTotalQuantity() != null && request.getTotalQuantity() < 0) {
            throw new IllegalArgumentException("Total quantity cannot be negative");
        }
        Equipment equipment = equipmentMapper.toEntity(request);
        Equipment savedEquipment = equipmentRepository.save(equipment);
        return equipmentMapper.toResponse(savedEquipment);
    }

    @Override
    public EquipmentResponse getEquipmentById(Long id) {
        Equipment equipment = findEquipmentOrThrow(id);
        return equipmentMapper.toResponse(equipment);
    }

    @Override
    public Page<EquipmentResponse> getAllEquipments(Pageable pageable) {
        return equipmentRepository.findAll(pageable)
                .map(equipmentMapper::toResponse);
    }

    @Override
    @Transactional
    public EquipmentResponse updateEquipment(Long id, EquipmentCreateRequest request) {
        Equipment existingEquipment = findEquipmentOrThrow(id);

        if (request.getTotalQuantity() != null && request.getTotalQuantity() < 0) {
            throw new IllegalArgumentException("Total quantity cannot be negative");
        }

        existingEquipment.setName(request.getName());
        existingEquipment.setTotalQuantity(request.getTotalQuantity());
        existingEquipment.setCategory(request.getCategory());
        existingEquipment.setImageUrl(RoomMapper.normalizeImageUrl(request.getImageUrl()));

        Equipment updatedEquipment = equipmentRepository.save(existingEquipment);
        return equipmentMapper.toResponse(updatedEquipment);
    }

    @Override
    @Transactional
    public void deleteEquipment(Long id) {
        Equipment existingEquipment = findEquipmentOrThrow(id);
        try {
            equipmentRepository.delete(existingEquipment);
            // flush ทันที เพื่อให้ foreign key ชนตรงนี้ (ไม่ใช่ตอน commit) แล้วจับได้ใน try
            equipmentRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(
                    "ไม่สามารถลบอุปกรณ์ได้ เพราะมีการจองที่ใช้อุปกรณ์นี้อยู่ (id: " + id + ")");
        }
    }

    private Equipment findEquipmentOrThrow(Long id) {
        return equipmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Equipment not found with id: " + id));
    }
}