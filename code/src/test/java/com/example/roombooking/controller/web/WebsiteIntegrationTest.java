package com.example.roombooking.controller.web;

import com.example.roombooking.domain.enums.BookingStatus;
import com.example.roombooking.repository.BookingRepository;
import com.example.roombooking.repository.UserRepository;
import com.example.roombooking.support.WebsiteTestDataConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP requests render Thymeleaf and exercise the existing services against an isolated database. */
@ActiveProfiles("web")
@Import(WebsiteTestDataConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:website-integration;MODE=PostgreSQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "server.servlet.session.cookie.secure=false"})
class WebsiteIntegrationTest {
    @Autowired Environment environment;
    @Autowired BookingRepository bookingRepository;
    @Autowired UserRepository userRepository;
    private WebClient browser() { return new WebClient("http://127.0.0.1:"+environment.getProperty("local.server.port")); }

    @Test void publicPagesRenderWithWebsiteAssetsAndErrors() throws Exception {
        WebClient web=browser();
        for(String path: List.of("/","/rooms","/rooms/1","/equipment","/equipment/1","/login","/register",
                "/rooms?q=missing&capacity=100","/equipment?category=missing")) {
            Reply reply=web.get(path); assertEquals(200,reply.status,path); assertTrue(reply.html.contains("<html"),path);
            assertFalse(reply.html.contains("th:replace="),path);
        }
        assertEquals(200,web.get("/assets/css/site.css").status);
        assertEquals(200,web.get("/assets/js/site.js").status);
        assertEquals(200,web.get("/assets/img/room-focus.png").status);
        assertEquals(404,web.get("/rooms/999999").status);
        assertEquals(404,web.get("/equipment/999999").status);
        assertEquals(404,web.get("/not-a-page").status);
        assertEquals(404,web.get("/assets/css/missing.css").status);
        assertEquals(405,web.get("/logout").status);
        assertEquals(400,web.get("/rooms?date=2026-10-10&startTime=11:00&endTime=09:00").status);
    }

    @Test void memberBookingWizardEditCancelAndManagerApprovalWork() throws Exception {
        WebClient member=browser(); member.login("narin");
        assertEquals(200,member.get("/account").status); assertEquals(200,member.get("/bookings").status);
        assertEquals(200,member.get("/bookings/new?roomId=2").status);
        String date=LocalDate.now().plusDays(14).toString();
        Reply step1=member.post("/bookings/new/details", Map.of("roomId","2","date",date,"startTime","09:00","endTime","11:00","purpose","HTTP integration test"));
        assertEquals("/bookings/new/equipment",step1.location);
        assertEquals(200,member.get(step1.location).status);
        Reply step2=member.post("/bookings/new/equipment",Map.of("quantities[1]","1","quantities[2]","0"));
        assertEquals("/bookings/new/review",step2.location); assertEquals(200,member.get(step2.location).status);
        Reply submit=member.post("/bookings/new/submit",Map.of()); assertEquals(302,submit.status);
        assertTrue(submit.location.matches("/bookings/\\d+/submitted"),submit.location);
        assertEquals(200,member.get(submit.location).status);
        String detail=submit.location.replace("/submitted","");
        Long bookingId=Long.valueOf(detail.substring(detail.lastIndexOf('/')+1));
        assertEquals(BookingStatus.PENDING,bookingRepository.findById(bookingId).orElseThrow().getStatus());
        assertEquals(200,member.get(detail).status); assertEquals(200,member.get(detail+"/edit").status);
        Reply edit=member.post(detail+"/edit",Map.of("roomId","2","date",date,"startTime","10:00","endTime","12:00","purpose","Updated purpose","quantities[1]","1"));
        assertEquals(detail,edit.location); assertTrue(member.get(detail).html.contains("Updated purpose"));
        WebClient manager=browser(); manager.login("staff");
        String adminDetail="/admin"+detail; assertEquals(200,manager.get(adminDetail).status);
        assertEquals(adminDetail,manager.post(adminDetail+"/status",Map.of("status","APPROVED")).location);
        assertTrue(manager.get(adminDetail).html.contains("อนุมัติแล้ว"));
        assertEquals(BookingStatus.APPROVED,bookingRepository.findById(bookingId).orElseThrow().getStatus());
        assertEquals(302,member.get(detail+"/edit").status);
        assertEquals(detail,member.post(detail+"/cancel",Map.of()).location);
        assertTrue(member.get(detail).html.contains("ยกเลิกแล้ว"));
        assertEquals(BookingStatus.CANCELLED,bookingRepository.findById(bookingId).orElseThrow().getStatus());
        for(String path:List.of("/admin","/admin/bookings","/admin/bookings/new","/admin/rooms","/admin/equipment")) assertEquals(200,manager.get(path).status,path);
        assertEquals(403,manager.get("/admin/users").status);
    }

