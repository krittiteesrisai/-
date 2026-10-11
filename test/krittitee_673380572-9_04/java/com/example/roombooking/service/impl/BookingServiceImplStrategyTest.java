package com.example.roombooking.service.impl;

import com.example.roombooking.domain.entity.Booking;
import com.example.roombooking.domain.entity.MeetingRoom;
import com.example.roombooking.domain.entity.User;
import com.example.roombooking.domain.enums.BookingStatus;
import com.example.roombooking.domain.enums.RoomStatus;
import com.example.roombooking.domain.enums.RoomType;
import com.example.roombooking.dto.request.BookingCreateRequest;
import com.example.roombooking.dto.response.BookingResponse;
import com.example.roombooking.mapper.BookingMapper;
import com.example.roombooking.repository.BookingRepository;
import com.example.roombooking.repository.EquipmentRepository;
import com.example.roombooking.repository.UserRepository;
import com.example.roombooking.service.strategy.BookingRuleStrategyFactory;
import com.example.roombooking.service.strategy.StandardRoomRuleStrategy;
import com.example.roombooking.service.strategy.VipRoomRuleStrategy;
import com.example.roombooking.service.validation.BookingValidationChain;
import com.example.roombooking.service.validation.BookingValidationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ทดสอบว่า BookingServiceImpl.createBooking() เรียกใช้ Strategy Pattern จริง
 * กฎใหม่: ทั้ง STANDARD และ VIP เริ่มที่รออนุมัติ (PENDING)
 * ใช้ BookingRuleStrategyFactory ตัวจริง เพื่อทดสอบว่าเลือก strategy ถูกตัว
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceImplStrategyTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private EquipmentRepository equipmentRepository;
    @Mock
    private BookingValidationChain validationChain;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private BookingServiceImpl bookingService;

    @BeforeEach
    void setUp() {
        BookingRuleStrategyFactory factory = new BookingRuleStrategyFactory(
                List.of(new StandardRoomRuleStrategy(), new VipRoomRuleStrategy()));
        bookingService = new BookingServiceImpl(bookingRepository, userRepository, equipmentRepository,
                validationChain, new BookingMapper(), eventPublisher, factory);
    }

    private MeetingRoom buildRoom(RoomType type) {
        MeetingRoom room = new MeetingRoom();
        room.setId(10L);
        room.setName("Room " + type);
        room.setCapacity(8);
        room.setRoomType(type);
        room.setStatus(RoomStatus.AVAILABLE);
        return room;
    }

    private BookingCreateRequest buildRequest() {
        BookingCreateRequest request = new BookingCreateRequest();
        request.setRoomId(10L);
        request.setStartTime(LocalDateTime.of(2026, 12, 1, 9, 0));
        request.setEndTime(LocalDateTime.of(2026, 12, 1, 11, 0));
        request.setPurpose("Team meeting");
        return request;
    }

    // validation chain เป็น mock จึงต้องจำลองสิ่งที่ RoomAvailabilityHandler ทำ คือใส่ห้องลงใน context
    private void validationChainFindsRoom(MeetingRoom room) {
        doAnswer(invocation -> {
            BookingValidationContext context = invocation.getArgument(0);
            context.setRoom(room);
            return null;
        }).when(validationChain).validate(any(BookingValidationContext.class));
    }

    private void givenRequesterAndSave() {
        User requester = new User();
        requester.setId(1L);
        requester.setUsername("tanny");
        when(userRepository.findById(1L)).thenReturn(Optional.of(requester));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createBooking_standardRoom_staysPendingForApproval() {
        givenRequesterAndSave();
        validationChainFindsRoom(buildRoom(RoomType.STANDARD));

        BookingResponse response = bookingService.createBooking(buildRequest(), 1L);

        assertEquals(BookingStatus.PENDING, response.getStatus());
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertEquals(BookingStatus.PENDING, saved.getValue().getStatus());
    }

    @Test
    void createBooking_vipRoom_staysPendingForApproval() {
        givenRequesterAndSave();
        validationChainFindsRoom(buildRoom(RoomType.VIP));

        BookingResponse response = bookingService.createBooking(buildRequest(), 1L);

        assertEquals(BookingStatus.PENDING, response.getStatus());
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertEquals(BookingStatus.PENDING, saved.getValue().getStatus());
    }
}
