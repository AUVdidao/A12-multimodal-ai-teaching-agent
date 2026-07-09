package com.auvdidao.a12teachingagent.dialog;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.dialog.dto.DialogDtos.DialogMessageRequest;
import com.auvdidao.a12teachingagent.dialog.dto.DialogDtos.DialogMessageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DialogController {

    private final DialogService dialogService;

    public DialogController(DialogService dialogService) {
        this.dialogService = dialogService;
    }

    @PostMapping("/projects/{projectId}/dialogues")
    public ApiResponse<DialogMessageResponse> saveProjectDialogue(
            @PathVariable Long projectId,
            @Valid @RequestBody DialogMessageRequest request
    ) {
        return ApiResponse.success(dialogService.saveMessage(projectId, request));
    }

    @GetMapping("/projects/{projectId}/dialogues")
    public ApiResponse<List<DialogMessageResponse>> listProjectDialogues(@PathVariable Long projectId) {
        return ApiResponse.success(dialogService.listProjectMessages(projectId));
    }

    @GetMapping("/dialogues/{sessionId}")
    public ApiResponse<List<DialogMessageResponse>> listSessionDialogues(@PathVariable String sessionId) {
        return ApiResponse.success(dialogService.listSessionMessages(sessionId));
    }
}
