package com.example.roombooking.controller.web;

import com.example.roombooking.service.ImageStorageService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Duration;

/** ส่งไฟล์รูปที่แอดมินอัปโหลดไว้ (ห้อง/อุปกรณ์) */
@Controller
@Profile("web")
public class ImageViewController {

    private final ImageStorageService images;

    public ImageViewController(ImageStorageService images) {
        this.images = images;
    }

    @GetMapping("/images/{kind}/{id}")
    public ResponseEntity<byte[]> image(@PathVariable String kind, @PathVariable Long id) {
        return ImageStorageService.Kind.fromPath(kind)
                .flatMap(k -> images.load(k, id))
                .map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.getContentType()))
                        // URL มี ?v= เปลี่ยนทุกครั้งที่อัปโหลดใหม่ จึงแคชนานได้
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(image.getData()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
