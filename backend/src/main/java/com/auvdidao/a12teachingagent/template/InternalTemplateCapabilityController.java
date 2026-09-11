package com.auvdidao.a12teachingagent.template;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.common.exception.ForbiddenException;
import com.auvdidao.a12teachingagent.security.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.auvdidao.a12teachingagent.template.TemplateDtos.InternalCapabilityRequest;
import static com.auvdidao.a12teachingagent.template.TemplateDtos.InternalCapabilityResponse;

@RestController
@Validated
@RequestMapping("/api/v1/internal/template-capability")
public class InternalTemplateCapabilityController {

    private final TemplateProfileService profileService;
    private final CurrentUserService currentUserService;

    public InternalTemplateCapabilityController(TemplateProfileService profileService, CurrentUserService currentUserService) {
        this.profileService = profileService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    public ApiResponse<InternalCapabilityResponse> resolve(@Valid @RequestBody InternalCapabilityRequest request) {
        Long actor = currentUserService.requireUser().userId();
        if (actor == null || !actor.equals(request.ownerUserId())) {
            throw new ForbiddenException("Internal capability owner does not match actor");
        }
        return ApiResponse.success(profileService.internalCapability(request));
    }
}
