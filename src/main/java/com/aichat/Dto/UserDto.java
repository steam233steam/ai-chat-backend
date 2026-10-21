package com.aichat.Dto;

/**
 * 用户信息 DTO（返回给前端的）
 * 只包含前端需要的字段，不包含敏感信息（如密码）
 */
public class UserDto {
    // 用户唯一标识
    private String id;
    // 用户名/昵称
    private String name;
    // 手机号（可选，前端展示用）
    private String phone;
    // 是否为新用户（需要引导设置密码/昵称），仅在 /user/me 中返回
    private Boolean isNewUser;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Boolean getIsNewUser() {
        return isNewUser;
    }

    public void setIsNewUser(Boolean isNewUser) {
        this.isNewUser = isNewUser;
    }
}
