package com.auvdidao.a12teachingagent.ai.connection;

import com.auvdidao.a12teachingagent.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.auvdidao.a12teachingagent.ai.connection.ModelConnectionDtos.*;

@RestController
@RequestMapping("/api/v1/ai-credentials/connections")
public class ModelConnectionController {
    private final ModelConnectionService service;
    public ModelConnectionController(ModelConnectionService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<ConnectionView>> list() { return ApiResponse.success(service.list()); }

    @PostMapping
    public ApiResponse<ConnectionView> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ConnectionView> update(@PathVariable @Positive Long id, @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @Positive Long id) { service.delete(id); return ApiResponse.success(); }

    @PostMapping("/{id}/enabled")
    public ApiResponse<ConnectionView> enabled(@PathVariable @Positive Long id, @RequestParam boolean value) {
        return ApiResponse.success(service.setEnabled(id, value));
    }

    @PostMapping("/{id}/verify")
    public ApiResponse<VerificationView> verify(@PathVariable @Positive Long id) {
        return ApiResponse.success(service.verify(id));
    }
}
