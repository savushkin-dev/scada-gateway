package com.scada.gateway.opcua;

import com.scada.gateway.model.entity.ControllerEntity;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.model.entity.TelemetryEntity;
import com.scada.gateway.model.TagProtocols;
import com.scada.gateway.service.ConfigurationService;
import com.scada.gateway.telemetry.TelemetryProcessor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.scada.gateway.command.OpcUaClientRegistry;
import com.scada.gateway.command.TagCatalog;
import com.scada.gateway.modbus.ModbusBatchReader;
import com.scada.gateway.modbus.ModbusClientService;
import com.scada.gateway.modbus.ModbusEndpoint;
import com.scada.gateway.service.EventLogService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadResponse;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Ядро шлюза: жизненный цикл соединений с контроллерами и опрос («connection/polling
 * engine»).
 *
 * <p>Поднимает и держит соединения OPC UA (Milo) и Modbus, ведёт супервизор
 * авто-переподключения (superviseConnections + токен поколения опроса против flapping)
 * и два цикла опроса: OPC UA — батч-чтением одним read на контроллер; Modbus — через
 * {@link com.scada.gateway.modbus.ModbusBatchReader}. Снятые значения отдаёт в
 * {@link com.scada.gateway.telemetry.TelemetryProcessor}, отдаёт метрики связи.
 *
 * <p>Реализует порты {@link com.scada.gateway.command.TagCatalog} и
 * {@link com.scada.gateway.command.OpcUaClientRegistry} (владелец живых карт тегов и
 * OPC UA-клиентов) для CommandService. Остальные обязанности вынесены в отдельные
 * классы: конвертация — ValueCodec, команды — CommandService, алармы — AlarmEvaluator,
 * телеметрия — TelemetryProcessor.
 */
