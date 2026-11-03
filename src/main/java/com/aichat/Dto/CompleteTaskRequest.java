package com.aichat.Dto;

import jakarta.validation.constraints.NotNull;

/**
 * 完成任务请求体。带乐观锁 version；重复任务完成时 Service 计算下一次 due_at 再滚动。
 */
public class CompleteTaskRequest {
    @NotNull
    private Integer version;

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
