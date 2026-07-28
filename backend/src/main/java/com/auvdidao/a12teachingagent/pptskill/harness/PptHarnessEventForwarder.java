package com.auvdidao.a12teachingagent.pptskill.harness;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Proxies the Harness event stream after Spring Boot has enforced project access. */
@Component
public class PptHarnessEventForwarder {
    private final PptHarnessClient client;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public PptHarnessEventForwarder(PptHarnessClient client) { this.client = client; }

    public SseEmitter forward(String taskId) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> copy(taskId, emitter));
        return emitter;
    }

    private void copy(String taskId, SseEmitter emitter) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(client.eventsUrl(taskId)))
                    .timeout(Duration.ofMinutes(12)).header("Accept", "text/event-stream").GET().build();
            HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw new IllegalStateException("Harness event stream is unavailable");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                String event = "status";
                String id = null;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) event = line.substring(6).trim();
                    else if (line.startsWith("id:")) id = line.substring(3).trim();
                    else if (line.startsWith("data:")) {
                        SseEmitter.SseEventBuilder message = SseEmitter.event().name(event).data(line.substring(5).trim());
                        if (id != null) message.id(id);
                        emitter.send(message);
                    }
                }
            }
            emitter.complete();
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }
}