@Service
public class OpcUaClientServiceDB implements TagCatalog, OpcUaClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(OpcUaClientServiceDB.class);

    private final ConfigurationService configurationService;
    private final ModbusClientService modbusClientService;
    private final ModbusBatchReader modbusBatchReader;
    private final com.scada.gateway.pac.PacClientService pacClientService;
    private final EventLogService eventLogService;
    private final TelemetryProcessor telemetryProcessor;

    private final Map<Long, OpcUaClient> opcClients = new ConcurrentHashMap<>();
    private final Map<Long, ExecutorService> executors = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> runningStatus = new ConcurrentHashMap<>();
    /** «Поколение» опроса на контроллер. При переподключении растёт → старый поток
     *  опроса перестаёт быть текущим и выходит, даже если runningStatus снова true.
     *  Без этого старый и новый потоки опрашивали один контроллер и «дёргали»
     *  состояние связи (flapping DISCONNECTED/CONNECTED). */
    private final Map<Long, Long> pollGeneration = new ConcurrentHashMap<>();

    // --- Состояние связи с контроллерами (для авто-переподключения и журнала) ---
    /** true = связь есть. Переходы журналируются и шлются событием в Kafka. */
    private final Map<Long, Boolean> connected = new ConcurrentHashMap<>();
    /** Момент последнего успешного чтения (ms) — для детекта «тихой» смерти OPC UA. */
    private final Map<Long, Long> lastGoodReadMs = new ConcurrentHashMap<>();
    /** Идёт ли попытка переподключения (защита от гонки супервизора). */
    private final Map<Long, Boolean> reconnecting = new ConcurrentHashMap<>();
    /** Контроллер по id — чтобы супервизор знал, кого поднимать. */
    private final Map<Long, ControllerEntity> controllerById = new ConcurrentHashMap<>();
    /** Считаем ошибки чтения подряд — чтобы не спамить лог (троттлинг). */
    private final Map<Long, Integer> readErrorStreak = new ConcurrentHashMap<>();
    /** Считаем НЕудачные попытки ПОДКЛЮЧЕНИЯ подряд — чтобы фиксировать «не могу
     *  подключиться» в event_log (иначе из БД не отличить «ещё не успел» от «висит давно»). */
    private final Map<Long, Integer> connectFailStreak = new ConcurrentHashMap<>();

    private final Map<Long, TagEntity> tagCache = new ConcurrentHashMap<>();
    /** Тот же набор тегов, но по имени канала (= полный путь узла) — адресация команд извне. */
    private final Map<String, TagEntity> tagsByName = new ConcurrentHashMap<>();

    // Разобранный NodeId по его строке — чтобы не парсить строку на каждое чтение.
    private final Map<String, NodeId> nodeIdCache = new ConcurrentHashMap<>();

    // --- Флаги горячего пути (application.yaml → gateway.*) ---
    /** Писать ли каждую точку в локальную БД шлюза. Значения в Kafka идут всегда. */
    @Value("${gateway.persist-telemetry:true}")
    private boolean persistTelemetry;
    /**
     * Таймаут блокирующих OPC UA-вызовов (discovery / connect / write). БЕЗ него
     * зависший handshake вешает вызывающий поток НАВСЕГДА — поток супервизора при
     * подключении и поток консьюмера при записи. См. gateway-opcua-write-issue.md.
     */
    @Value("${gateway.opcua-op-timeout-ms:5000}")
    private long opcuaOpTimeoutMs;

    private final com.scada.gateway.kafka.producer.EventProducer eventProducer;

    public OpcUaClientServiceDB(ConfigurationService configurationService,
                                ModbusClientService modbusClientService,
                                ModbusBatchReader modbusBatchReader,
                                com.scada.gateway.pac.PacClientService pacClientService,
                                EventLogService eventLogService,
                                com.scada.gateway.kafka.producer.EventProducer eventProducer,
                                TelemetryProcessor telemetryProcessor,
                                MeterRegistry meterRegistry) {
        this.configurationService = configurationService;
        this.modbusClientService = modbusClientService;
        this.modbusBatchReader = modbusBatchReader;
        this.pacClientService = pacClientService;
        this.eventLogService = eventLogService;
        this.eventProducer = eventProducer;
        this.telemetryProcessor = telemetryProcessor;
        // Метрики здоровья связи: читаются live из карт при каждом scrape /actuator/prometheus.
        Gauge.builder("scada.controllers.connected", connected,
                        m -> m.values().stream().filter(Boolean.TRUE::equals).count())
                .description("Контроллеров на связи").register(meterRegistry);
        Gauge.builder("scada.controllers.total", controllerById, java.util.Map::size)
                .description("Всего сконфигурировано контроллеров").register(meterRegistry);
    }

    /**
     * Отметить УСПЕШНОЕ чтение с контроллера. При восстановлении связи (BAD→GOOD)
     * пишем событие в журнал и Kafka, сбрасываем счётчик ошибок.
     */
    private void markControllerUp(ControllerEntity controller) {
        Long id = controller.getId();
        lastGoodReadMs.put(id, System.currentTimeMillis());
        readErrorStreak.put(id, 0);
        Boolean was = connected.put(id, true);
        if (was == null || !was) {
            boolean restored = Boolean.FALSE.equals(was); // was==null → первичное подключение
            String note = restored ? "link restored" : "initial connect";
            log.info("🟢 {}: связь {}", controller.getName(), restored ? "восстановлена" : "установлена");
            eventLogService.logConnection(controller, "CONNECTED", note);
            eventProducer.sendEvent("CONNECTION", "OpcUaClient", "INFO",
                    "Связь с " + controller.getName() + (restored ? " восстановлена" : " установлена"),
                    Map.of("controller", controller.getName(), "state", "CONNECTED"));
        }
    }

    /**
     * Отметить ОШИБКУ связи. Первый переход GOOD→BAD журналируем и шлём событие;
     * дальше только троттлинг лога (раз в N ошибок), чтобы не спамить.
     */
    private void markControllerDown(ControllerEntity controller, String reason) {
        Long id = controller.getId();
        int streak = readErrorStreak.merge(id, 1, Integer::sum);
        Boolean was = connected.put(id, false);
        if (was == null || was) {
            log.warn("🔴 Потеряна связь: {} ({})", controller.getName(), reason);
            eventLogService.logConnection(controller, "DISCONNECTED", reason);
            eventProducer.sendEvent("CONNECTION", "OpcUaClient", "WARNING",
                    "Потеряна связь с " + controller.getName() + ": " + reason,
                    Map.of("controller", controller.getName(), "state", "DISCONNECTED", "reason", reason));
        } else if (streak % 30 == 0) {
            log.warn("🔴 {} всё ещё недоступен ({} ошибок подряд)", controller.getName(), streak);
        }
    }

    @PostConstruct
    public void init() {
        log.info("Initializing Unified Protocol Client Service");
        eventLogService.logSystem("INFO", "SCADA Gateway starting up", Map.of("component", "OpcUaClientService"));

        loadConfiguration();
        log.info("⚙ Флаг горячего пути: persist-telemetry={}", persistTelemetry);

        List<ControllerEntity> controllers = configurationService.getAllControllers();

        for (ControllerEntity controller : controllers) {
            if (controller.isEnabled()) {
                controllerById.put(controller.getId(), controller);
                connectToController(controller);
            }
        }
    }

    /**
     * СУПЕРВИЗОР СВЯЗИ (авто-переподключение). Каждые 10 c проверяет каждый
     * контроллер и переподнимает OPC UA, если клиент мёртв или давно нет удачных
     * чтений («тихая» смерть сессии). Modbus само-восстанавливается лениво в
     * ModbusClientService, поэтому его здесь только не трогаем. Метод работает и
     * когда контроллер не поднялся при старте (симулятор запустился позже).
     */
    @Scheduled(fixedDelayString = "${gateway.supervise-interval-ms:10000}", initialDelay = 20000)
    public void superviseConnections() {
        long staleMs = 30_000; // нет удачных чтений 30 c → считаем связь мёртвой
        for (ControllerEntity controller : controllerById.values()) {
            if (!controller.isEnabled()) continue;
            String endpoint = controller.getEndpoint();
            boolean isOpc = endpoint != null && endpoint.toLowerCase().contains("opc.tcp");
            if (!isOpc) continue; // Modbus лечится сам

            Long id = controller.getId();
            OpcUaClient client = opcClients.get(id);
            long last = lastGoodReadMs.getOrDefault(id, 0L);
            boolean stale = System.currentTimeMillis() - last > staleMs;
            boolean dead = client == null || stale;

            if (dead && !Boolean.TRUE.equals(reconnecting.get(id))) {
                reconnecting.put(id, true);
                try {
                    log.info("🔄 Супервизор: переподключаю OPC UA {} (client={}, stale={})",
                            controller.getName(), client != null, stale);
                    // «Тихая» смерть OPC UA: чтения зависают, а не бросают исключение,
                    // поэтому poll-цикл мог не вызвать markControllerDown. Фиксируем обрыв
                    // здесь (только если связь ЧИСЛИЛАСЬ живой) — иначе не будет ни события
                    // DISCONNECTED, ни последующего «восстановлена».
                    if (Boolean.TRUE.equals(connected.get(id))) {
                        markControllerDown(controller,
                                "супервизор: нет удачных чтений > " + (staleMs / 1000) + " c");
                    }
                    reconnectOpcUa(controller);
                } catch (Exception e) {
                    log.error("Супервизор: переподключение {} не удалось: {}", controller.getName(), e.getMessage());
                } finally {
                    reconnecting.put(id, false);
                }
            }
        }
    }

    /** Периодическая сводка здоровья в лог (сколько контроллеров на связи). */
    @Scheduled(fixedDelayString = "${gateway.health-log-interval-ms:60000}", initialDelay = 30000)
    public void logHealthSummary() {
        int total = controllerById.size();
        long up = connected.values().stream().filter(Boolean.TRUE::equals).count();
        StringBuilder sb = new StringBuilder();
        for (ControllerEntity c : controllerById.values()) {
            boolean ok = Boolean.TRUE.equals(connected.get(c.getId()));
            sb.append(sb.length() == 0 ? "" : ", ")
              .append(ok ? "🟢 " : "🔴 ").append(c.getName());
        }
        log.info("📋 Здоровье связи: {}/{} на связи [{}]", up, total, sb);
    }

    /** Остановить старый опрос/клиент и заново подключиться к OPC UA-контроллеру. */
    private void reconnectOpcUa(ControllerEntity controller) {
        Long id = controller.getId();
        // 1) гасим старый поток опроса
        runningStatus.put(id, false);
        ExecutorService old = executors.remove(id);
        if (old != null) old.shutdownNow();
        // 2) закрываем старый клиент
        OpcUaClient client = opcClients.remove(id);
        if (client != null) {
            // Таймаут и на disconnect: зависшее рассоединение иначе повесит супервизор.
            try { client.disconnect().get(opcuaOpTimeoutMs, TimeUnit.MILLISECONDS); } catch (Exception ignore) {}
        }
        // 3) подключаемся заново тихо (connectOpcUaController сам запустит опрос)
        connectOpcUaController(controller, false);
    }

    private void loadConfiguration() {
        var tags = configurationService.getAllActiveTags();
        tags.forEach(tag -> {
            tagCache.put(tag.getId(), tag);
            if (tag.getName() != null) {
                tagsByName.put(tag.getName(), tag);
            }
        });
        log.info("Loaded {} tags", tagCache.size());
        eventLogService.logSystem("INFO", "Configuration loaded", Map.of("tags", tagCache.size(), "controllers", configurationService.getAllControllers().size()));
    }

    private void connectToController(ControllerEntity controller) {
        String endpoint = controller.getEndpoint();
        
        if (endpoint != null && endpoint.toLowerCase().contains("modbus")) {
            log.info("📡 Modbus controller: {} at {}", controller.getName(), endpoint);
            startPollingForController(controller);
        } else if (endpoint != null && endpoint.toLowerCase().contains("pac://")) {
            log.info("📡 PAC controller: {} at {}", controller.getName(), endpoint);
            startPollingForController(controller);
        } else if (endpoint != null && endpoint.toLowerCase().contains("opc.tcp")) {
            connectOpcUaController(controller);
        } else {
            log.warn("Unknown protocol for controller: {}", controller.getName());
            eventLogService.logSystem("WARNING", "Unknown protocol for controller: " + controller.getName(), Map.of("controller", controller.getName(), "endpoint", endpoint));
        }
    }

    private void connectOpcUaController(ControllerEntity controller) {
        connectOpcUaController(controller, true);
    }

    /**
     * Подключение к OPC UA-контроллеру. verbose=true — первичное подключение при
     * старте (пишем CONNECTING в журнал, полный лог). verbose=false — супервизорное
     * переподключение (тихо: без CONNECTING-события и без стектрейса каждые 10 c при
     * длительном обрыве). Событие CONNECTED порождает markControllerUp по первому
     * удачному чтению — единый владелец перехода «связь есть», без дублей.
     */
    private void connectOpcUaController(ControllerEntity controller, boolean verbose) {
        OpcUaClient client = null;
        try {
            if (verbose) {
                log.info("🔌 Connecting OPC UA: {} at {}", controller.getName(), controller.getEndpoint());
                eventLogService.logConnection(controller, "CONNECTING", null);
            } else {
                log.debug("🔄 Reconnecting OPC UA: {}", controller.getName());
            }

            // Таймаут ОБЯЗАТЕЛЕН: без него зависший OPC UA-handshake (TCP отвечает, а
            // сам протокол — нет) вешает этот поток навсегда (gateway-opcua-write-issue.md).
            List<EndpointDescription> endpoints =
                    DiscoveryClient.getEndpoints(controller.getEndpoint())
                            .get(opcuaOpTimeoutMs, TimeUnit.MILLISECONDS);

            if (endpoints.isEmpty()) {
                // Пустой список = сервер недоступен/не ответил (getEndpoints НЕ бросает при
                // отказе — возвращает пусто). Раньше тут был тихий return, и провал
                // переподключения шёл МИМО учёта (CONNECT_FAILED не писался). Бросаем —
                // пусть неудача попадёт в единый catch (счётчик + событие в event_log).
                throw new IllegalStateException("OPC UA discovery вернул пустой список endpoint (сервер недоступен?)");
            }

            EndpointDescription endpoint = endpoints.get(0);

            OpcUaClientConfig config = OpcUaClientConfig.builder()
                    .setApplicationName(LocalizedText.english("SCADA Gateway"))
                    .setApplicationUri("urn:scada:gateway")
                    .setEndpoint(endpoint)
                    .build();

            client = OpcUaClient.create(config);
            client.connect().get(opcuaOpTimeoutMs, TimeUnit.MILLISECONDS);

            opcClients.put(controller.getId(), client);
            runningStatus.put(controller.getId(), true);
            connectFailStreak.remove(controller.getId()); // успех — сбрасываем счётчик неудач
            // Отмечаем момент как «свежий», чтобы супервизор не счёл связь stale в
            // окне между установкой сокета и первым удачным чтением (иначе — цикл
            // переподключений, рвущий только что поднятую сессию).
            lastGoodReadMs.put(controller.getId(), System.currentTimeMillis());

            startPollingForController(controller);

            log.info("✅ OPC UA socket up: {} — опрос запущен", controller.getName());
            // CONNECTED-событие эмитит markControllerUp по первому удачному чтению.

        } catch (Exception e) {
            // Таймаут/ошибка: клиент мог подняться частично — закрываем, чтобы не течь.
            if (client != null) {
                try { client.disconnect().get(opcuaOpTimeoutMs, TimeUnit.MILLISECONDS); } catch (Exception ignore) {}
            }
            String msg = e instanceof TimeoutException
                    ? "таймаут " + opcuaOpTimeoutMs + " мс (handshake завис)"
                    : (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            // Видимость в БД: первую неудачу и далее раз в ~минуту фиксируем в event_log —
            // иначе из журнала не отличить «ещё подключается» от «висит уже давно».
            int fails = connectFailStreak.merge(controller.getId(), 1, Integer::sum);
            if (fails == 1 || fails % 6 == 0) {
                eventLogService.logConnection(controller, "CONNECT_FAILED",
                        "попыток подряд: " + fails + (msg != null ? " — " + msg : ""));
            }
            if (verbose) {
                log.warn("❌ OPC UA connect failed for {}: {} (попытка {})", controller.getName(), msg, fails);
            } else {
                log.debug("OPC UA reconnect attempt failed for {}: {} (попытка {})", controller.getName(), msg, fails);
            }
        }
    }

    /** Текущий ли это поток опроса своего поколения (защита от «двух опросов»). */
    private boolean isCurrentPoll(Long id, long gen) {
        return runningStatus.getOrDefault(id, false) && gen == pollGeneration.getOrDefault(id, gen);
    }

    private void startPollingForController(ControllerEntity controller) {
        Long id = controller.getId();
        // Новое поколение опроса — этим гасим любой предыдущий поток по этому контроллеру.
        long gen = pollGeneration.merge(id, 1L, Long::sum);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        // put возвращает прежний пул: если по этому id уже крутился executor — гасим его,
        // не полагаясь на вызывающего. Иначе newSingleThreadExecutor оставил бы висящий
        // НЕ-daemon поток навсегда (утечка потока + JVM не выключится чисто). reconnectOpcUa
        // и так гасит старый пул до нас — это защита от будущих путей реконнекта (напр. Modbus).
        ExecutorService previous = executors.put(id, executor);
        if (previous != null) previous.shutdownNow();
        runningStatus.put(id, true);

        String endpoint = controller.getEndpoint();
        boolean isModbus = endpoint != null && endpoint.toLowerCase().contains("modbus");
        boolean isPac = endpoint != null && endpoint.toLowerCase().contains("pac://");

        if (isModbus) {
            startModbusPolling(controller, executor, gen);
        } else if (isPac) {
            startPacPolling(controller, executor, gen);
        } else {
            startOpcuaPolling(controller, executor, gen);
        }
    }

    private void startOpcuaPolling(ControllerEntity controller, ExecutorService executor, long gen) {
        final Long cid = controller.getId();
        executor.submit(() -> {
            // ИНВАРИАНТ: клиента берём ОДИН раз. opcClients меняется только вместе с
            // перезапуском опроса (reconnectOpcUa: bump поколения + shutdownNow ЭТОГО
            // потока), поэтому к моменту подмены поток уже мёртв (isCurrentPoll=false) и
            // до нового клиента не доберётся. writeOpcUa берёт клиента из карты заново —
            // это ок. НЕ менять opcClients в обход reconnectOpcUa, иначе чтение/запись разъедутся.
            OpcUaClient client = opcClients.get(cid);

            while (isCurrentPoll(cid, gen)) {
                try {
                    List<TagEntity> allTags = configurationService.getTagsForController(cid);

                    // Собираем ОДИН запрос чтения на все узлы контроллера: opcTags[i] ↔ reads[i].
                    // Раньше читали по одному узлу (N сетевых round-trip'ов за цикл) — теперь один
                    // read() на все теги. Это главный выигрыш по задержке для OPC UA.
                    List<TagEntity> opcTags = new ArrayList<>();
                    List<ReadValueId> reads = new ArrayList<>();
                    long cycleDelay = 1000L;
                    for (TagEntity tag : allTags) {
                        if (!tag.isEnabled() || !TagProtocols.isOpcUaTag(tag)) continue;
                        NodeId nodeId = nodeIdCache.computeIfAbsent(tag.getNodeId(), NodeId::parse);
                        opcTags.add(tag);
                        reads.add(new ReadValueId(nodeId, AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE));
                        cycleDelay = Math.min(cycleDelay, Math.max(tag.getPollingRate(), 100L));
                    }

                    if (opcTags.isEmpty()) {
                        Thread.sleep(cycleDelay);
                        continue;
                    }

                    int goodReads = 0, badReads = 0;
                    String lastErr = null;
                    // Буфер точек цикла: одна saveAll в конце = одна транзакция вместо N коммитов.
                    List<TelemetryEntity> batch = persistTelemetry ? new ArrayList<>(opcTags.size()) : null;

                    try {
                        // Таймаут на чтении — как на connect/write: «тихая» смерть OPC UA (TCP
                        // жив, протокол завис) вешает .get() навсегда. Без него поток висит,
                        // пока супервизор (30 c) не сделает shutdownNow; с таймаутом цикл сам
                        // ловит зависание за opcuaOpTimeoutMs и метит его BAD.
                        ReadResponse resp = client.read(0.0, TimestampsToReturn.Both, reads)
                                .get(opcuaOpTimeoutMs, TimeUnit.MILLISECONDS);
                        DataValue[] results = resp.getResults();
                        for (int i = 0; i < opcTags.size(); i++) {
                            TagEntity tag = opcTags.get(i);
                            DataValue dv = (results != null && i < results.length) ? results[i] : null;
                            Object val = dv != null ? ValueCodec.extractValue(dv.getValue()) : null;
                            String quality = (dv != null && dv.getStatusCode().isGood()) ? "GOOD" : "BAD";
                            // A2: метка времени = момент снятия значения сервером (sourceTime),
                            // а не момент отправки. Фолбэк serverTime → now.
                            Instant ts = sourceTimeOf(dv);

                            if (tag.isRecordDevice() && "GOOD".equals(quality)) {
                                telemetryProcessor.processDeviceRecord(tag, val, quality, ts, controller);
                            } else {
                                telemetryProcessor.processTagValue(tag, val, quality, ts, batch);
                            }
                        }
                        // Запрос прошёл → связь есть (пер-узловые BAD-статусы связь не роняют).
                        goodReads = opcTags.size();
                    } catch (Exception e) {
                        // Обрыв на уровне запроса: весь цикл — BAD (супервизор переподнимет).
                        // Таймаут метим отдельно — это «тихое» зависание, а не обычная ошибка.
                        lastErr = e instanceof TimeoutException
                                ? "таймаут чтения " + opcuaOpTimeoutMs + " мс (сервер завис)"
                                : e.getMessage();
                        badReads = opcTags.size();
                        Instant ts = Instant.now();
                        for (TagEntity tag : opcTags) {
                            telemetryProcessor.processTagValue(tag, null, "BAD", ts, batch);
                        }
                    }

                    if (batch != null && !batch.isEmpty()) telemetryProcessor.flushTelemetry(batch);

                    // Только текущее поколение опроса правит состояние связи (иначе flapping
                    // от «умирающего» старого потока: DISCONNECTED/CONNECTED вперемешку).
                    if (isCurrentPoll(cid, gen)) {
                        if (goodReads > 0) markControllerUp(controller);
                        else if (badReads > 0) markControllerDown(controller,
                                "OPC UA reads failing" + (lastErr != null ? ": " + lastErr : ""));
                    }

                    Thread.sleep(cycleDelay);

                } catch (InterruptedException e) {
                    log.debug("Polling interrupted for {}", controller.getName());
                    break;
                } catch (Exception e) {
                    log.error("Polling error for {}: {}", controller.getName(), e.getMessage());
                    eventLogService.logError("OpcUaClient", "Polling error for " + controller.getName(), e, null, controller);
                }
            }
        });
    }

    private void startModbusPolling(ControllerEntity controller, ExecutorService executor, long gen) {
        String host = ModbusEndpoint.host(controller.getEndpoint());
        int port = ModbusEndpoint.port(controller.getEndpoint(), 502);
        final Long cid = controller.getId();

        eventLogService.logConnection(controller, "CONNECTING", "Modbus endpoint: " + controller.getEndpoint());

        executor.submit(() -> {
            while (isCurrentPoll(cid, gen)) {
                try {
                    List<TagEntity> tags = configurationService.getTagsForController(cid);
                    long cycleDelay = 1000L;
                    int goodReads = 0, badReads = 0;
                    String lastErr = null;
                    // Буфер точек цикла: одна saveAll в конце = одна транзакция вместо N коммитов.
                    List<TelemetryEntity> batch = persistTelemetry ? new ArrayList<>() : null;

                    // Батч-чтение: собираем enabled Modbus-теги и читаем блоками регистров
                    // (~30 FC03 вместо 1877 пер-теговых запросов). См. ModbusBatchReader.
                    List<TagEntity> mbTags = new ArrayList<>();
                    for (TagEntity tag : tags) {
                        if (!tag.isEnabled() || !TagProtocols.isModbusTag(tag)) continue;
                        mbTags.add(tag);
                        cycleDelay = Math.min(cycleDelay, Math.max(tag.getPollingRate(), 100L));
                    }
                    // unitId общий для контроллера (в конфиге один на всех тегах).
                    int unitId = mbTags.isEmpty() ? 1 : mbTags.get(0).getModbusUnitId();
                    for (ModbusBatchReader.Reading r : modbusBatchReader.read(host, port, unitId, mbTags)) {
                        Object value = r.value();
                        if (value != null) goodReads++; else badReads++;
                        if (value == null && lastErr == null) lastErr = "Modbus block read failed";
                        // A2: у Modbus источника времени в протоколе нет — момент завершения чтения.
                        telemetryProcessor.processTagValue(r.tag(), value,
                                value != null ? "GOOD" : "BAD", Instant.now(), batch);
                    }

                    if (batch != null && !batch.isEmpty()) telemetryProcessor.flushTelemetry(batch);

                    // Оценка связи по итогам цикла (Modbus само-восстанавливается лениво).
                    if (isCurrentPoll(cid, gen)) {
                        if (goodReads > 0) markControllerUp(controller);
                        else if (badReads > 0) markControllerDown(controller,
                                "Modbus reads failing" + (lastErr != null ? ": " + lastErr : ""));
                    }

                    Thread.sleep(cycleDelay);

                } catch (InterruptedException e) {
                    log.debug("Modbus polling interrupted for {}", controller.getName());
                    break;
                } catch (Exception e) {
                    log.error("Modbus polling error for {}: {}", controller.getName(), e.getMessage());
                    eventLogService.logError("ModbusClient", "Polling error for " + controller.getName(), e, null, controller);
                }
            }
        });
    }

    /**
     * Цикл опроса PAC-контроллера (протокол driver-master). Как Modbus: свой клиент
     * (не Milo), соединение ленивое в {@link com.scada.gateway.pac.PacClientService}.
     * Особенность протокола — один GET_DEVICES_STATES снимает состояние ВСЕХ устройств
     * за раз, поэтому за цикл ровно один сетевой запрос, а значения тегов берутся из
     * Lua-таблицы tags по channelId.
     */
    private void startPacPolling(ControllerEntity controller, ExecutorService executor, long gen) {
        String host = com.scada.gateway.pac.PacEndpoint.host(controller.getEndpoint());
        int port = com.scada.gateway.pac.PacEndpoint.port(controller.getEndpoint(), 10000);
        final Long cid = controller.getId();

        eventLogService.logConnection(controller, "CONNECTING", "PAC endpoint: " + controller.getEndpoint());

        executor.submit(() -> {
            while (isCurrentPoll(cid, gen)) {
                try {
                    List<TagEntity> tags = configurationService.getTagsForController(cid);
                    long cycleDelay = 1000L;
                    int goodReads = 0, badReads = 0;
                    String lastErr = null;
                    // Буфер точек цикла: одна saveAll в конце = одна транзакция вместо N коммитов.
                    List<TelemetryEntity> batch = persistTelemetry ? new ArrayList<>() : null;

                    // Собираем enabled PAC-теги контроллера (значения все придут одним снимком).
                    List<TagEntity> pacTags = new ArrayList<>();
                    for (TagEntity tag : tags) {
                        if (!tag.isEnabled() || !TagProtocols.isPacTag(tag)) continue;
                        pacTags.add(tag);
                        cycleDelay = Math.min(cycleDelay, Math.max(tag.getPollingRate(), 100L));
                    }

                    for (com.scada.gateway.pac.PacClientService.Reading r :
                            pacClientService.read(host, port, pacTags)) {
                        Object value = r.value();
                        if (value != null) goodReads++; else badReads++;
                        if (value == null && lastErr == null) lastErr = "PAC read failed";
                        // A2: у PAC источника времени в протоколе нет — момент завершения чтения.
                        telemetryProcessor.processTagValue(r.tag(), value,
                                value != null ? "GOOD" : "BAD", Instant.now(), batch);
                    }

                    if (batch != null && !batch.isEmpty()) telemetryProcessor.flushTelemetry(batch);

                    if (isCurrentPoll(cid, gen)) {
                        if (goodReads > 0) markControllerUp(controller);
                        else if (badReads > 0) markControllerDown(controller,
                                "PAC reads failing" + (lastErr != null ? ": " + lastErr : ""));
                    }

                    Thread.sleep(cycleDelay);

                } catch (InterruptedException e) {
                    log.debug("PAC polling interrupted for {}", controller.getName());
                    break;
                } catch (Exception e) {
                    log.error("PAC polling error for {}: {}", controller.getName(), e.getMessage());
                    eventLogService.logError("PacClient", "Polling error for " + controller.getName(), e, null, controller);
                }
            }
        });
    }

    // --- Порты для CommandService (DIP): god-класс — владелец живых карт (кэш тегов,
    // OPC UA-клиенты), поэтому отдаёт их только на ЧТЕНИЕ. Сама логика записи
    // (writeTag/writeOpcUa/writePac) переехала в com.scada.gateway.command.CommandService.
    @Override
    public TagEntity byId(Long id) {
        return id == null ? null : tagCache.get(id);
    }

    @Override
    public TagEntity byName(String name) {
        return name == null ? null : tagsByName.get(name);
    }

    @Override
    public OpcUaClient forController(Long controllerId) {
        return controllerId == null ? null : opcClients.get(controllerId);
    }

    /** Момент снятия значения: sourceTime сервера, иначе serverTime, иначе now (A2). */
    private Instant sourceTimeOf(DataValue dv) {
        if (dv != null) {
            DateTime src = dv.getSourceTime();
            if (src != null && !src.isNull()) return src.getJavaInstant();
            DateTime srv = dv.getServerTime();
            if (srv != null && !srv.isNull()) return srv.getJavaInstant();
        }
        return Instant.now();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down all connections...");
        eventLogService.logSystem("INFO", "SCADA Gateway shutting down", Map.of("component", "OpcUaClientService"));

        for (Long id : runningStatus.keySet()) {
            runningStatus.put(id, false);
        }

        for (ExecutorService executor : executors.values()) {
            if (executor != null) {
                executor.shutdown();
            }
        }

        for (Map.Entry<Long, OpcUaClient> entry : opcClients.entrySet()) {
            if (entry.getValue() != null) {
                try {
                    // Таймаут и на shutdown: иначе зависший disconnect повесит выключение JVM.
                    entry.getValue().disconnect().get(opcuaOpTimeoutMs, TimeUnit.MILLISECONDS);
                    log.info("Disconnected OPC UA client for controller {}", entry.getKey());
                } catch (Exception ignored) {}
            }
        }

        modbusClientService.disconnectAll();
        pacClientService.disconnectAll();
        log.info("Shutdown complete");
    }
}
