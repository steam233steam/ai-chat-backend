package com.aichat.Controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Dto.ConfirmResult;
import com.aichat.Dto.PendingActionVO;
import com.aichat.Dto.Result;
import com.aichat.Service.PendingActionService;
import com.aichat.Service.support.ConfirmationReceiptWriter;

/**
 * 弹窗路径短回执：
 * - 确认成功只写“已经定好”
 * - 取消成功只写“计划取消”
 * - 重复确认/重复取消不重复写
 */
class PendingActionControllerTest {

    private PendingActionService service;
    private ConfirmationReceiptWriter receiptWriter;
    private PendingActionController controller;

    @BeforeEach
    void setUp() {
        service = mock(PendingActionService.class);
        receiptWriter = mock(ConfirmationReceiptWriter.class);
        controller = new PendingActionController();
        ReflectionTestUtils.setField(controller, "pendingActionService", service);
        ReflectionTestUtils.setField(controller, "confirmationReceiptWriter", receiptWriter);
    }

    @Test
    void confirmShouldWriteFixedShortReceipt() {
        ConfirmResult result = new ConfirmResult(false, List.of(), List.of());
        result.setSessionId("s1");
        when(service.confirm(9L)).thenReturn(result);

        Result<ConfirmResult> response = controller.confirm(9L);

        assertThat(response.getCode()).isEqualTo(200);
        verify(receiptWriter).writeConfirmed("s1");
    }

    @Test
    void alreadyConfirmedShouldNotWriteDuplicateReceipt() {
        ConfirmResult result = new ConfirmResult(true, List.of(), List.of());
        result.setSessionId("s1");
        when(service.confirm(9L)).thenReturn(result);

        controller.confirm(9L);

        verify(receiptWriter, never()).writeConfirmed("s1");
    }

    @Test
    void rejectShouldWriteFixedPlanCancelledReceipt() {
        PendingActionVO pending = new PendingActionVO();
        pending.setId(9L);
        pending.setSessionId("s1");
        pending.setStatus("pending");
        when(service.getById(9L)).thenReturn(pending);
        when(service.reject(9L)).thenReturn(true);

        controller.reject(9L);

        verify(receiptWriter).writeCancelled("s1");
    }

    @Test
    void idempotentRejectShouldNotWriteDuplicateReceipt() {
        PendingActionVO pending = new PendingActionVO();
        pending.setId(9L);
        pending.setSessionId("s1");
        pending.setStatus("rejected");
        when(service.getById(9L)).thenReturn(pending);
        when(service.reject(9L)).thenReturn(false);

        controller.reject(9L);

        verify(receiptWriter, never()).writeCancelled("s1");
    }
}