    @Test void standardBookingWaitsForApprovalAndCanStillBeEdited() throws Exception {
        WebClient member=browser(); member.login("narin");
        assertEquals(200,member.get("/bookings/new?roomId=1").status);
        String date=LocalDate.now().plusDays(15).toString();
        assertEquals("/bookings/new/equipment",member.post("/bookings/new/details",
                Map.of("roomId","1","date",date,"startTime","13:00","endTime","14:00","purpose","STANDARD approval QA")).location);
        assertEquals(200,member.get("/bookings/new/equipment").status);
        assertEquals("/bookings/new/review",member.post("/bookings/new/equipment",Map.of()).location);
        assertEquals(200,member.get("/bookings/new/review").status);
        Reply submit=member.post("/bookings/new/submit",Map.of());
        assertTrue(submit.location.matches("/bookings/\\d+/submitted"),submit.html);
        String detail=submit.location.replace("/submitted","");
        Long bookingId=Long.valueOf(detail.substring(detail.lastIndexOf('/')+1));
        // กฎใหม่: ห้องมาตรฐานรอผู้ดูแลระบบอนุมัติ และยังแก้ไขได้ระหว่างรอ
        assertEquals(BookingStatus.PENDING,bookingRepository.findById(bookingId).orElseThrow().getStatus());
        assertTrue(member.get(submit.location).html.contains("รออนุมัติ"));
        assertEquals(200,member.get(detail+"/edit").status);
        assertEquals(detail,member.post(detail+"/cancel",Map.of()).location);
        assertEquals(BookingStatus.CANCELLED,bookingRepository.findById(bookingId).orElseThrow().getStatus());
    }

    @Test void registrationCreatesEncodedPasswordAndSupportsLaterLogin() throws Exception {
        WebClient newMember=browser();
        assertEquals(200,newMember.get("/register").status);
        Reply registration=newMember.post("/register",Map.of("username","e2e_member","email","e2e-member@example.com",
                "password","local123","confirmPassword","local123","role","ADMIN"));
        assertEquals("/account",registration.location);
        assertEquals(200,newMember.get("/account").status);
        var saved=userRepository.findByUsername("e2e_member").orElseThrow();
        assertNotEquals("local123",saved.getPassword());
        assertEquals(com.example.roombooking.domain.enums.Role.USER,saved.getRole());
        assertEquals("/login?loggedOut",newMember.post("/logout",Map.of()).location);
        WebClient laterSession=browser(); laterSession.login("e2e_member");
        assertTrue(laterSession.get("/account").html.contains("e2e_member"));
    }

