package com.scada.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scada.gateway.config.OpcUaConfig;
import com.scada.gateway.model.entity.ControllerEntity;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.repository.ControllerRepository;
import com.scada.gateway.repository.TagRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Мост между статическим конфигом и рантаймом. При старте синхронизирует БД с
 * controllers.yaml (initDatabaseFromYaml, идемпотентно) и держит теги/контроллеры в
 * кэшах, отдавая их остальным сервисам (getAllControllers, getTagsForController,
 * getAllActiveTags). Кэши убирают запросы к БД с горячего пути опроса.
 */
@Service
public class ConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ControllerRepository controllerRepository;
    private final TagRepository tagRepository;
    private final OpcUaConfig opcUaConfig;

    private Map<Long, ControllerEntity> controllerCache = new HashMap<>();
    private Map<Long, TagEntity> tagCache = new HashMap<>();
    private Map<String, TagEntity> tagByNodeIdCache = new HashMap<>();
    // Готовые списки активных тегов по контроллеру — чтобы poll-поток не гонял
    // stream().filter() по всем ~2471 тегу на КАЖДЫЙ цикл опроса.
    private Map<Long, List<TagEntity>> tagsByControllerCache = new HashMap<>();

    // Явный конструктор (вместо @RequiredArgsConstructor)
    public ConfigurationService(ControllerRepository controllerRepository,
                                TagRepository tagRepository,
                                OpcUaConfig opcUaConfig) {
        this.controllerRepository = controllerRepository;
        this.tagRepository = tagRepository;
        this.opcUaConfig = opcUaConfig;
    }

    /** Старт: синхронизирует БД с YAML и прогревает кэши. Вызывается один раз (@PostConstruct). */
    @PostConstruct
    @Transactional
    public void initDatabaseFromYaml() {
        // Полная синхронизация БД с YAML: добавляем новые, обновляем изменённые
        // и удаляем исчезнувшие из конфига контроллеры/теги. Идемпотентно —
        // выполняется при каждом старте, ручная чистка БД больше не нужна.
        syncDatabaseFromYaml();

        // Загружаем в кэш
        loadConfiguration();
    }

    /**
     * Полная синхронизация БД с controllers.yaml: upsert контроллеров по имени и тегов
     * по nodeId в пределах контроллера, удаление исчезнувших из YAML. Идемпотентно —
     * повторный старт с тем же конфигом ничего не меняет. YAML — источник истины.
     */
    private void syncDatabaseFromYaml() {
        log.info("Synchronizing database with YAML configuration...");

        int created = 0, updated = 0, deleted = 0;
        Set<String> yamlControllerNames = new HashSet<>();

        for (OpcUaConfig.OpcUaServerConfig serverConfig : opcUaConfig.getServers()) {
            yamlControllerNames.add(serverConfig.getName());

            // --- Контроллер: upsert по уникальному имени ---
            ControllerEntity controller = controllerRepository
                .findByName(serverConfig.getName())
                .orElseGet(ControllerEntity::new);
            boolean isNewController = controller.getId() == null;

            controller.setName(serverConfig.getName());
            controller.setEndpoint(serverConfig.getEndpoint());
            controller.setSecurityPolicy(serverConfig.getSecurity());
            controller.setUsername(serverConfig.getUsername());
            controller.setPassword(serverConfig.getPassword());
            controller.setEnabled(serverConfig.isEnabled());

            ControllerEntity savedController = controllerRepository.save(controller);
            log.info("{} controller: {} (ID {})",
                isNewController ? "Created" : "Updated",
                savedController.getName(), savedController.getId());

            // --- Теги: upsert по nodeId в пределах контроллера ---
            Map<String, TagEntity> existingByNodeId = tagRepository
                .findByControllerId(savedController.getId()).stream()
                .collect(Collectors.toMap(TagEntity::getNodeId, t -> t, (a, b) -> a));
            Set<String> yamlNodeIds = new HashSet<>();

            for (OpcUaConfig.TagConfig tagConfig : serverConfig.getTags()) {
                yamlNodeIds.add(tagConfig.getNodeId());

                TagEntity tag = existingByNodeId.get(tagConfig.getNodeId());
                if (tag == null) {
                    tag = new TagEntity();
                    tag.setController(savedController);
                    created++;
                } else {
                    updated++;
                }
                tag.setNodeId(tagConfig.getNodeId());
                tag.setName(tagConfig.getName());
                tag.setDataType(tagConfig.getDataType());
                tag.setPollingRate(tagConfig.getPollingRate());
                tag.setUnit(tagConfig.getUnit());
                tag.setEnabled(tagConfig.isEnabled());
                // Пороги для алармов: без них processTagValue не генерит ни одного аларма.
                tag.setMinValue(tagConfig.getMinValue());
                tag.setMaxValue(tagConfig.getMaxValue());
                // Связь с общей БД каналов + параметры Modbus.
                tag.setChannelId(tagConfig.getChannelId());
                // Объектная модель прибора (device={field:…}) → в Kafka metadata.
                tag.setDeviceName(tagConfig.getDeviceName());
                tag.setFieldName(tagConfig.getFieldName());
                tag.setDeviceType(tagConfig.getDeviceType());
                // Режим сырой записи прибора: карту полей сериализуем в JSON.
                tag.setRecordDevice(tagConfig.isRecordDevice());
                if (tagConfig.getFields() != null) {
                    try {
                        tag.setFieldsJson(objectMapper.writeValueAsString(tagConfig.getFields()));
                    } catch (Exception e) {
                        log.error("Не удалось сериализовать fields для {}: {}", tagConfig.getName(), e.getMessage());
                    }
                }
                tag.setProtocol(tagConfig.getProtocol());
                tag.setModbusAddress(tagConfig.getModbusAddress());
                tag.setModbusType(tagConfig.getModbusType());
                tag.setModbusUnitId(tagConfig.getModbusUnitId());
                // Доступ к записи (RW актуатор / RO датчик) — enforce'ит writeTag.
                tag.setWritable(tagConfig.isWritable());

                tagRepository.save(tag);
            }

            // Теги, которых больше нет в YAML — удаляем
            List<TagEntity> staleTags = existingByNodeId.values().stream()
                .filter(t -> !yamlNodeIds.contains(t.getNodeId()))
                .collect(Collectors.toList());
            if (!staleTags.isEmpty()) {
                tagRepository.deleteAll(staleTags);
                deleted += staleTags.size();
                log.info("Deleted {} stale tags from controller {}",
                    staleTags.size(), savedController.getName());
            }
        }

        // Контроллеры, которых больше нет в YAML — удаляем вместе с их тегами
        List<ControllerEntity> staleControllers = controllerRepository.findAll().stream()
            .filter(c -> !yamlControllerNames.contains(c.getName()))
            .collect(Collectors.toList());
        for (ControllerEntity stale : staleControllers) {
            List<TagEntity> tags = tagRepository.findByControllerId(stale.getId());
            if (!tags.isEmpty()) {
                tagRepository.deleteAll(tags);
                deleted += tags.size();
            }
            controllerRepository.delete(stale);
            log.info("Deleted stale controller: {}", stale.getName());
        }

        log.info("Sync complete: {} tags created, {} updated, {} deleted; {} controllers in YAML",
            created, updated, deleted, yamlControllerNames.size());
    }

    /**
     * Перечитывает включённые контроллеры и теги из БД в кэши (по id, по nodeId и
     * предгруппировку по контроллеру). Предгруппировка снимает stream().filter() по
     * всем ~2471 тегам с КАЖДОГО цикла опроса.
     */
    @Transactional(readOnly = true)
    public void loadConfiguration() {
        log.info("Loading configuration from database...");

        List<ControllerEntity> controllers = controllerRepository.findByEnabledTrue();
        controllerCache = controllers.stream()
            .collect(Collectors.toMap(ControllerEntity::getId, c -> c));

        List<TagEntity> tags = tagRepository.findByEnabledTrue();

        // Проставляем тегам ЗАГРУЖЕННЫЙ контроллер из controllerCache вместо ленивого
        // прокси (findByEnabledTrue его не подгружает). Иначе доступ к полям контроллера
        // (endpoint) ВНЕ этой @Transactional-сессии — напр. в CommandService.writePac —
        // падает LazyInitializationException. getId() на прокси безопасен.
        for (TagEntity t : tags) {
            ControllerEntity ctrl = t.getController();
            if (ctrl != null) {
                ControllerEntity loaded = controllerCache.get(ctrl.getId());
                if (loaded != null) t.setController(loaded);
            }
        }

        tagCache = tags.stream()
            .collect(Collectors.toMap(TagEntity::getId, t -> t));

        tagByNodeIdCache = tags.stream()
            .filter(t -> t.getNodeId() != null)
            .collect(Collectors.toMap(TagEntity::getNodeId, t -> t));

        // Предгруппировка активных тегов по контроллеру (один раз, а не каждый цикл).
        tagsByControllerCache = tags.stream()
            .filter(TagEntity::isEnabled)
            .filter(t -> t.getController() != null && t.getController().getId() != null)
            .collect(Collectors.groupingBy(t -> t.getController().getId()));

        log.info("Configuration loaded: {} controllers, {} tags",
            controllers.size(), tags.size());
    }

    /** Все включённые контроллеры из кэша (копия — вызывающий не портит кэш). */
    public List<ControllerEntity> getAllControllers() {
        return List.copyOf(controllerCache.values());
    }

    /** Все активные теги из кэша — для загрузки конфигурации опроса. */
    public List<TagEntity> getAllActiveTags() {
        return tagCache.values().stream()
            .filter(TagEntity::isEnabled)
            .collect(Collectors.toList());
    }

    /** Активные теги одного контроллера из предгруппированного кэша (горячий путь опроса). */
    public List<TagEntity> getTagsForController(Long controllerId) {
        // Из предгруппированного кэша — без stream/filter на каждый цикл опроса.
        return tagsByControllerCache.getOrDefault(controllerId, List.of());
    }

    /** Тег по строке nodeId (адресация OPC UA-узла); null, если такого нет. */
    public TagEntity getTagByNodeId(String nodeId) {
        return tagByNodeIdCache.get(nodeId);
    }

    /** Сводка конфигурации (число контроллеров/тегов + список) — для REST-мониторинга. */
    public Map<String, Object> getStats() {
        return Map.of(
            "controllers", controllerCache.size(),
            "tags", tagCache.size(),
            "controllers_list", controllerCache.values().stream()
                .map(c -> Map.of(
                    "id", c.getId(),
                    "name", c.getName(),
                    "endpoint", c.getEndpoint(),
                    "tags_count", getTagsForController(c.getId()).size()
                ))
                .collect(Collectors.toList())
        );
    }
}