package com.auvdidao.a12teachingagent.approval;

import com.auvdidao.a12teachingagent.approval.dto.ApprovalRequestDtos.ReviewApprovalRequest;
import com.auvdidao.a12teachingagent.approval.dto.ApprovalRequestDtos.SubmitApprovalRequest;
import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.domain.approval.ApprovalStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approval-requests")
public class ApprovalRequestController {

    private final ApprovalRequestService approvalRequestService;

    public ApprovalRequestController(ApprovalRequestService approvalRequestService) {
        this.approvalRequestService = approvalRequestService;
    }

    @PostMapping
    public ApiResponse<?> submit(@Valid @RequestBody SubmitApprovalRequest request) {
        return ApiResponse.success(approvalRequestService.submit(request));
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) ApprovalStatus status) {
        return ApiResponse.success(approvalRequestService.list(status));
    }

    @GetMapping("/{approvalRequestId}")
    public ApiResponse<?> get(@PathVariable Long approvalRequestId) {
        return ApiResponse.success(approvalRequestService.get(approvalRequestId));
    }

    @PutMapping("/{approvalRequestId}/review")
    public ApiResponse<?> review(
            @PathVariable Long approvalRequestId,
            @Valid @RequestBody ReviewApprovalRequest request
    ) {
        return ApiResponse.success(approvalRequestService.review(approvalRequestId, request));
    }

    @PostMapping("/{approvalRequestId}/cancel")
    public ApiResponse<?> cancel(@PathVariable Long approvalRequestId) {
        return ApiResponse.success(approvalRequestService.cancel(approvalRequestId));
    }
}