    @Test void adminCreatesAndEditsCatalogAndBooksOnBehalf() throws Exception {
        WebClient admin=browser(); admin.login("admin");
        for(String path:List.of("/admin/users","/admin/users/1")) assertEquals(200,admin.get(path).status,path);
        admin.get("/admin/rooms");
        assertEquals("/admin/rooms",admin.post("/admin/rooms",Map.of("name","QA Room","capacity","4","floor","1","roomType","STANDARD","status","AVAILABLE")).location);
        String roomPage=admin.get("/admin/rooms").html;
        Matcher room=Pattern.compile("href=\"/admin/rooms\\?editId=(\\d+)\"").matcher(roomPage);
        String roomId=null; while(room.find()) roomId=room.group(1);
        assertNotNull(roomId,"Admin room edit link must exist");
        assertEquals(200,admin.get("/admin/rooms?editId="+roomId).status);
        assertEquals("/admin/rooms",admin.post("/admin/rooms/"+roomId,Map.of("name","QA Room Updated","capacity","5","floor","2","roomType","VIP","status","AVAILABLE")).location);
        assertTrue(admin.get("/admin/rooms").html.contains("QA Room Updated"));
        assertEquals("/admin/rooms",admin.post("/admin/rooms/"+roomId+"/delete",Map.of()).location);
        admin.get("/admin/equipment");
        assertEquals("/admin/equipment",admin.post("/admin/equipment",Map.of("name","QA Cable","totalQuantity","2","category","QA")).location);
        String itemPage=admin.get("/admin/equipment").html;
        Matcher item=Pattern.compile("href=\"/admin/equipment\\?editId=(\\d+)\"").matcher(itemPage);
        String itemId=null; while(item.find()) itemId=item.group(1);
        assertNotNull(itemId);
        assertEquals(200,admin.get("/admin/equipment?editId="+itemId).status);
        assertEquals("/admin/equipment",admin.post("/admin/equipment/"+itemId,Map.of("name","QA Cable Updated","totalQuantity","3","category","QA")).location);
        assertTrue(admin.get("/admin/equipment").html.contains("QA Cable Updated"));
        assertEquals("/admin/equipment",admin.post("/admin/equipment/"+itemId+"/delete",Map.of()).location);
        assertEquals(200,admin.get("/admin/bookings/new").status);
        Reply behalf=admin.post("/admin/bookings/new",Map.of("userId","1","roomId","2","date",LocalDate.now().plusDays(16).toString(),"startTime","09:00","endTime","11:00","purpose","On behalf QA"));
        assertTrue(behalf.location.matches("/admin/bookings/\\d+"),behalf.html);
        assertTrue(admin.get(behalf.location).html.contains("narin"));
        admin.get("/admin/users");
        assertEquals("/admin/users",admin.post("/admin/users",Map.of("username","qa_member","email","qa@example.com","password","qa12345","role","USER")).location);
        assertTrue(admin.get("/admin/users").html.contains("qa_member"));
    }

    @Test void sessionsCsrfAndMemberPrivilegesAreEnforced() throws Exception {
        WebClient guest=browser();
        assertEquals(302,guest.get("/bookings").status); assertEquals(302,guest.get("/admin").status);
        assertEquals(403,guest.rawPost("/login",Map.of("username","admin","password","local123")).status);
        assertEquals(403,guest.get("/api/rooms").status);
        WebClient member=browser(); member.login("narin");
        assertEquals(403,member.get("/admin").status);
        assertEquals(403,member.get("/admin/users").status);
        assertEquals(403,member.post("/admin/rooms",Map.of("name","forbidden")).status);
        assertEquals(302,member.post("/logout",Map.of()).status);
        assertEquals(302,member.get("/bookings").status);
    }

    private record Reply(int status,String html,String location) { }
    private static class WebClient {
        private final String base;
        private String token;
        private final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        WebClient(String base) { this.base=base; }
        Reply get(String path) throws Exception { return send(HttpRequest.newBuilder(URI.create(base+path)).GET().build()); }
        Reply rawPost(String path,Map<String,String> fields) throws Exception {
            String body=fields.entrySet().stream().map(e -> encode(e.getKey())+"="+encode(e.getValue())).reduce((a,b)->a+"&"+b).orElse("");
            return send(HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        }
        Reply post(String path,Map<String,String> fields) throws Exception {
            Map<String,String> values=new HashMap<>(fields); values.put("_csrf",token); return rawPost(path,values);
        }
        Reply send(HttpRequest request) throws Exception {
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Matcher matcher=Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(response.body());
            if(matcher.find()) token=matcher.group(1);
            String location=response.headers().firstValue("Location").orElse("");
            if(location.startsWith(base)) location=location.substring(base.length());
            return new Reply(response.statusCode(),response.body(),location);
        }
        void login(String username) throws Exception {
            assertEquals(200,get("/login").status);
            Reply response=post("/login",Map.of("username",username,"password","local123")); assertEquals(302,response.status,response.html);
            assertEquals(200,get(response.location).status);
        }
        private static String encode(String text) { return URLEncoder.encode(text,StandardCharsets.UTF_8); }
    }
}
