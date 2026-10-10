package com.example.roombooking.mapper;

import com.example.roombooking.controller.web.form.AdminEquipmentForm;
import com.example.roombooking.controller.web.form.AdminRoomForm;
import com.example.roombooking.controller.web.support.WebUi;
import com.example.roombooking.domain.entity.Equipment;
import com.example.roombooking.domain.entity.MeetingRoom;
import com.example.roombooking.domain.enums.RoomStatus;
import com.example.roombooking.domain.enums.RoomType;
import com.example.roombooking.dto.request.EquipmentCreateRequest;
import com.example.roombooking.dto.request.RoomCreateRequest;
import com.example.roombooking.dto.response.EquipmentResponse;
import com.example.roombooking.dto.response.RoomResponse;
import com.example.roombooking.repository.EquipmentRepository;
import com.example.roombooking.repository.MeetingRoomRepository;
import com.example.roombooking.service.impl.EquipmentServiceImpl;
import com.example.roombooking.service.impl.RoomServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** imageUrl ของห้องและอุปกรณ์: map ไปกลับ, update ผ่าน service, และ validation */
class ImageUrlMappingTest {

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void roomMapperMapsImageUrlBothWays() {
        RoomMapper mapper = new RoomMapper();
        RoomCreateRequest request = roomRequest(" https://example.com/room.png ");

        MeetingRoom entity = mapper.toEntity(request);
        assertEquals("https://example.com/room.png", entity.getImageUrl());

        RoomResponse response = mapper.toResponse(entity);
        assertEquals("https://example.com/room.png", response.getImageUrl());
    }

    @Test
    void equipmentMapperMapsImageUrlBothWaysAndBlankBecomesNull() {
        EquipmentMapper mapper = new EquipmentMapper();

        Equipment entity = mapper.toEntity(equipmentRequest("https://example.com/mic.png"));
        assertEquals("https://example.com/mic.png", entity.getImageUrl());
        assertEquals("https://example.com/mic.png", mapper.toResponse(entity).getImageUrl());

        assertNull(mapper.toEntity(equipmentRequest("   ")).getImageUrl());
        assertNull(mapper.toEntity(equipmentRequest(null)).getImageUrl());
    }

    @Test
    void updateRoomSetsAndClearsImageUrl() {
        MeetingRoomRepository repository = mock(MeetingRoomRepository.class);
        MeetingRoom existing = new MeetingRoom();
        existing.setId(1L);
        existing.setImageUrl("https://example.com/old.png");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(MeetingRoom.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RoomServiceImpl service = new RoomServiceImpl(repository, new RoomMapper());

        assertEquals("https://example.com/new.png",
                service.updateRoom(1L, roomRequest("https://example.com/new.png")).getImageUrl());
        assertNull(service.updateRoom(1L, roomRequest("")).getImageUrl());
        assertNull(existing.getImageUrl());
    }

    @Test
    void updateEquipmentSetsAndClearsImageUrl() {
        EquipmentRepository repository = mock(EquipmentRepository.class);
        Equipment existing = new Equipment();
        existing.setId(2L);
        when(repository.findById(2L)).thenReturn(Optional.of(existing));
        when(repository.save(any(Equipment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EquipmentServiceImpl service = new EquipmentServiceImpl(repository, new EquipmentMapper());

        EquipmentResponse updated = service.updateEquipment(2L, equipmentRequest("http://example.com/projector.jpg"));
        assertEquals("http://example.com/projector.jpg", updated.getImageUrl());
        assertNull(service.updateEquipment(2L, equipmentRequest(null)).getImageUrl());
    }

    @Test
    void requestValidationRejectsNonHttpImageUrl() {
        assertTrue(validator.validateProperty(roomRequest("https://example.com/a.png"), "imageUrl").isEmpty());
        assertTrue(validator.validateProperty(roomRequest(""), "imageUrl").isEmpty());
        assertTrue(validator.validateProperty(roomRequest(null), "imageUrl").isEmpty());
        assertFalse(validator.validateProperty(roomRequest("ftp://example.com/a.png"), "imageUrl").isEmpty());
        assertFalse(validator.validateProperty(roomRequest("javascript:alert(1)"), "imageUrl").isEmpty());

        assertTrue(validator.validateProperty(equipmentRequest("http://example.com/a.png"), "imageUrl").isEmpty());
        assertFalse(validator.validateProperty(equipmentRequest("example.com/a.png"), "imageUrl").isEmpty());
        assertFalse(validator.validateProperty(equipmentRequest("https://" + "a".repeat(500)), "imageUrl").isEmpty());
    }

    @Test
    void adminFormValidationRejectsNonHttpImageUrl() {
        AdminRoomForm room = new AdminRoomForm();
        room.setImageUrl("/assets/img/room-focus.png");
        assertFalse(validator.validateProperty(room, "imageUrl").isEmpty());
        room.setImageUrl("https://example.com/room.png");
        assertTrue(validator.validateProperty(room, "imageUrl").isEmpty());

        AdminEquipmentForm equipment = new AdminEquipmentForm();
        equipment.setImageUrl("data:image/png;base64,AAAA");
        assertFalse(validator.validateProperty(equipment, "imageUrl").isEmpty());
        equipment.setImageUrl("");
        assertTrue(validator.validateProperty(equipment, "imageUrl").isEmpty());
    }

    @Test
    void webUiPrefersImageUrlAndFallsBackToOldImages() {
        WebUi ui = new WebUi();
        RoomResponse room = new RoomResponse();
        assertEquals("/assets/img/room-focus.png", ui.roomImage(room));
        room.setImageUrl("https://example.com/room.png");
        assertEquals("https://example.com/room.png", ui.roomImage(room));

        EquipmentResponse projector = new EquipmentResponse();
        projector.setName("Projector");
        assertEquals("/assets/img/projector.png", ui.equipmentImage(projector));
        projector.setImageUrl("https://example.com/p.png");
        assertEquals("https://example.com/p.png", ui.equipmentImage(projector));

        EquipmentResponse other = new EquipmentResponse();
        other.setName("Whiteboard");
        assertEquals("/assets/img/equipment-placeholder.svg", ui.equipmentImage(other));
    }

    private static RoomCreateRequest roomRequest(String imageUrl) {
        RoomCreateRequest request = new RoomCreateRequest();
        request.setName("Room A");
        request.setCapacity(6);
        request.setFloor("2");
        request.setRoomType(RoomType.STANDARD);
        request.setStatus(RoomStatus.AVAILABLE);
        request.setImageUrl(imageUrl);
        return request;
    }

    private static EquipmentCreateRequest equipmentRequest(String imageUrl) {
        EquipmentCreateRequest request = new EquipmentCreateRequest();
        request.setName("Projector");
        request.setTotalQuantity(3);
        request.setCategory("AV");
        request.setImageUrl(imageUrl);
        return request;
    }
}
