package com.example.roombooking.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * รูปหนึ่งรูปในอัลบั้มของห้องหรืออุปกรณ์ (ตาราง item_images)
 * อ่าน/เขียนผ่าน ImageStorageService; entity นี้มีไว้ให้ Hibernate รู้จักตาราง
 * (สร้างตารางตอนเทสต์ และ validate ตอน deploy ว่ามีตารางจริง)
 */
@Entity
@Table(name = "item_images")
public class ItemImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_type", nullable = false, length = 20)
    private String itemType;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    // ใช้ bytea ให้ตรงกับ schema.sql (ถ้าปล่อยให้ Hibernate เลือกเอง รูปใหญ่จะได้ BLOB ซึ่ง H2 โหมด PostgreSQL ไม่รับ)
    @Column(name = "data", nullable = false, columnDefinition = "bytea")
    private byte[] data;

    public ItemImage() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
