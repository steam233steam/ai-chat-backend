package com.aichat.Dto;

import java.time.LocalDateTime;

public class User {
    private String uid;
    private String name;
    private String phone;
    private String password;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    //getter and setter
    public String getUid() {
        return uid;
    }
    public void setUid(String uid) {
        this.uid = uid;
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
    public String getPassword() {
        return password;
    }
    public void setPassword(String password) {
        this.password = password;
    }
    public LocalDateTime getCreateTime() {
        return createTime;
    }
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
    public LocalDateTime getUpdateTime() {
        return updateTime;
    }
    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
    //有参构造，无参构造
    public User(String uid, String name, String phone, String password, LocalDateTime createTime, LocalDateTime updateTime) {
        this.uid = uid;
        this.name = name;
        this.phone = phone;
        this.password = password;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }
    public User() {
    }
}
