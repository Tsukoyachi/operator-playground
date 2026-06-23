package com.example.app;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
public class InfoController {

    private final AppProperties properties;

    public InfoController(AppProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/info")
    public Map<String, String> info() throws Exception {

        String message = Files.readString(Path.of(properties.messageFile()));

        String secret = System.getenv(properties.secretName());

        return Map.of("message", message.trim(), "secret", secret);
    }
}
