package com.example.roombooking.controller.web;

import com.example.roombooking.controller.web.support.WebAccessInterceptor;
import com.example.roombooking.controller.web.support.WebSessionSupport;
import com.example.roombooking.controller.web.support.WebViewAdvice;
import com.example.roombooking.domain.entity.User;
import com.example.roombooking.domain.enums.*;
import com.example.roombooking.dto.request.BookingCreateRequest;
import com.example.roombooking.dto.request.UserCreateRequest;
import com.example.roombooking.dto.response.*;
import com.example.roombooking.repository.UserRepository;
import com.example.roombooking.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminViewControllerTest {
    private BookingService bookings;
    private RoomService rooms;
    private EquipmentService equipment;
    private UserService users;
    private UserRepository userRepository;
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;
    private User admin;
    private User recipient;

    @BeforeEach
    void setUp() {
        bookings = mock(BookingService.class);
        rooms = mock(RoomService.class);
        equipment = mock(EquipmentService.class);
        users = mock(UserService.class);
        userRepository = mock(UserRepository.class);
        admin = user(1L, Role.ADMIN);
        recipient = user(4L, Role.USER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, Role.STAFF)));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user(3L, Role.USER)));
        when(userRepository.findById(4L)).thenReturn(Optional.of(recipient));
        when(userRepository.findAll()).thenReturn(List.of(admin, recipient));
        when(rooms.getAllRooms(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(room())));
        EquipmentResponse projector = new EquipmentResponse();
        projector.setId(7L);
        projector.setName("Projector");
        projector.setTotalQuantity(5);
        when(equipment.getAllEquipments(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(projector)));
        WebSessionSupport sessions = new WebSessionSupport(userRepository);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new AdminViewController(rooms, equipment, bookings, users, userRepository, sessions,
                        mock(ImageStorageService.class)))
                .setControllerAdvice(new WebViewAdvice(sessions)).setValidator(validator)
                .addInterceptors(new WebAccessInterceptor(sessions)).build();
    }

    @AfterEach
    void tearDown() { validator.close(); }

    @Test
    void ordinaryUserCannotMutateAdminResourcesOrBookingState() throws Exception {
        for (String path : List.of("/admin/rooms/2/delete", "/admin/equipment/7/delete", "/admin/bookings/42/status")) {
            mvc.perform(post(path).session(session(3L)).param("_csrf", "test-csrf").param("status", "APPROVED"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(rooms, equipment, bookings, users);
    }

    @Test
    void staffCannotCreateOrDeleteUsers() throws Exception {
        mvc.perform(get("/admin/users").session(session(2L))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users").session(session(2L)).param("_csrf", "test-csrf")
                        .param("username", "newuser").param("email", "new@example.com").param("password", "local123").param("role", "ADMIN"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/4/delete").session(session(2L)).param("_csrf", "test-csrf"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users);
    }

    @Test
    void managerMutationRequiresCsrfAndUsesSessionActor() throws Exception {
        mvc.perform(post("/admin/bookings/42/status").session(session(1L)).param("status", "APPROVED"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(bookings);
        mvc.perform(post("/admin/bookings/42/status").session(session(1L)).param("_csrf", "test-csrf")
                        .param("status", "APPROVED").param("actorId", "999"))
                .andExpect(redirectedUrl("/admin/bookings/42"));
        verify(bookings).updateStatus(42L, BookingStatus.APPROVED, 1L);
    }

    @Test
    void cannotReturnBookingToPending() throws Exception {
        mvc.perform(post("/admin/bookings/42/status").session(session(1L)).param("_csrf", "test-csrf").param("status", "PENDING"))
                .andExpect(status().isBadRequest()).andExpect(view().name("common/error"));
        verifyNoInteractions(bookings);
    }

    @Test
    void onBehalfBookingBindsSelectedRecipientAndEquipmentWithoutSpoofingActor() throws Exception {
        when(bookings.createBooking(any(), eq(1L))).thenReturn(booking(41L, 2L, BookingStatus.PENDING));
        mvc.perform(bookingRequest().param("equipmentItems[0].equipmentId", "7").param("equipmentItems[0].quantity", "2")
                        .param("equipmentItems[1].equipmentId", "8").param("equipmentItems[1].quantity", "0")
                        .param("actorId", "999").param("bookingId", "999"))
                .andExpect(redirectedUrl("/admin/bookings/41"));
        ArgumentCaptor<BookingCreateRequest> payload = ArgumentCaptor.forClass(BookingCreateRequest.class);
        verify(bookings).createBooking(payload.capture(), eq(1L));
        assertEquals(4L, payload.getValue().getBookingForUserId());
        assertEquals(2L, payload.getValue().getRoomId());
        assertNull(payload.getValue().getBookingId());
        assertEquals(LocalDateTime.of(2027, 1, 12, 9, 0), payload.getValue().getStartTime());
        assertEquals(1, payload.getValue().getEquipmentItems().size());
        assertEquals(7L, payload.getValue().getEquipmentItems().get(0).getEquipmentId());
        assertEquals(2, payload.getValue().getEquipmentItems().get(0).getQuantity());
    }

    @Test
    void oversizedPurposeAndInactiveRecipientDoNotCreateBooking() throws Exception {
        recipient.setActive(false);
        mvc.perform(bookingRequest().param("purpose", "x".repeat(256)))
                .andExpect(view().name("admin/booking-form"))
                .andExpect(model().attributeHasFieldErrors("form", "purpose", "userId"));
        verify(bookings, never()).createBooking(any(), anyLong());
    }

    @Test
    void combinedUserRoomAndStatusFiltersAllApply() throws Exception {
        BookingResponse match = booking(41L, 2L, BookingStatus.PENDING);
        when(bookings.getBookingsByUser(eq(4L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(match, booking(42L, 3L, BookingStatus.PENDING), booking(43L, 2L, BookingStatus.APPROVED))));
        mvc.perform(get("/admin/bookings").session(session(1L)).param("userId", "4").param("roomId", "2").param("status", "PENDING"))
                .andExpect(view().name("admin/bookings")).andExpect(model().attribute("bookings", List.of(match)));
    }

    @Test
    void administratorCannotDeleteOwnAccount() throws Exception {
        mvc.perform(post("/admin/users/1/delete").session(session(1L)).param("_csrf", "test-csrf"))
                .andExpect(redirectedUrl("/admin/users")).andExpect(flash().attributeExists("flashError"));
        verify(users, never()).deleteUserById(anyLong());
    }

    @Test
    void adminUserCreationPassesRawPasswordAndNormalizedDtoToService() throws Exception {
        mvc.perform(post("/admin/users").session(session(1L)).param("_csrf", "test-csrf")
                        .param("username", " newuser ").param("email", "NEW@EXAMPLE.COM")
                        .param("password", "local123").param("role", "STAFF"))
                .andExpect(redirectedUrl("/admin/users"));
        ArgumentCaptor<UserCreateRequest> payload = ArgumentCaptor.forClass(UserCreateRequest.class);
        verify(users).createUser(payload.capture());
        assertEquals("newuser", payload.getValue().getUsername());
        assertEquals("new@example.com", payload.getValue().getEmail());
        assertEquals("local123", payload.getValue().getPassword());
        assertEquals(Role.STAFF, payload.getValue().getRole());
    }

    @Test
    void unexpectedFailureDoesNotExposeInternalMessageToUser() throws Exception {
        doThrow(new RuntimeException("internal-database-error")).when(rooms).deleteRoom(2L);
        mvc.perform(post("/admin/rooms/2/delete").session(session(1L)).param("_csrf", "test-csrf"))
                .andExpect(redirectedUrl("/admin/rooms"))
                .andExpect(flash().attribute("flashError", not(containsString("internal-database-error"))));
    }

    private MockHttpServletRequestBuilder bookingRequest() {
        return post("/admin/bookings/new").session(session(1L)).param("_csrf", "test-csrf")
                .param("userId", "4").param("roomId", "2").param("date", "2027-01-12")
                .param("startTime", "09:00").param("endTime", "11:00");
    }

    private MockHttpSession session(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(WebSessionSupport.USER_ID_SESSION_KEY, userId);
        session.setAttribute(WebSessionSupport.CSRF_SESSION_KEY, "test-csrf");
        return session;
    }

    private User user(Long id, Role role) {
        User user = new User(); user.setId(id); user.setRole(role); user.setUsername("user" + id); return user;
    }

    private RoomResponse room() {
        RoomResponse room = new RoomResponse(); room.setId(2L); room.setName("Focus"); room.setStatus(RoomStatus.AVAILABLE); return room;
    }

    private BookingResponse booking(Long id, Long roomId, BookingStatus status) {
        return new BookingResponse(id, roomId, "Focus", 4L, "user4", LocalDateTime.of(2027, 1, 12, 9, 0),
                LocalDateTime.of(2027, 1, 12, 11, 0), status, null, List.of(), LocalDateTime.of(2026, 10, 6, 13, 0));
    }
}
