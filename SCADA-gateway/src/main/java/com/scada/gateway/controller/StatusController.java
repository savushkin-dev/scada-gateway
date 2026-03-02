package com.scada.gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/status")
public class StatusController {

    @GetMapping
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("server", "SCADA Gateway");
        status.put("status", "RUNNING");
        status.put("time", LocalDateTime.now().toString());
        status.put("opcua", "CONNECTING");
        return status;
    }
}