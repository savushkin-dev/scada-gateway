package com.scada.gateway.controller;

import com.scada.gateway.ha.LeaderElector;
import com.scada.gateway.model.entity.EventLogEntity;
import com.scada.gateway.script.LuaValueScripts;
import com.scada.gateway.service.EventLogService;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST-эндпоинты мониторинга: здоровье шлюза и выборки из журнала событий
 * (event_log) по типу/важности. Для оператора и интеграций, не для потока телеметрии.
 */
@RestController
@RequestMapping("/api")
public class MonitoringController {

    private final EventLogService eventLogService;
    private final LeaderElector leaderElector;
    private final LuaValueScripts scripts;

    public MonitoringController(EventLogService eventLogService, LeaderElector leaderElector,
                                LuaValueScripts scripts) {
        this.eventLogService = eventLogService;
        this.leaderElector = leaderElector;
        this.scripts = scripts;
    }

    /**
     * Пользовательские скрипты обработки значений. GET /api/scripts →
     * {dir, bindings: [{script, tags, matchedTags, write, errors, lastError}], lastReloadError}.
     */
    @GetMapping("/scripts")
    public Map<String, Object> scripts() {
        return scripts.info();
    }

    /**
     * Роль экземпляра в паре горячего резерва. GET /api/ha →
     * {enabled, instance, role: ACTIVE|STANDBY, since, group}.
     */
    @GetMapping("/ha")
    public Map<String, Object> ha() {
        Map<String, Object> response = new HashMap<>();
        response.put("enabled", leaderElector.isHaEnabled());
        response.put("instance", leaderElector.instanceId());
        response.put("role", leaderElector.isActive() ? "ACTIVE" : "STANDBY");
        response.put("since", leaderElector.roleSince().toString());
        response.put("group", leaderElector.groupId());
        return response;
    }

    /** Лёгкая проверка живости шлюза. GET /api/health → {status:UP}. */
    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "SCADA Gateway");
        return response;
    }

    // ==================== ЖУРНАЛ СОБЫТИЙ ====================

    /**
     * Получить последние события
     * GET /api/events?limit=100
     */
    @GetMapping("/events")
    public List<EventLogEntity> getRecentEvents(@RequestParam(defaultValue = "100") int limit) {
        return eventLogService.getRecentEvents(limit);
    }

    /**
     * Получить события по типу
     * GET /api/events/type/CONNECTION
     */
    @GetMapping("/events/type/{eventType}")
    public List<EventLogEntity> getEventsByType(@PathVariable String eventType) {
        return eventLogService.getEventsByType(eventType);
    }

    /**
     * Получить события по severity (INFO, WARNING, ERROR, CRITICAL)
     * GET /api/events/severity/ERROR
     */
    @GetMapping("/events/severity/{severity}")
    public List<EventLogEntity> getEventsBySeverity(@PathVariable String severity) {
        return eventLogService.getEventsBySeverity(severity);
    }

    /**
     * Получить неподтвержденные алармы
     * GET /api/events/alarms/unacknowledged
     */
    @GetMapping("/events/alarms/unacknowledged")
    public List<EventLogEntity> getUnacknowledgedAlarms() {
        return eventLogService.getUnacknowledgedAlarms();
    }

    /**
     * Получить все алармы (неподтвержденные и подтвержденные)
     * GET /api/events/alarms
     */
    @GetMapping("/events/alarms")
    public List<EventLogEntity> getAllAlarms() {
        return eventLogService.getEventsByType("ALARM");
    }

    /**
     * Подтвердить аларм
     * POST /api/events/{id}/acknowledge?userId=operator
     */
    @PostMapping("/events/{id}/acknowledge")
    public Map<String, String> acknowledgeAlarm(@PathVariable Long id, @RequestParam String userId) {
        eventLogService.acknowledgeAlarm(id, userId);
        Map<String, String> response = new HashMap<>();
        response.put("status", "ACKNOWLEDGED");
        response.put("message", "Alarm " + id + " acknowledged by " + userId);
        return response;
    }

    /**
     * Получить статистику по событиям
     * GET /api/events/stats
     */
    @GetMapping("/events/stats")
    public Map<String, Object> getEventStats() {
        Map<String, Object> stats = new HashMap<>();
        
        List<EventLogEntity> allEvents = eventLogService.getRecentEvents(10000);
        
        long errors = allEvents.stream()
                .filter(e -> "ERROR".equals(e.getSeverity()) || "CRITICAL".equals(e.getSeverity()))
                .count();
        
        long warnings = allEvents.stream()
                .filter(e -> "WARNING".equals(e.getSeverity()))
                .count();
        
        long unacknowledgedAlarms = eventLogService.getUnacknowledgedAlarms().size();
        
        stats.put("total_events", allEvents.size());
        stats.put("errors", errors);
        stats.put("warnings", warnings);
        stats.put("unacknowledged_alarms", unacknowledgedAlarms);
        stats.put("timestamp", System.currentTimeMillis());
        
        return stats;
    }
}