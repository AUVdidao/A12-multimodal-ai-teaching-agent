package com.auvdidao.a12teachingagent.publication;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import com.auvdidao.a12teachingagent.domain.publication.PublicationStatus;
import com.auvdidao.a12teachingagent.publication.dto.PublicationDtos.CreatePublicationRequest;
import com.auvdidao.a12teachingagent.publication.dto.PublicationDtos.PublicationResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/publications")
public class PublicationController {

    private final PublicationService publicationService;

    public PublicationController(PublicationService publicationService) {
        this.publicationService = publicationService;
    }

    @PostMapping
    public ApiResponse<PublicationResponse> publish(@Valid @RequestBody CreatePublicationRequest request) {
        return ApiResponse.success(publicationService.publish(request));
    }

    @GetMapping
    public ApiResponse<List<PublicationResponse>> list(
            @RequestParam(required = false) PublicationStatus status
    ) {
        return ApiResponse.success(publicationService.listPublications(status));
    }

    @GetMapping("/{publicationId}")
    public ApiResponse<PublicationResponse> get(@PathVariable Long publicationId) {
        return ApiResponse.success(publicationService.getPublication(publicationId));
    }

    @PostMapping("/{publicationId}/withdraw")
    public ApiResponse<PublicationResponse> withdraw(@PathVariable Long publicationId) {
        return ApiResponse.success(publicationService.withdraw(publicationId));
    }
}
