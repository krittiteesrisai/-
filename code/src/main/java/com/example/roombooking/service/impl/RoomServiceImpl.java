package com.example.roombooking.service.impl;

import com.example.roombooking.domain.entity.MeetingRoom;
import com.example.roombooking.dto.request.RoomCreateRequest;
import com.example.roombooking.dto.response.RoomResponse;
import com.example.roombooking.exception.ConflictException;
import com.example.roombooking.exception.ResourceNotFoundException;
import com.example.roombooking.mapper.RoomMapper;
import com.example.roombooking.repository.MeetingRoomRepository;
import com.example.roombooking.service.RoomService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomServiceImpl implements RoomService {

    private final MeetingRoomRepository meetingRoomRepository;
    private final RoomMapper roomMapper;

    public RoomServiceImpl(MeetingRoomRepository meetingRoomRepository, RoomMapper roomMapper) {
        this.meetingRoomRepository = meetingRoomRepository;
        this.roomMapper = roomMapper;
    }

    @Override
    @Transactional
    public RoomResponse createRoom(RoomCreateRequest request) {
        MeetingRoom room = roomMapper.toEntity(request);
        MeetingRoom savedRoom = meetingRoomRepository.save(room);
        return roomMapper.toResponse(savedRoom);
    }

    @Override
    @Transactional(readOnly = true)
    public RoomResponse getRoomById(Long id) {
        return roomMapper.toResponse(findRoomOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RoomResponse> getAllRooms(Pageable pageable) {
        return meetingRoomRepository.findAll(pageable)
                .map(roomMapper::toResponse);
    }

    @Override
    @Transactional
    public RoomResponse updateRoom(Long id, RoomCreateRequest request) {
        MeetingRoom existingRoom = findRoomOrThrow(id);

        existingRoom.setName(request.getName());
        existingRoom.setCapacity(request.getCapacity());
        existingRoom.setFloor(request.getFloor());
        existingRoom.setRoomType(request.getRoomType());
        existingRoom.setStatus(request.getStatus());
        existingRoom.setImageUrl(RoomMapper.normalizeImageUrl(request.getImageUrl()));

        MeetingRoom updatedRoom = meetingRoomRepository.save(existingRoom);
        return roomMapper.toResponse(updatedRoom);
    }

    @Override
    @Transactional
    public void deleteRoom(Long id) {
        MeetingRoom existingRoom = findRoomOrThrow(id);
        try {
            meetingRoomRepository.delete(existingRoom);
            // flush ทันที เพื่อให้ foreign key ชนตรงนี้ (ไม่ใช่ตอน commit) แล้วจับได้ใน try
            meetingRoomRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(
                    "ไม่สามารถลบห้องได้ เพราะมีการจองที่เกี่ยวข้องกับห้องนี้อยู่ (id: " + id + ")");
        }
    }

    private MeetingRoom findRoomOrThrow(Long id) {
        return meetingRoomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Meeting room not found with id: " + id));
    }
}