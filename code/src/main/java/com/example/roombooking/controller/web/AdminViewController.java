package com.example.roombooking.controller.web;

import com.example.roombooking.controller.web.form.*;
import com.example.roombooking.controller.web.support.WebSessionSupport;
import com.example.roombooking.domain.entity.User;
import com.example.roombooking.domain.enums.*;
import com.example.roombooking.dto.request.*;
import com.example.roombooking.dto.response.*;
import com.example.roombooking.exception.*;
import com.example.roombooking.repository.*;
import com.example.roombooking.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Controller
@Profile("web")
@RequestMapping("/admin")
public class AdminViewController {
    private static final Logger LOG=LoggerFactory.getLogger(AdminViewController.class);
    private final RoomService rooms;
    private final EquipmentService equipment;
    private final BookingService bookings;
    private final UserService users;
    private final UserRepository userRepository;
    private final WebSessionSupport sessions;
    public AdminViewController(RoomService rooms, EquipmentService equipment, BookingService bookings, UserService users,
            UserRepository userRepository, WebSessionSupport sessions) {
        this.rooms=rooms; this.equipment=equipment; this.bookings=bookings; this.users=users;
        this.userRepository=userRepository; this.sessions=sessions;
    }
    @InitBinder("form") public void safeBinding(WebDataBinder binder) {
        binder.setAutoGrowCollectionLimit(100);
        Class<?> type = binder.getTarget() == null ? Object.class : binder.getTarget().getClass();
        if (type == AdminRoomForm.class) binder.setAllowedFields("name","capacity","floor","roomType","status","imageUrl");
        else if (type == AdminEquipmentForm.class) binder.setAllowedFields("name","totalQuantity","category","imageUrl");
        else if (type == AdminUserForm.class) binder.setAllowedFields("username","email","password","role");
        else if (type == AdminBookingForm.class) binder.setAllowedFields("userId","roomId","date","startTime","endTime","purpose","equipmentItems[*].equipmentId","equipmentItems[*].quantity");
    }
    @GetMapping public String overview(HttpServletRequest request, Model model) {
        sessions.requireManager(request);
        List<BookingResponse> all = allBookings();
        model.addAttribute("roomCount", allRooms().size()); model.addAttribute("equipmentCount", allEquipment().size());
        model.addAttribute("userCount", userRepository.count()); model.addAttribute("bookingCount", all.size());
        model.addAttribute("pendingCount", all.stream().filter(b -> b.getStatus()==BookingStatus.PENDING).count());
        model.addAttribute("recentBookings", all.stream().limit(6).toList()); return "admin/overview";
    }
    @GetMapping("/bookings") public String bookingList(@RequestParam(required=false) Long roomId,
            @RequestParam(required=false) Long userId, @RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="0") int page, HttpServletRequest request, Model model) {
        sessions.requireManager(request);
        BookingStatus filter = null;
        if (!status.isBlank()) { try { filter=BookingStatus.valueOf(status); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("สถานะไม่ถูกต้อง"); } }
        final BookingStatus statusFilter=filter;
        List<BookingResponse> source = userId != null ? bookings.getBookingsByUser(userId,Pageable.unpaged()).getContent()
                : roomId != null ? bookings.getBookingsByRoom(roomId,Pageable.unpaged()).getContent() : allBookings();
        List<BookingResponse> filtered = source.stream().filter(b -> statusFilter==null || b.getStatus()==statusFilter)
                .filter(b -> roomId==null || Objects.equals(b.getRoomId(),roomId))
                .sorted(Comparator.comparing(BookingResponse::getId).reversed()).toList();
        model.addAttribute("bookings", CatalogViewController.paginate(filtered,page,12,model));
        model.addAttribute("rooms",allRooms()); model.addAttribute("users",userRepository.findAll());
        model.addAttribute("roomId",roomId); model.addAttribute("userId",userId); model.addAttribute("status",status); return "admin/bookings";
    }
    @GetMapping("/bookings/{id}") public String bookingDetail(@PathVariable Long id, HttpServletRequest request, Model model) {
        sessions.requireManager(request); model.addAttribute("booking",bookings.getBookingById(id)); return "admin/booking-detail";
    }
    @PostMapping("/bookings/{id}/status") public String updateStatus(@PathVariable Long id, @RequestParam BookingStatus status,
            HttpServletRequest request, RedirectAttributes redirect) {
        User actor=sessions.requireManager(request);
        if (status==BookingStatus.PENDING) throw new IllegalArgumentException("ไม่สามารถเปลี่ยนกลับเป็นรออนุมัติได้");
        try { bookings.updateStatus(id,status,actor.getId()); redirect.addFlashAttribute("flashSuccess","อัปเดตสถานะการจองแล้ว"); }
        catch (RuntimeException ex) { redirect.addFlashAttribute("flashError", actionError(ex)); }
        return "redirect:/admin/bookings/"+id;
    }
    @GetMapping("/bookings/new") public String newBooking(HttpServletRequest request, Model model) {
        sessions.requireManager(request);
        AdminBookingForm form=new AdminBookingForm(); form.setDate(LocalDate.now(ZoneId.of("Asia/Bangkok")));
        for (EquipmentResponse item:allEquipment()) { AdminBookingForm.Item row=new AdminBookingForm.Item(); row.setEquipmentId(item.getId()); form.getEquipmentItems().add(row); }
        model.addAttribute("form",form); bookingFormModel(model); return "admin/booking-form";
    }
    @PostMapping("/bookings/new") public String createBooking(@Valid @ModelAttribute("form") AdminBookingForm form,
            BindingResult errors, HttpServletRequest request, Model model, RedirectAttributes redirect) {
        User actor=sessions.requireManager(request);
        if (!errors.hasFieldErrors("endTime") && form.getStartTime()!=null && form.getEndTime()!=null && !form.getEndTime().isAfter(form.getStartTime()))
            errors.rejectValue("endTime","order","เวลาสิ้นสุดต้องอยู่หลังเวลาเริ่มต้น");
        if (!errors.hasFieldErrors("userId") && form.getUserId()!=null) {
            User recipient=userRepository.findById(form.getUserId()).orElse(null);
            if(recipient==null || !Boolean.TRUE.equals(recipient.getActive())) errors.rejectValue("userId","inactive","ผู้ใช้ไม่มีอยู่หรือถูกปิดใช้งานแล้ว");
        }
        if (!errors.hasErrors()) {
            BookingCreateRequest payload=new BookingCreateRequest(); payload.setRoomId(form.getRoomId()); payload.setBookingForUserId(form.getUserId());
            payload.setStartTime(form.getDate().atTime(form.getStartTime())); payload.setEndTime(form.getDate().atTime(form.getEndTime())); payload.setPurpose(form.getPurpose());
            payload.setEquipmentItems(form.getEquipmentItems().stream().filter(i -> i.getQuantity()>0)
                    .map(i -> new BookingCreateRequest.EquipmentItemRequest(i.getEquipmentId(),i.getQuantity())).toList());
            try { BookingResponse saved=bookings.createBooking(payload,actor.getId()); redirect.addFlashAttribute("flashSuccess","ส่งคำขอจองแทนผู้ใช้แล้ว"); return "redirect:/admin/bookings/"+saved.getId(); }
            catch (RuntimeException ex) { errors.reject("booking",actionError(ex)); }
        }
        bookingFormModel(model); return "admin/booking-form";
    }
    @GetMapping("/rooms") public String roomList(@RequestParam(required=false) Long editId, HttpServletRequest request, Model model) {
        sessions.requireManager(request); AdminRoomForm form=new AdminRoomForm();
        if(editId!=null) { RoomResponse r=rooms.getRoomById(editId); form.setName(r.getName()); form.setCapacity(r.getCapacity()); form.setFloor(r.getFloor()); form.setRoomType(r.getRoomType()); form.setStatus(r.getStatus()); form.setImageUrl(r.getImageUrl()); }
        model.addAttribute("form",form); roomModel(model,editId); return "admin/rooms";
    }
    @PostMapping("/rooms") public String createRoom(@Valid @ModelAttribute("form") AdminRoomForm form, BindingResult errors,
            HttpServletRequest request, Model model, RedirectAttributes redirect) { return saveRoom(null,form,errors,request,model,redirect); }
    @PostMapping("/rooms/{id}") public String updateRoom(@PathVariable Long id,@Valid @ModelAttribute("form") AdminRoomForm form, BindingResult errors,
            HttpServletRequest request, Model model, RedirectAttributes redirect) { return saveRoom(id,form,errors,request,model,redirect); }
    private String saveRoom(Long id, AdminRoomForm form, BindingResult errors,HttpServletRequest request, Model model, RedirectAttributes redirect) {
        sessions.requireManager(request);
        if(!errors.hasErrors()) {
            RoomCreateRequest payload=new RoomCreateRequest(); payload.setName(form.getName().strip()); payload.setCapacity(form.getCapacity()); payload.setFloor(form.getFloor().strip()); payload.setRoomType(form.getRoomType()); payload.setStatus(form.getStatus()); payload.setImageUrl(form.getImageUrl());
            try { if(id==null) rooms.createRoom(payload); else rooms.updateRoom(id,payload); redirect.addFlashAttribute("flashSuccess","บันทึกข้อมูลห้องแล้ว"); return "redirect:/admin/rooms"; }
            catch(RuntimeException ex) { errors.reject("room",actionError(ex)); }
        }
        roomModel(model,id); return "admin/rooms";
    }
    @PostMapping("/rooms/{id}/delete") public String deleteRoom(@PathVariable Long id,HttpServletRequest request,RedirectAttributes redirect) {
        sessions.requireManager(request);
        try { rooms.deleteRoom(id); redirect.addFlashAttribute("flashSuccess","ลบห้องแล้ว"); }
        catch(RuntimeException ex) { redirect.addFlashAttribute("flashError",actionError(ex)); }
        return "redirect:/admin/rooms";
    }
    @GetMapping("/equipment") public String equipmentList(@RequestParam(required=false) Long editId,HttpServletRequest request,Model model) {
        sessions.requireManager(request); AdminEquipmentForm form=new AdminEquipmentForm();
        if(editId!=null) { EquipmentResponse e=equipment.getEquipmentById(editId); form.setName(e.getName()); form.setTotalQuantity(e.getTotalQuantity()); form.setCategory(e.getCategory()); form.setImageUrl(e.getImageUrl()); }
        model.addAttribute("form",form); equipmentModel(model,editId); return "admin/equipment";
    }
    @PostMapping("/equipment") public String createEquipment(@Valid @ModelAttribute("form") AdminEquipmentForm form,BindingResult errors,
            HttpServletRequest request,Model model,RedirectAttributes redirect) { return saveEquipment(null,form,errors,request,model,redirect); }
    @PostMapping("/equipment/{id}") public String updateEquipment(@PathVariable Long id,@Valid @ModelAttribute("form") AdminEquipmentForm form,BindingResult errors,
            HttpServletRequest request,Model model,RedirectAttributes redirect) { return saveEquipment(id,form,errors,request,model,redirect); }
    private String saveEquipment(Long id,AdminEquipmentForm form,BindingResult errors,HttpServletRequest request,Model model,RedirectAttributes redirect) {
        sessions.requireManager(request);
        if(!errors.hasErrors()) {
            EquipmentCreateRequest payload=new EquipmentCreateRequest(); payload.setName(form.getName().strip()); payload.setCategory(form.getCategory().strip()); payload.setTotalQuantity(form.getTotalQuantity()); payload.setImageUrl(form.getImageUrl());
            try { if(id==null) equipment.createEquipment(payload); else equipment.updateEquipment(id,payload); redirect.addFlashAttribute("flashSuccess","บันทึกข้อมูลอุปกรณ์แล้ว"); return "redirect:/admin/equipment"; }
            catch(RuntimeException ex) { errors.reject("equipment",actionError(ex)); }
        }
        equipmentModel(model,id); return "admin/equipment";
    }
    @PostMapping("/equipment/{id}/delete") public String deleteEquipment(@PathVariable Long id,HttpServletRequest request,RedirectAttributes redirect) {
        sessions.requireManager(request);
        try { equipment.deleteEquipment(id); redirect.addFlashAttribute("flashSuccess","ลบอุปกรณ์แล้ว"); }
        catch(RuntimeException ex) { redirect.addFlashAttribute("flashError",actionError(ex)); }
        return "redirect:/admin/equipment";
    }
    @GetMapping("/users") public String userList(HttpServletRequest request,Model model) {
        sessions.requireAdmin(request); model.addAttribute("form",new AdminUserForm()); userModel(model); return "admin/users";
    }
    @PostMapping("/users") public synchronized String createUser(@Valid @ModelAttribute("form") AdminUserForm form,BindingResult errors,
            HttpServletRequest request,Model model,RedirectAttributes redirect) {
        sessions.requireAdmin(request);
        if(!errors.hasErrors()) {
            String name=form.getUsername().strip(), email=form.getEmail().strip().toLowerCase(Locale.ROOT);
            if(name.length()<4) errors.rejectValue("username","length","ชื่อผู้ใช้ต้องมีอย่างน้อย 4 ตัวอักษร");
            List<User> existing=userRepository.findAll();
            if(existing.stream().anyMatch(u -> name.equalsIgnoreCase(u.getUsername()))) errors.rejectValue("username","duplicate","ชื่อผู้ใช้นี้มีแล้ว");
            if(existing.stream().anyMatch(u -> email.equalsIgnoreCase(u.getEmail()))) errors.rejectValue("email","duplicate","อีเมลนี้มีแล้ว");
            if(form.getPassword().getBytes(StandardCharsets.UTF_8).length>72) errors.rejectValue("password","length","รหัสผ่านต้องไม่เกิน 72 ไบต์");
            if(!errors.hasErrors()) {
                UserCreateRequest payload=new UserCreateRequest(); payload.setUsername(name); payload.setEmail(email);
                payload.setPassword(form.getPassword()); payload.setRole(form.getRole());
                users.createUser(payload); redirect.addFlashAttribute("flashSuccess","สร้างผู้ใช้แล้ว"); return "redirect:/admin/users";
            }
        }
        userModel(model); return "admin/users";
    }
    @GetMapping("/users/{id}") public String userDetail(@PathVariable Long id,HttpServletRequest request,Model model) {
        sessions.requireAdmin(request); model.addAttribute("userDetail",userEntity(id));
        model.addAttribute("bookings",bookings.getBookingsByUser(id,Pageable.unpaged()).getContent()); return "admin/user-detail";
    }
    @PostMapping("/users/{id}/delete") public String deleteUser(@PathVariable Long id,HttpServletRequest request,RedirectAttributes redirect) {
        User actor=sessions.requireAdmin(request); User target=userEntity(id);
        if(actor.getId().equals(id)) redirect.addFlashAttribute("flashError","ไม่สามารถลบบัญชีที่กำลังใช้งานอยู่ได้");
        else if(target.getRole()==Role.ADMIN && userRepository.findAll().stream().filter(u -> u.getRole()==Role.ADMIN && Boolean.TRUE.equals(u.getActive())).count()<=1)
            redirect.addFlashAttribute("flashError","ต้องมีผู้ดูแลระบบที่ใช้งานได้อย่างน้อยหนึ่งบัญชี");
        else { try { users.deleteUserById(id); redirect.addFlashAttribute("flashSuccess","ลบผู้ใช้แล้ว"); } catch(RuntimeException ex) { redirect.addFlashAttribute("flashError",actionError(ex)); } }
        return "redirect:/admin/users";
    }
    private List<RoomResponse> allRooms() { return rooms.getAllRooms(Pageable.unpaged()).getContent(); }
    private List<EquipmentResponse> allEquipment() { return equipment.getAllEquipments(Pageable.unpaged()).getContent(); }
    private List<BookingResponse> allBookings() {
        return allRooms().stream().flatMap(r -> bookings.getBookingsByRoom(r.getId(),Pageable.unpaged()).getContent().stream())
                .sorted(Comparator.comparing(BookingResponse::getId).reversed()).toList();
    }
    private void roomModel(Model model,Long editId) { model.addAttribute("rooms",allRooms()); model.addAttribute("editId",editId); }
    private void equipmentModel(Model model,Long editId) { model.addAttribute("equipmentList",allEquipment()); model.addAttribute("editId",editId); }
    private void userModel(Model model) { model.addAttribute("users",userRepository.findAll()); }
    private User userEntity(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("ไม่พบผู้ใช้"));
    }
    private void bookingFormModel(Model model) {
        model.addAttribute("rooms",allRooms().stream().filter(r -> r.getStatus()==RoomStatus.AVAILABLE).toList());
        model.addAttribute("users",userRepository.findAll().stream().filter(u -> Boolean.TRUE.equals(u.getActive())).toList()); model.addAttribute("equipmentList",allEquipment());
    }
    private static String actionError(RuntimeException ex) {
        if(ex instanceof DataIntegrityViolationException) return "ลบหรือแก้ไขรายการไม่ได้ เพราะมีข้อมูลการจองอ้างอิงอยู่";
        if(ex instanceof RoomNotAvailableException || ex instanceof EquipmentNotAvailableException || ex instanceof ConflictException
                || ex instanceof InvalidStateTransitionException || ex instanceof ForbiddenException || ex instanceof ResourceNotFoundException
                || ex instanceof IllegalArgumentException) {
            return ex.getMessage()==null ? "กรุณาตรวจข้อมูลแล้วลองใหม่" : ex.getMessage();
        }
        LOG.error("Web admin action failed",ex);
        return "ดำเนินการไม่สำเร็จ กรุณาตรวจข้อมูลแล้วลองใหม่";
    }
}
