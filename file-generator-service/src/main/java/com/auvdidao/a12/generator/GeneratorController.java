package com.auvdidao.a12.generator;

import com.auvdidao.a12.generator.GeneratorDtos.PackageRequest;
import com.auvdidao.a12.generator.GeneratorDtos.RenderRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal")
class GeneratorController {
    private final GeneratorRenderer renderer;

    GeneratorController(GeneratorRenderer renderer) { this.renderer = renderer; }

    @GetMapping("/health")
    Map<String, String> health() { return Map.of("status", "UP"); }

    @PostMapping(value = "/file-generator/pptx", produces = "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    ResponseEntity<byte[]> pptx(@RequestBody RenderRequest request) {
        return binary(renderer.pptx(request), "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    @PostMapping(value = "/file-generator/docx", produces = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ResponseEntity<byte[]> docx(@RequestBody RenderRequest request) {
        return binary(renderer.docx(request), "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @PostMapping(value = "/file-generator/interactive-html", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<byte[]> interactiveHtml(@RequestBody RenderRequest request) {
        return binary(renderer.interactiveHtml(request), MediaType.TEXT_HTML_VALUE + ";charset=UTF-8");
    }

    @PostMapping(value = "/file-generator/package", produces = "application/zip")
    ResponseEntity<byte[]> packageFiles(@RequestBody PackageRequest request) {
        return binary(renderer.packageFiles(request), "application/zip");
    }

    @ExceptionHandler(GeneratorException.class)
    ResponseEntity<Map<String, String>> generatorFailure(GeneratorException exception) {
        return ResponseEntity.unprocessableEntity().body(Map.of("code", exception.code(), "message", exception.getMessage()));
    }

    private static ResponseEntity<byte[]> binary(byte[] bytes, String contentType) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, contentType).body(bytes);
    }
}
