package com.example.roombooking.controller.web.form;
import com.example.roombooking.domain.enums.*;
import jakarta.validation.constraints.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
@Data
public class AdminRoomForm {
    @NotBlank(message="กรุณาระบุชื่อห้อง") @Size(max=100) private String name;
    @NotNull @Min(value=1,message="ความจุต้องอย่างน้อย 1 คน") private Integer capacity;
    @NotBlank(message="กรุณาระบุชั้น") @Size(max=20) private String floor;
    @NotNull private RoomType roomType = RoomType.STANDARD;
    @NotNull private RoomStatus status = RoomStatus.AVAILABLE;
    @Size(max=500,message="ลิงก์รูปยาวได้ไม่เกิน 500 ตัวอักษร") @Pattern(regexp="^\\s*$|^\\s*https?://\\S+\\s*$",message="ลิงก์รูปต้องขึ้นต้นด้วย http:// หรือ https://") private String imageUrl;
    private List<MultipartFile> imageFiles = new ArrayList<>();
    // เขียน getter/setter เองสำหรับ field ใหม่ (ไม่พึ่ง Lombok)
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public List<MultipartFile> getImageFiles() { return imageFiles; }
    public void setImageFiles(List<MultipartFile> imageFiles) { this.imageFiles = imageFiles; }
}
