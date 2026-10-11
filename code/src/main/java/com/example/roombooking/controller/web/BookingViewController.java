package com.example.roombooking.controller.web;

import com.example.roombooking.controller.web.form.BookingDetailsForm;
import com.example.roombooking.controller.web.form.BookingEditForm;
import com.example.roombooking.controller.web.form.BookingEquipmentForm;
import com.example.roombooking.controller.web.support.WebAccessDeniedException;
import com.example.roombooking.controller.web.support.WebSessionSupport;
import com.example.roombooking.domain.entity.User;
import com.example.roombooking.domain.enums.BookingStatus;
import com.example.roombooking.domain.enums.RoomStatus;
import com.example.roombooking.dto.request.BookingCreateRequest;
import com.example.roombooking.dto.response.BookingResponse;
import com.example.roombooking.dto.response.EquipmentResponse;
import com.example.roombooking.dto.response.RoomResponse;
import com.example.roombooking.exception.EquipmentNotAvailableException;
import com.example.roombooking.exception.ForbiddenException;
import com.example.roombooking.exception.InvalidStateTransitionException;
import com.example.roombooking.exception.ResourceNotFoundException;
import com.example.roombooking.exception.RoomNotAvailableException;
import com.example.roombooking.service.BookingService;
import com.example.roombooking.service.EquipmentService;
import com.example.roombooking.service.RoomService;
import com.example.roombooking.service.validation.PastBookingTimeHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@Profile("web")
@RequestMapping("/bookings")
public class BookingViewController {
    private static final String DRAFT_KEY = "bookingWizardDraft";
    private static final String LAST_SUBMISSION_KEY = "lastSubmittedBookingId";
    private static final int PAGE_SIZE = 10;
    private static final int LOOKUP_PAGE_SIZE = 100;
    private static final DateTimeFormatter THAI_DATE = DateTimeFormatter.ofPattern("dd MMM", Locale.forLanguageTag("th"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final BookingService bookingService;
    private final RoomService roomService;
    private final EquipmentService equipmentService;
    private final WebSessionSupport sessions;

    public BookingViewController(BookingService bookingService, RoomService roomService,
                                 EquipmentService equipmentService, WebSessionSupport sessions) {
        this.bookingService = bookingService;
        this.roomService = roomService;
        this.equipmentService = equipmentService;
        this.sessions = sessions;
    }

    @InitBinder
    void bookingFields(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
        switch (binder.getObjectName()) {
            case "detailsForm" -> binder.setAllowedFields("roomId", "date", "endDate", "startTime", "endTime", "purpose");
            case "equipmentForm" -> binder.setAllowedFields("quantities[*]");
            case "editForm" -> binder.setAllowedFields("roomId", "date", "endDate", "startTime", "endTime", "purpose", "quantities[*]");
            default -> { }
        }
    }

    @ModelAttribute("statusLabels")
    Map<BookingStatus, String> statusLabels() {
        return Map.of(BookingStatus.PENDING, "รออนุมัติ", BookingStatus.APPROVED, "อนุมัติแล้ว",
                BookingStatus.REJECTED, "ไม่อนุมัติ", BookingStatus.CANCELLED, "ยกเลิกแล้ว",
                BookingStatus.COMPLETED, "ใช้งานเสร็จสิ้น");
    }

    @GetMapping
    public String list(HttpServletRequest request, Model model,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) BookingStatus status,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        User actor = sessions.requireUser(request);
        PageRequest paging = PageRequest.of(Math.max(0, page), PAGE_SIZE, Sort.by("startTime").descending());
        Page<BookingResponse> result;
        if (from != null && to != null && to.isBefore(from)) {
            model.addAttribute("flashError", "วันที่สิ้นสุดต้องไม่อยู่ก่อนวันที่เริ่มต้น");
            result = Page.empty(paging);
        } else if (status == null && from == null && to == null) {
            result = bookingService.getBookingsByUser(actor.getId(), paging);
            if (result.getTotalPages() > 0 && page >= result.getTotalPages()) {
                result = bookingService.getBookingsByUser(actor.getId(), PageRequest.of(result.getTotalPages() - 1,
                        PAGE_SIZE, Sort.by("startTime").descending()));
            }
        } else {
            List<BookingResponse> filtered = allUserBookings(actor.getId()).stream()
                    .filter(item -> status == null || item.getStatus() == status)
                    .filter(item -> from == null || item.getEndTime().isAfter(from.atStartOfDay()))
                    .filter(item -> to == null || !item.getStartTime().toLocalDate().isAfter(to))
                    .toList();
            int lastPage = Math.max(0, (filtered.size() - 1) / PAGE_SIZE);
            paging = PageRequest.of(Math.min(paging.getPageNumber(), lastPage), PAGE_SIZE);
            int first = Math.min((int) paging.getOffset(), filtered.size());
            result = new PageImpl<>(filtered.subList(first, Math.min(first + PAGE_SIZE, filtered.size())), paging, filtered.size());
        }
        model.addAttribute("bookingPage", result);
        model.addAttribute("bookings", result.getContent());
        model.addAttribute("status", status);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("dateLabels", result.getContent().stream().collect(Collectors.toMap(BookingResponse::getId, this::dateRange)));
        model.addAttribute("timeLabels", result.getContent().stream().collect(Collectors.toMap(BookingResponse::getId, this::timeRange)));
        return "bookings/list";
    }

    @GetMapping("/new")
    public String details(HttpServletRequest request, Model model, @RequestParam(required = false) Long roomId,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime startTime,
                          @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime endTime) {
        User actor = sessions.requireUser(request);
        BookingDraft draft = draft(request, actor);
        BookingDetailsForm form = draft == null ? new BookingDetailsForm() : draft.details().copy();
        if (draft == null) {
            form.setDate(LocalDate.now(ZoneId.of("Asia/Bangkok")).plusDays(1));
            form.setStartTime(LocalTime.of(9, 0));
            form.setEndTime(LocalTime.of(11, 0));
        }
        if (roomId != null) {
            roomService.getRoomById(roomId);
            form.setRoomId(roomId);
        }
        if (date != null) {
            form.setDate(date);
            form.setEndDate(null);
        }
        if (startTime != null) form.setStartTime(startTime);
        if (endTime != null) form.setEndTime(endTime);
        model.addAttribute("detailsForm", form);
        addRooms(model);
        return "bookings/details";
    }

    @PostMapping("/new/details")
    public String saveDetails(HttpServletRequest request, @Valid @ModelAttribute("detailsForm") BookingDetailsForm form,
                              BindingResult errors, Model model) {
        User actor = sessions.requireUser(request);
        validateDetails(form, errors);
        if (errors.hasErrors()) {
            addErrors(model, errors);
            addRooms(model);
            return "bookings/details";
        }
        BookingDraft previous = draft(request, actor);
        request.getSession().setAttribute(DRAFT_KEY, new BookingDraft(actor.getId(), form.copy(),
                previous == null ? Map.of() : previous.quantities(), false));
        request.getSession().removeAttribute(LAST_SUBMISSION_KEY);
        return "redirect:/bookings/new/equipment";
    }

    @GetMapping("/new/equipment")
    public String equipment(HttpServletRequest request, Model model, RedirectAttributes flash) {
        User actor = sessions.requireUser(request);
        BookingDraft draft = draft(request, actor);
        if (draft == null) return missingDraft(flash);
        BookingEquipmentForm form = new BookingEquipmentForm();
        form.setQuantities(new LinkedHashMap<>(draft.quantities()));
        model.addAttribute("equipmentForm", form);
        addEquipment(model);
        addSummary(model, draft.details());
        return "bookings/equipment";
    }

    @PostMapping("/new/equipment")
    public String saveEquipment(HttpServletRequest request, @ModelAttribute("equipmentForm") BookingEquipmentForm form,
                                BindingResult errors, Model model, RedirectAttributes flash) {
        User actor = sessions.requireUser(request);
        BookingDraft draft = draft(request, actor);
        if (draft == null) return missingDraft(flash);
        List<EquipmentResponse> equipment = addEquipment(model);
        Map<Long, Integer> selected = validateQuantities(form.getQuantities(), equipment, errors);
        if (errors.hasErrors()) {
            addErrors(model, errors);
            addSummary(model, draft.details());
            return "bookings/equipment";
        }
        request.getSession().setAttribute(DRAFT_KEY, new BookingDraft(actor.getId(), draft.details(), selected, true));
        return "redirect:/bookings/new/review";
    }

    @GetMapping("/new/review")
    public String review(HttpServletRequest request, Model model, RedirectAttributes flash) {
        User actor = sessions.requireUser(request);
        BookingDraft draft = draft(request, actor);
        if (draft == null) return missingDraft(flash);
        if (!draft.equipmentChosen()) return "redirect:/bookings/new/equipment";
        addSummary(model, draft.details());
        List<EquipmentResponse> equipment = addEquipment(model);
        Map<Long, EquipmentResponse> known = equipment.stream().collect(Collectors.toMap(EquipmentResponse::getId, Function.identity()));
        if (!known.keySet().containsAll(draft.quantities().keySet())) {
            flash.addFlashAttribute("flashError", "รายการอุปกรณ์มีการเปลี่ยนแปลง กรุณาเลือกอุปกรณ์อีกครั้ง");
            return "redirect:/bookings/new/equipment";
        }
        model.addAttribute("detailsForm", draft.details());
        model.addAttribute("selectedEquipment", equipment.stream().filter(item -> draft.quantities().containsKey(item.getId())).toList());
        model.addAttribute("quantities", draft.quantities());
        return "bookings/review";
    }

    @PostMapping("/new/submit")
    public String submit(HttpServletRequest request, RedirectAttributes flash) {
        User actor = sessions.requireUser(request);
        HttpSession session = request.getSession();
        // A duplicate submission in this session must not create a second booking.
        synchronized (session) {
            BookingDraft draft = draft(request, actor);
            if (draft == null) {
                Object last = session.getAttribute(LAST_SUBMISSION_KEY);
                return last instanceof LastSubmission submission && Objects.equals(submission.ownerId(), actor.getId())
                        ? "redirect:/bookings/" + submission.bookingId() + "/submitted" : missingDraft(flash);
            }
            if (!draft.equipmentChosen()) return "redirect:/bookings/new/equipment";
            try {
                BookingResponse saved = bookingService.createBooking(toRequest(draft.details(), draft.quantities()), actor.getId());
                session.removeAttribute(DRAFT_KEY);
                session.setAttribute(LAST_SUBMISSION_KEY, new LastSubmission(actor.getId(), saved.getId()));
                return "redirect:/bookings/" + saved.getId() + "/submitted";
            } catch (RoomNotAvailableException | EquipmentNotAvailableException | ForbiddenException | IllegalArgumentException ex) {
                flash.addFlashAttribute("flashError", friendlyMessage(ex));
                return "redirect:/bookings/new/review";
            } catch (ResourceNotFoundException ex) {
                flash.addFlashAttribute("flashError", "ห้องหรืออุปกรณ์ที่เลือกไม่มีแล้ว กรุณาตรวจรายการอีกครั้ง");
                return "redirect:/bookings/new";
            } catch (DataAccessException ex) {
                flash.addFlashAttribute("flashError", "ยังบันทึกคำขอไม่ได้ กรุณาลองอีกครั้ง");
                return "redirect:/bookings/new/review";
            }
        }
    }

    @GetMapping("/{id}/submitted")
    public String submitted(@PathVariable Long id, HttpServletRequest request, Model model) {
        BookingResponse booking = ownedBooking(id, sessions.requireUser(request));
        addBooking(model, booking);
        return "bookings/submitted";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, HttpServletRequest request, Model model) {
        BookingResponse booking = ownedBooking(id, sessions.requireUser(request));
        addBooking(model, booking);
        return "bookings/detail";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, HttpServletRequest request, Model model, RedirectAttributes flash) {
        BookingResponse booking = ownedBooking(id, sessions.requireUser(request));
        if (booking.getStatus() != BookingStatus.PENDING) return notEditable(id, flash);
        BookingEditForm form = new BookingEditForm();
        form.setRoomId(booking.getRoomId());
        form.setDate(booking.getStartTime().toLocalDate());
        form.setEndDate(booking.getEndTime().toLocalDate());
        form.setStartTime(booking.getStartTime().toLocalTime());
        form.setEndTime(booking.getEndTime().toLocalTime());
        form.setPurpose(booking.getPurpose());
        booking.getEquipmentItems().forEach(item -> form.getQuantities().put(item.getEquipmentId(), item.getQuantity()));
        model.addAttribute("editForm", form);
        addBooking(model, booking);
        addRooms(model);
        addEquipment(model);
        return "bookings/edit";
    }

    @PostMapping("/{id}/edit")
    public String saveEdit(@PathVariable Long id, HttpServletRequest request,
                           @Valid @ModelAttribute("editForm") BookingEditForm form, BindingResult errors,
                           Model model, RedirectAttributes flash) {
        User actor = sessions.requireUser(request);
        BookingResponse booking = ownedBooking(id, actor);
        if (booking.getStatus() != BookingStatus.PENDING) return notEditable(id, flash);
        validateDetails(form, errors);
        List<EquipmentResponse> equipment = addEquipment(model);
        Map<Long, Integer> selected = validateQuantities(form.getQuantities(), equipment, errors);
        if (!errors.hasErrors()) {
            try {
                bookingService.updateBooking(id, toRequest(form, selected), actor.getId());
                flash.addFlashAttribute("flashSuccess", "บันทึกการเปลี่ยนแปลงแล้ว");
                return "redirect:/bookings/" + id;
            } catch (RoomNotAvailableException | EquipmentNotAvailableException | ForbiddenException | IllegalArgumentException ex) {
                errors.reject("booking.unavailable", friendlyMessage(ex));
            } catch (InvalidStateTransitionException ex) {
                return notEditable(id, flash);
            } catch (ResourceNotFoundException ex) {
                errors.reject("booking.changed", "ห้องหรืออุปกรณ์มีการเปลี่ยนแปลง กรุณาตรวจรายการอีกครั้ง");
            } catch (DataAccessException ex) {
                errors.reject("booking.save", "ยังบันทึกการเปลี่ยนแปลงไม่ได้ กรุณาลองอีกครั้ง");
            }
        }
        addErrors(model, errors);
        addBooking(model, booking);
        addRooms(model);
        return "bookings/edit";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, HttpServletRequest request, RedirectAttributes flash) {
        User actor = sessions.requireUser(request);
        BookingResponse booking = ownedBooking(id, actor);
        if (!cancellable(booking.getStatus())) {
            flash.addFlashAttribute("flashError", "รายการนี้ไม่สามารถยกเลิกได้แล้ว");
            return "redirect:/bookings/" + id;
        }
        try {
            bookingService.updateStatus(id, BookingStatus.CANCELLED, actor.getId());
            flash.addFlashAttribute("flashSuccess", "ยกเลิกการจองแล้ว");
        } catch (InvalidStateTransitionException ex) {
            flash.addFlashAttribute("flashError", "สถานะรายการเปลี่ยนแล้ว และไม่สามารถยกเลิกได้");
        } catch (DataAccessException ex) {
            flash.addFlashAttribute("flashError", "ยังยกเลิกการจองไม่ได้ กรุณาลองอีกครั้ง");
        }
        return "redirect:/bookings/" + id;
    }

    private BookingResponse ownedBooking(Long id, User actor) {
        BookingResponse booking = bookingService.getBookingById(id);
        if (!Objects.equals(booking.getUserId(), actor.getId()) && !sessions.isManager(actor)) {
            throw new WebAccessDeniedException("คุณไม่มีสิทธิ์เข้าถึงการจองนี้");
        }
        return booking;
    }

    private void validateDetails(BookingDetailsForm form, BindingResult errors) {
        if (form.getStartDateTime() != null && form.getEndDateTime() != null
                && !form.getStartDateTime().isBefore(form.getEndDateTime())) {
            errors.rejectValue("endTime", "booking.time", "เวลาสิ้นสุดต้องอยู่หลังเวลาเริ่มต้น");
        }
        if (form.getStartDateTime() != null
                && form.getStartDateTime().isBefore(LocalDateTime.now(PastBookingTimeHandler.BOOKING_ZONE))) {
            errors.rejectValue("startTime", "booking.past", "ไม่สามารถจองช่วงเวลาที่ผ่านมาแล้ว กรุณาเลือกวันและเวลาใหม่");
        }
        if (form.getRoomId() != null && form.getRoomId() > 0 && !errors.hasFieldErrors("roomId")) {
            try {
                if (roomService.getRoomById(form.getRoomId()).getStatus() != RoomStatus.AVAILABLE) {
                    errors.rejectValue("roomId", "booking.room", "ห้องนี้ปิดซ่อมบำรุง กรุณาเลือกห้องอื่น");
                }
            } catch (ResourceNotFoundException ex) {
                errors.rejectValue("roomId", "booking.room", "ไม่พบห้องที่เลือก กรุณาเลือกห้องอีกครั้ง");
            }
        }
    }

    private Map<Long, Integer> validateQuantities(Map<Long, Integer> quantities, List<EquipmentResponse> equipment, BindingResult errors) {
        Map<Long, EquipmentResponse> known = equipment.stream().collect(Collectors.toMap(EquipmentResponse::getId, Function.identity()));
        Map<Long, Integer> selected = new LinkedHashMap<>();
        quantities.forEach((id, quantity) -> {
            if (quantity == null || quantity == 0) return;
            if (quantity < 0) {
                errors.reject("booking.quantity", "จำนวนอุปกรณ์ต้องไม่ติดลบ");
            } else if (!known.containsKey(id)) {
                errors.reject("booking.equipment", "ไม่พบอุปกรณ์ที่เลือก กรุณาตรวจรายการอีกครั้ง");
            } else if (known.get(id).getTotalQuantity() == null || quantity > known.get(id).getTotalQuantity()) {
                errors.reject("booking.quantity", "จำนวน " + known.get(id).getName() + " ที่เลือกมากกว่าจำนวนทั้งหมดในคลัง");
            } else {
                selected.put(id, quantity);
            }
        });
        return Collections.unmodifiableMap(selected);
    }

    private BookingCreateRequest toRequest(BookingDetailsForm details, Map<Long, Integer> quantities) {
        BookingCreateRequest request = new BookingCreateRequest();
        request.setRoomId(details.getRoomId());
        request.setStartTime(details.getStartDateTime());
        request.setEndTime(details.getEndDateTime());
        request.setPurpose(details.getPurpose());
        request.setEquipmentItems(quantities.entrySet().stream().map(entry ->
                new BookingCreateRequest.EquipmentItemRequest(entry.getKey(), entry.getValue())).toList());
        // Owner and booking id are assigned by the server/service, never by browser fields.
        return request;
    }

    private BookingDraft draft(HttpServletRequest request, User actor) {
        Object stored = request.getSession().getAttribute(DRAFT_KEY);
        if (stored == null) return null;
        if (stored instanceof BookingDraft draft && Objects.equals(draft.ownerId(), actor.getId())) return draft;
        request.getSession().removeAttribute(DRAFT_KEY);
        request.getSession().removeAttribute(LAST_SUBMISSION_KEY);
        return null;
    }

    private String missingDraft(RedirectAttributes flash) {
        flash.addFlashAttribute("flashError", "กรุณาเริ่มจากรายละเอียดการจองอีกครั้ง");
        return "redirect:/bookings/new";
    }

    private String notEditable(Long id, RedirectAttributes flash) {
        flash.addFlashAttribute("flashError", "แก้ไขได้เฉพาะรายการที่รออนุมัติ");
        return "redirect:/bookings/" + id;
    }

    private boolean cancellable(BookingStatus status) {
        return status == BookingStatus.PENDING || status == BookingStatus.APPROVED;
    }

    private void addErrors(Model model, BindingResult errors) {
        model.addAttribute("formErrors", errors.getAllErrors().stream().map(error ->
                error instanceof FieldError field && field.isBindingFailure()
                        ? "กรุณาตรวจรูปแบบวันที่ เวลา และจำนวนอุปกรณ์" : error.getDefaultMessage()).distinct().toList());
    }

    private void addRooms(Model model) {
        List<RoomResponse> rooms = new ArrayList<>();
        Page<RoomResponse> page;
        int index = 0;
        do {
            page = roomService.getAllRooms(PageRequest.of(index++, LOOKUP_PAGE_SIZE, Sort.by("name")));
            rooms.addAll(page.getContent());
        } while (page.hasNext());
        model.addAttribute("rooms", rooms);
    }

    private List<EquipmentResponse> addEquipment(Model model) {
        List<EquipmentResponse> equipment = new ArrayList<>();
        Page<EquipmentResponse> page;
        int index = 0;
        do {
            page = equipmentService.getAllEquipments(PageRequest.of(index++, LOOKUP_PAGE_SIZE, Sort.by("name")));
            equipment.addAll(page.getContent());
        } while (page.hasNext());
        model.addAttribute("equipment", equipment);
        model.addAttribute("equipmentPhotos", equipment.stream().collect(Collectors.toMap(EquipmentResponse::getId, item -> equipmentPhoto(item))));
        return equipment;
    }

    private List<BookingResponse> allUserBookings(Long userId) {
        List<BookingResponse> bookings = new ArrayList<>();
        Page<BookingResponse> page;
        int index = 0;
        do {
            page = bookingService.getBookingsByUser(userId, PageRequest.of(index++, LOOKUP_PAGE_SIZE, Sort.by("startTime").descending()));
            bookings.addAll(page.getContent());
        } while (page.hasNext());
        return bookings;
    }

    private void addSummary(Model model, BookingDetailsForm details) {
        model.addAttribute("selectedRoom", roomService.getRoomById(details.getRoomId()));
        model.addAttribute("summaryDate", dateLabel(details.getDate())
                + (details.getEndDate() != null && !details.getEndDate().equals(details.getDate()) ? " – " + dateLabel(details.getEndDate()) : ""));
        model.addAttribute("summaryTime", details.getStartTime().format(TIME) + "–" + details.getEndTime().format(TIME));
    }

    private void addBooking(Model model, BookingResponse booking) {
        model.addAttribute("booking", booking);
        model.addAttribute("dateLabel", dateRange(booking));
        model.addAttribute("timeLabel", timeRange(booking));
        model.addAttribute("createdDate", booking.getCreatedAt() == null ? "–" : dateLabel(booking.getCreatedAt().toLocalDate()));
        model.addAttribute("editable", booking.getStatus() == BookingStatus.PENDING);
        model.addAttribute("cancellable", cancellable(booking.getStatus()));
        model.addAttribute("itemPhotos", booking.getEquipmentItems().stream().collect(Collectors.toMap(
                BookingResponse.EquipmentItem::getEquipmentId, item -> equipmentPhoto(item.getEquipmentId(), item.getEquipmentName()), (first, second) -> first)));
    }

    private String dateRange(BookingResponse booking) {
        LocalDate start = booking.getStartTime().toLocalDate();
        LocalDate end = booking.getEndTime().toLocalDate();
        return dateLabel(start) + (start.equals(end) ? "" : " – " + dateLabel(end));
    }

    private String timeRange(BookingResponse booking) {
        return booking.getStartTime().format(TIME) + "–" + booking.getEndTime().format(TIME);
    }

    private String dateLabel(LocalDate date) {
        return date.format(THAI_DATE) + " " + (date.getYear() + 543);
    }

    // ใช้ลิงก์รูปที่แอดมินตั้งไว้ก่อน ถ้าไม่มีค่อยเดารูปจากชื่อแบบเดิม
    private String equipmentPhoto(EquipmentResponse item) {
        if (item.getImageUrl() != null && !item.getImageUrl().isBlank()) return item.getImageUrl();
        return equipmentPhoto(item.getName());
    }

    private String equipmentPhoto(Long equipmentId, String name) {
        if (equipmentId != null) {
            try {
                EquipmentResponse item = equipmentService.getEquipmentById(equipmentId);
                if (item != null && item.getImageUrl() != null && !item.getImageUrl().isBlank()) return item.getImageUrl();
            } catch (ResourceNotFoundException ignored) {
                // อุปกรณ์ถูกลบไปแล้ว ใช้รูปจากชื่อแทน
            }
        }
        return equipmentPhoto(name);
    }

    private String equipmentPhoto(String name) {
        String text = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (text.contains("โปรเจ") || text.contains("projector")) return "/assets/img/projector.png";
        if (text.contains("ไมโคร") || text.contains("microphone")) return "/assets/img/microphone.png";
        if (text.contains("จอ") || text.contains("monitor")) return "/assets/img/monitor.png";
        return "";
    }

    private String friendlyMessage(RuntimeException ex) {
        if (ex instanceof RoomNotAvailableException || ex instanceof EquipmentNotAvailableException || ex instanceof ForbiddenException) {
            return ex.getMessage();
        }
        return "กรุณาตรวจสอบห้อง วันที่ เวลา และจำนวนอุปกรณ์อีกครั้ง";
    }

    private record BookingDraft(Long ownerId, BookingDetailsForm details, Map<Long, Integer> quantities, boolean equipmentChosen) { }
    private record LastSubmission(Long ownerId, Long bookingId) { }
}
