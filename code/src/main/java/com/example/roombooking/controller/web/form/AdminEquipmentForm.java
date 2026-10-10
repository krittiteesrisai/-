package com.example.roombooking.controller.web.form;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class AdminEquipmentForm {
    @NotBlank(message="กรุณาระบุชื่ออุปกรณ์") @Size(max=100) private String name;
    @NotNull @Min(value=0,message="จำนวนต้องไม่น้อยกว่า 0") private Integer totalQuantity;
    @NotBlank(message="กรุณาระบุหมวดหมู่") @Size(max=50) private String category;
    @Size(max=500,message="ลิงก์รูปยาวได้ไม่เกิน 500 ตัวอักษร") @Pattern(regexp="^\\s*$|^\\s*https?://\\S+\\s*$",message="ลิงก์รูปต้องขึ้นต้นด้วย http:// หรือ https://") private String imageUrl;
    // เขียน getter/setter เองสำหรับ field ใหม่ (ไม่พึ่ง Lombok)
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
}
