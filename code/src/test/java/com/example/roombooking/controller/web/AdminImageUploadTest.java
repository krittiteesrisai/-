package com.example.roombooking.controller.web;

import com.example.roombooking.controller.web.support.WebAccessInterceptor;
import com.example.roombooking.controller.web.support.WebSessionSupport;
import com.example.roombooking.controller.web.support.WebViewAdvice;
import com.example.roombooking.domain.entity.User;
import com.example.roombooking.domain.enums.Role;
import com.example.roombooking.domain.enums.RoomStatus;
import com.example.roombooking.dto.request.EquipmentCreateRequest;
import com.example.roombooking.dto.request.RoomCreateRequest;
import com.example.roombooking.dto.response.EquipmentResponse;
import com.example.roombooking.dto.response.RoomResponse;
import com.example.roombooking.repository.UserRepository;
import com.example.roombooking.service.BookingService;
import com.example.roombooking.service.EquipmentService;
import com.example.roombooking.service.ImageStorageService;
import com.example.roombooking.service.RoomService;
import com.example.roombooking.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** อัปโหลดรูปห้อง/อุปกรณ์จากฟอร์ม admin */
class AdminImageUploadTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    private RoomService rooms;
    private EquipmentService equipment;
    private ImageStorageService images;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        rooms = mock(RoomService.class);
        equipment = mock(EquipmentService.class);
        images = mock(ImageStorageService.class);
        UserRepository userRepository = mock(UserRepository.class);
        User admin = new User();
        admin.setId(1L);
        admin.setRole(Role.ADMIN);
        admin.setUsername("admin");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(rooms.getAllRooms(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(equipment.getAllEquipments(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(rooms.createRoom(any(RoomCreateRequest.class))).thenReturn(room(5L, null));
        when(equipment.createEquipment(any(EquipmentCreateRequest.class))).thenReturn(equipment(8L, null));
        WebSessionSupport sessions = new WebSessionSupport(userRepository);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new AdminViewController(rooms, equipment, mock(BookingService.class),
                        mock(UserService.class), userRepository, sessions, images))
                .setControllerAdvice(new WebViewAdvice(sessions)).setValidator(validator)
                .addInterceptors(new WebAccessInterceptor(sessions)).build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void uploadedRoomImageIsStoredForTheCreatedRoom() throws Exception {
        mvc.perform(roomForm("/admin/rooms").file(new MockMultipartFile("imageFile", "room.png", "image/png", PNG)))
                .andExpect(redirectedUrl("/admin/rooms"));
        verify(images).save(ImageStorageService.Kind.ROOMS, 5L, PNG);
    }

    @Test
    void uploadedEquipmentImageIsStoredForTheCreatedEquipment() throws Exception {
        mvc.perform(multipart("/admin/equipment").file(new MockMultipartFile("imageFile", "p.png", "image/png", PNG))
                        .session(session()).param("_csrf", "test-csrf").param("name", "Projector")
                        .param("category", "AV").param("totalQuantity", "2"))
                .andExpect(redirectedUrl("/admin/equipment"));
        verify(images).save(ImageStorageService.Kind.EQUIPMENT, 8L, PNG);
    }

    @Test
    void nonImageFileIsRejectedEvenWithImageContentType() throws Exception {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes();
        mvc.perform(roomForm("/admin/rooms").file(new MockMultipartFile("imageFile", "x.png", "image/png", html)))
                .andExpect(view().name("admin/rooms"))
                .andExpect(model().attributeHasFieldErrors("form", "imageFile"));
        verify(rooms, never()).createRoom(any());
        verifyNoInteractions(images);
    }

    @Test
    void oversizedFileIsRejected() throws Exception {
        byte[] big = new byte[(int) ImageStorageService.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        mvc.perform(roomForm("/admin/rooms").file(new MockMultipartFile("imageFile", "big.png", "image/png", big)))
                .andExpect(model().attributeHasFieldErrors("form", "imageFile"));
        verify(rooms, never()).createRoom(any());
    }

    @Test
    void removingTheLinkClearsAPreviouslyUploadedImage() throws Exception {
        when(rooms.getRoomById(5L)).thenReturn(room(5L, "/images/rooms/5?v=1"));
        when(rooms.updateRoom(eq(5L), any(RoomCreateRequest.class))).thenReturn(room(5L, null));
        mvc.perform(roomForm("/admin/rooms/5").param("imageUrl", ""))
                .andExpect(redirectedUrl("/admin/rooms"));
        verify(images).clearUnlessStored(ImageStorageService.Kind.ROOMS, 5L, null);
    }

    @Test
    void savingWithoutUploadsNeverTouchesImageColumns() throws Exception {
        when(rooms.getRoomById(5L)).thenReturn(room(5L, "https://example.com/a.png"));
        when(rooms.updateRoom(eq(5L), any(RoomCreateRequest.class))).thenReturn(room(5L, "https://example.com/b.png"));
        mvc.perform(roomForm("/admin/rooms/5").param("imageUrl", "https://example.com/b.png"))
                .andExpect(redirectedUrl("/admin/rooms"));
        verify(images, never()).save(any(), anyLong(), any());
        verify(images, never()).clearUnlessStored(any(), anyLong(), any());
    }

    private MockMultipartHttpServletRequestBuilder roomForm(String url) {
        MockMultipartHttpServletRequestBuilder request = multipart(url);
        request.session(session()).param("_csrf", "test-csrf").param("name", "Focus").param("capacity", "4")
                .param("floor", "2").param("roomType", "STANDARD").param("status", "AVAILABLE");
        return request;
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(WebSessionSupport.USER_ID_SESSION_KEY, 1L);
        session.setAttribute(WebSessionSupport.CSRF_SESSION_KEY, "test-csrf");
        return session;
    }

    private RoomResponse room(Long id, String imageUrl) {
        RoomResponse room = new RoomResponse();
        room.setId(id);
        room.setName("Focus");
        room.setStatus(RoomStatus.AVAILABLE);
        room.setImageUrl(imageUrl);
        return room;
    }

    private EquipmentResponse equipment(Long id, String imageUrl) {
        EquipmentResponse item = new EquipmentResponse();
        item.setId(id);
        item.setName("Projector");
        item.setImageUrl(imageUrl);
        return item;
    }
}
