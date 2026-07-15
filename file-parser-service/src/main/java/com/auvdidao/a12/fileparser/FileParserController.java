package com.auvdidao.a12.fileparser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
public class FileParserController {

    private final FileParserService parserService;

    public FileParserController(FileParserService parserService) {
        this.parserService = parserService;
    }

    @GetMapping("/internal/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping(path = "/internal/file-parser/parse", consumes = "multipart/form-data")
    public FileParserService.ParseResponse parse(
            @RequestPart("file") MultipartFile file,
            @RequestParam String fileType,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) List<String> usageTypes
    ) {
        return parserService.parse(file, fileType, topic, usageTypes == null ? List.of() : usageTypes);
    }

    @ExceptionHandler(ParserException.class)
    public ResponseEntity<Map<String, String>> parserFailure(ParserException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("code", exception.code(), "message", exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> requestTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("code", "REQUEST_TOO_LARGE", "message", "Material exceeds the parser request size limit."));
    }
}
