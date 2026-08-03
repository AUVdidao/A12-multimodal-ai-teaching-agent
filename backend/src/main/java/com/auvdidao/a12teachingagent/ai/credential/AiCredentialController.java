package com.auvdidao.a12teachingagent.ai.credential;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-credentials")
public class AiCredentialController {

    private final AiApiCredentialService service;

    public AiCredentialController(AiApiCredentialService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AiApiCredentialService.CredentialsView> get() {
        return ApiResponse.success(service.view());
    }

    @PostMapping
    public ApiResponse<AiApiCredentialService.CredentialsView> save(
            @Valid @RequestBody AiApiCredentialService.SaveCredentialsRequest request
    ) {
        return ApiResponse.success(service.save(request));
    }
}
