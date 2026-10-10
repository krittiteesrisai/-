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
import com.example.roombooking.service.ImageStorageService.Kind;
import com.example.roombooking.service.RoomService;
import com.example.roombooking.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** อัปโหลดหลายรูปและจัดการอัลบั้มจากหน้า admin */
class AdminImageUploadTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

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
        when(rooms.createRoom(any(RoomCreateRequest.class))).thenReturn(room(5L));
        when(rooms.updateRoom(eq(5L), any(RoomCreateRequest.class))).thenReturn(room(5L));
        when(equipment.createEquipment(any(EquipmentCreateRequest.class))).thenReturn(equipment(8L));
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
    void severalUploadedFilesAreAddedInOrderThenCoverIsSynced() throws Exception {
        mvc.perform(roomForm("/admin/rooms").file(file("a.png", PNG)).file(file("b.jpg", JPEG)))
                .andExpect(redirectedUrl("/admin/rooms"));
        InOrder order = inOrder(images);
        order.verify(images).add(Kind.ROOM, 5L, PNG);
        order.verify(images).add(Kind.ROOM, 5L, JPEG);
        order.verify(images).syncCover(Kind.ROOM, 5L, null);
    }

    @Test
    void equipmentUploadsGoToTheEquipmentAlbum() throws Exception {
        mvc.perform(multipart("/admin/equipment").file(file("p.png", PNG))
                        .session(session()).param("_csrf", "test-csrf").param("name", "Projector")
                        .param("category", "AV").param("totalQuantity", "2"))
                .andExpect(redirectedUrl("/admin/equipment"));
        verify(images).add(Kind.EQUIPMENT, 8L, PNG);
        verify(images).syncCover(Kind.EQUIPMENT, 8L, null);
    }

    @Test
    void oneBadFileRejectsTheWholeUploadAndSavesNothing() throws Exception {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes();
        mvc.perform(roomForm("/admin/rooms").file(file("ok.png", PNG)).file(file("x.png", html)))
                .andExpect(view().name("admin/rooms"))
                .andExpect(model().attributeHasFieldErrors("form", "imageFiles"));
        verify(rooms, never()).createRoom(any());
        verify(images, never()).add(any(), anyLong(), any());
    }

    @Test
    void oversizedFileIsRejected() throws Exception {
        byte[] big = new byte[(int) ImageStorageService.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        mvc.perform(roomForm("/admin/rooms").file(file("big.png", big)))
                .andExpect(model().attributeHasFieldErrors("form", "imageFiles"));
        verify(rooms, never()).createRoom(any());
    }

    @Test
    void albumLimitCountsImagesAlreadyStored() throws Exception {
        when(images.imageIds(Kind.ROOM, 5L)).thenReturn(Collections.nCopies(ImageStorageService.MAX_PER_ITEM - 1, 1L));
        mvc.perform(roomForm("/admin/rooms/5").file(file("a.png", PNG)).file(file("b.png", PNG)))
                .andExpect(model().attributeHasFieldErrors("form", "imageFiles"));
        verify(rooms, never()).updateRoom(anyLong(), any());
    }

    @Test
    void savingWithoutFilesKeepsTheAlbumAndUsesTheLinkOnlyAsFallbackCover() throws Exception {
        mvc.perform(roomForm("/admin/rooms/5").param("imageUrl", "https://example.com/b.png"))
                .andExpect(redirectedUrl("/admin/rooms"));
        verify(images, never()).add(any(), anyLong(), any());
        verify(images).syncCover(Kind.ROOM, 5L, "https://example.com/b.png");
    }

    @Test
    void editPageHidesUploadedCoverFromTheLinkFieldAndListsTheAlbum() throws Exception {
        RoomResponse stored = room(5L);
        stored.setImageUrl("/images/42");
        when(rooms.getRoomById(5L)).thenReturn(stored);
        when(images.imageIds(Kind.ROOM, 5L)).thenReturn(List.of(42L, 43L));
        mvc.perform(get("/admin/rooms").param("editId", "5").session(session()))
                .andExpect(model().attribute("galleryIds", List.of(42L, 43L)))
                .andExpect(model().attribute("form", org.hamcrest.Matchers.hasProperty("imageUrl", org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void albumActionsChangeCoverOrDeleteThenResyncCover() throws Exception {
        mvc.perform(post("/admin/rooms/5/images/43/cover").session(session()).param("_csrf", "test-csrf"))
                .andExpect(redirectedUrl("/admin/rooms?editId=5"));
        verify(images).makeCover(Kind.ROOM, 5L, 43L);
        mvc.perform(post("/admin/equipment/8/images/44/delete").session(session()).param("_csrf", "test-csrf"))
                .andExpect(redirectedUrl("/admin/equipment?editId=8"));
        verify(images).delete(Kind.EQUIPMENT, 8L, 44L);
        verify(images).syncCover(Kind.ROOM, 5L, null);
        verify(images).syncCover(Kind.EQUIPMENT, 8L, null);
    }

    @Test
    void deletingARoomAlsoDeletesItsAlbum() throws Exception {
        mvc.perform(post("/admin/rooms/5/delete").session(session()).param("_csrf", "test-csrf"))
                .andExpect(redirectedUrl("/admin/rooms"));
        verify(rooms).deleteRoom(5L);
        verify(images).deleteAll(Kind.ROOM, 5L);
    }

    private MockMultipartFile file(String name, byte[] data) {
        return new MockMultipartFile("imageFiles", name, "image/png", data);
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

    private RoomResponse room(Long id) {
        RoomResponse room = new RoomResponse();
        room.setId(id);
        room.setName("Focus");
        room.setStatus(RoomStatus.AVAILABLE);
        return room;
    }

    private EquipmentResponse equipment(Long id) {
        EquipmentResponse item = new EquipmentResponse();
        item.setId(id);
        item.setName("Projector");
        return item;
    }
}
