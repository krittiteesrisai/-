package com.example.roombooking.dto.request;

import com.example.roombooking.domain.enums.RoomStatus;
import com.example.roombooking.domain.enums.RoomType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class RoomCreateRequest {
    @NotBlank(message = "name is required")
    @Size(max = 100, message = "name must be at most 100 characters")
    private String name;

    @NotNull(message = "capacity is required")
    @Positive(message = "capacity must be greater than 0")
    private Integer capacity;

    @Size(max = 20, message = "floor must be at most 20 characters")
    private String floor;

    @NotNull(message = "roomType is required")
    private RoomType roomType;

    @NotNull(message = "status is required")
    private RoomStatus status;

    @Size(max = 500, message = "imageUrl must be at most 500 characters")
    @Pattern(regexp = "^\\s*$|^https?://\\S+$|^/images/(rooms|equipment)/\\d+(\\?v=\\d+)?$", message = "imageUrl must start with http:// or https://")
    private String imageUrl;

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public String getFloor() { return floor; }
    public void setFloor(String floor) { this.floor = floor; }
    public RoomType getRoomType() { return roomType; }
    public void setRoomType(RoomType roomType) { this.roomType = roomType; }
    public RoomStatus getStatus() { return status; }
    public void setStatus(RoomStatus status) { this.status = status; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
}