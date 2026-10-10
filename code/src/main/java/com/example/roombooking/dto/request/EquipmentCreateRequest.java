package com.example.roombooking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public class EquipmentCreateRequest {
    @NotBlank(message = "name is required")
    @Size(max = 100, message = "name must be at most 100 characters")
    private String name;

    @NotNull(message = "totalQuantity is required")
    @PositiveOrZero(message = "totalQuantity cannot be negative")
    private Integer totalQuantity;

    @Size(max = 50, message = "category must be at most 50 characters")
    private String category;

    @Size(max = 500, message = "imageUrl must be at most 500 characters")
    @Pattern(regexp = "^\\s*$|^https?://\\S+$|^/images/\\d+$", message = "imageUrl must start with http:// or https://")
    private String imageUrl;

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(Integer totalQuantity) { this.totalQuantity = totalQuantity; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
}