package com.scada.gateway.modbus;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.io.ModbusTCPTransaction;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersRequest;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersResponse;
import com.ghgande.j2mod.modbus.net.TCPMasterConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Низкоуровневый клиент Modbus TCP (j2mod): пул соединений по host:port, троттлинг
 * логов обрыва, backoff на переподключение (анти-hot-loop). Чтение:
 * readFloat/readInt16/readBoolean (по одному тегу) и readHoldingRegisters (блок
 * регистров одной FC03 — основа батч-чтения, см. ModbusBatchReader). Записи нет:
 * Modbus-контроллер только на чтение. Явный таймаут на connect/чтение.
 */
@Service
public class ModbusClientService {

    private static final Logger log = LoggerFactory.getLogger(ModbusClientService.class);

    /**
     * Явный таймаут (мс) на connect И на чтение транзакции j2mod. У j2mod есть неявный
     * дефолт 3000 мс, но полагаться на дефолт библиотеки — хрупко: он невидим и
     * ненастраиваем. Задаём явно и делаем конфигурируемым (как gateway.opcua-op-timeout-ms).
     */
    @Value("${gateway.modbus-op-timeout-ms:3000}")
    private int modbusTimeoutMs;

    private final ConcurrentHashMap<String, TCPMasterConnection> connections = new ConcurrentHashMap<>();

    /** Число ошибок подряд по endpoint — для журнала (сколько чтений провалилось). */
    private final ConcurrentHashMap<String, Integer> failStreak = new ConcurrentHashMap<>();
    /** Момент последнего WARN об обрыве — троттлинг по ВРЕМЕНИ (не по числу ошибок). */
    private final ConcurrentHashMap<String, Long> lastFailLogMs = new ConcurrentHashMap<>();
    /** Не раньше какого момента пробовать реальный connect (backoff анти-hot-loop). */
    private final ConcurrentHashMap<String, Long> nextConnectMs = new ConcurrentHashMap<>();

    private static final long FAIL_LOG_INTERVAL_MS = 30_000; // ≤ 1 WARN / 30 c на endpoint
    private static final long CONNECT_BACKOFF_MS   = 2_000;  // реальный connect не чаще 1/2 c

    /** Обрыв: первый раз WARN сразу, дальше не чаще раза в 30 c, иначе тихо (debug). */
    private void reportFailure(String key, String msg) {
        int s = failStreak.merge(key, 1, Integer::sum);
        long now = System.currentTimeMillis();
        long last = lastFailLogMs.getOrDefault(key, 0L);
        if (s == 1 || now - last >= FAIL_LOG_INTERVAL_MS) {
            lastFailLogMs.put(key, now);
            log.warn("🔴 Modbus {} недоступен ({} ошибок подряд): {}", key, s, msg);
        } else {
            log.debug("Modbus {} error #{}: {}", key, s, msg);
        }
    }

    /** Успешное чтение: если до этого были ошибки — фиксируем восстановление один раз. */
    private void reportOk(String key) {
        Integer prev = failStreak.put(key, 0);
        lastFailLogMs.remove(key);
        if (prev != null && prev > 0) {
            log.info("🟢 Modbus {} на связи (после {} ошибок подряд)", key, prev);
        }
    }

    /**
     * Чтение FLOAT (2 регистра, Holding Registers / FC03)
     * Использует little-endian порядок байт (как в Python)
     */
    public Float readFloat(String host, int port, int address, int unitId) {
        String key = host + ":" + port;
        TCPMasterConnection connection = getConnection(key, host, port);

        if (connection == null) {
            // Обрыв уже залогирован в getConnection (троттлинг); тут только тихо выходим.
            return null;
        }

        try {
            synchronized (connection) {
                if (!connection.isConnected()) {
                    connection.connect();
                }

                // Преобразуем 40001 → 0
                int modbusAddress = address - 40001;

                if (modbusAddress < 0) {
                    log.error("❌ Invalid Modbus address: {}", address);
                    return null;
                }

                ReadMultipleRegistersRequest request =
                        new ReadMultipleRegistersRequest(modbusAddress, 2);

                request.setUnitID(unitId);

                ModbusTCPTransaction transaction =
                        new ModbusTCPTransaction(connection);

                transaction.setRequest(request);
                transaction.execute();

                ReadMultipleRegistersResponse response =
                        (ReadMultipleRegistersResponse) transaction.getResponse();

                if (response != null && response.getWordCount() >= 2) {

                    int reg1 = response.getRegisterValue(0);
                    int reg2 = response.getRegisterValue(1);

                    // little-endian (как в Python struct.pack('<f', value))
                    int littleEndian = (reg2 << 16) | (reg1 & 0xFFFF);
                    float valueLE = Float.intBitsToFloat(littleEndian);

                    log.debug("📡 Modbus raw [{}]: reg1={}, reg2={}", address, reg1, reg2);
                    log.debug("📡 LE={}", valueLE);

                    reportOk(key);
                    return valueLE;
                } else {
                    log.debug("⚠️ Empty Modbus response for address {}", address);
                }
            }

        } catch (ModbusException e) {
            reportFailure(key, e.getMessage());
            resetConnection(key, connection);
        } catch (Exception e) {
            reportFailure(key, e.getMessage());
            resetConnection(key, connection);
        }

        return null;
    }

    /**
     * Чтение INT16 (1 регистр)
     */
    public Integer readInt16(String host, int port, int address, int unitId) {
        String key = host + ":" + port;
        TCPMasterConnection connection = getConnection(key, host, port);

        if (connection == null) {
            return null;
        }

        try {
            synchronized (connection) {
                if (!connection.isConnected()) {
                    connection.connect();
                }

                int modbusAddress = address - 40001;

                if (modbusAddress < 0) {
                    log.error("❌ Invalid Modbus address: {}", address);
                    return null;
                }

                ReadMultipleRegistersRequest request =
                        new ReadMultipleRegistersRequest(modbusAddress, 1);

                request.setUnitID(unitId);

                ModbusTCPTransaction transaction =
                        new ModbusTCPTransaction(connection);

                transaction.setRequest(request);
                transaction.execute();

                ReadMultipleRegistersResponse response =
                        (ReadMultipleRegistersResponse) transaction.getResponse();

                if (response != null && response.getWordCount() >= 1) {
                    int value = response.getRegisterValue(0);
                    log.debug("📡 Modbus INT16 [{}] = {}", address, value);
                    reportOk(key);
                    return value;
                }
            }

        } catch (Exception e) {
            reportFailure(key, e.getMessage());
            resetConnection(key, connection);
        }

        return null;
    }

    /**
     * Чтение BOOL из регистра (проверяем младший бит)
     */
    public Boolean readBoolean(String host, int port, int address, int unitId) {
        Integer intValue = readInt16(host, port, address, unitId);
        if (intValue != null) {
            return (intValue & 0x01) != 0;
        }
        return null;
    }

    /**
     * БЛОК holding-регистров одной транзакцией FC03. Основа батч-чтения: вместо N
     * запросов по тегу читаем непрерывный блок и режем на значения на стороне вызова.
     * {@code startRegister} — уже 0-based (адрес 40001 → 0), {@code count} ≤ 125 (лимит FC03).
     * Возвращает сырые регистры (0..65535) или null при ошибке/обрыве (весь блок = BAD).
     */
    public int[] readHoldingRegisters(String host, int port, int startRegister, int count, int unitId) {
        String key = host + ":" + port;
        TCPMasterConnection connection = getConnection(key, host, port);
        if (connection == null) {
            return null;
        }
        try {
            synchronized (connection) {
                if (!connection.isConnected()) {
                    connection.connect();
                }
                ReadMultipleRegistersRequest request =
                        new ReadMultipleRegistersRequest(startRegister, count);
                request.setUnitID(unitId);
                ModbusTCPTransaction transaction = new ModbusTCPTransaction(connection);
                transaction.setRequest(request);
                transaction.execute();
                ReadMultipleRegistersResponse response =
                        (ReadMultipleRegistersResponse) transaction.getResponse();
                if (response == null || response.getWordCount() < count) {
                    return null;
                }
                int[] regs = new int[count];
                for (int i = 0; i < count; i++) {
                    regs[i] = response.getRegisterValue(i);
                }
                reportOk(key);
                return regs;
            }
        } catch (Exception e) {
            reportFailure(key, e.getMessage());
            resetConnection(key, connection);
            return null;
        }
    }

    /**
     * Получение или создание соединения
     */
    private TCPMasterConnection getConnection(String key, String host, int port) {
        TCPMasterConnection existing = connections.get(key);
        if (existing != null) return existing;

        // Соединения нет. НЕ долбим порт на каждый из 1877 тегов в цикле: реальный
        // connect пробуем не чаще раза в CONNECT_BACKOFF_MS. Между попытками — сразу
        // null (тег вернёт BAD), что убирает hot-loop из тысяч connect/цикл при обрыве.
        long now = System.currentTimeMillis();
        Long next = nextConnectMs.get(key);
        if (next != null && now < next) {
            // В окне backoff — тихо (не считаем как отдельную ошибку, иначе счётчик
            // «ошибок подряд» раздувается до десятков тысяч за секунды).
            log.debug("Modbus {} в окне backoff, connect пропущен", key);
            return null;
        }
        nextConnectMs.put(key, now + CONNECT_BACKOFF_MS);

        try {
            log.debug("📡 Creating Modbus connection to {}:{}", host, port);
            InetAddress addr = InetAddress.getByName(host);
            TCPMasterConnection connection = new TCPMasterConnection(addr);
            connection.setPort(port);
            // Явный таймаут на connect+чтение — не полагаемся на неявный дефолт j2mod.
            connection.setTimeout(modbusTimeoutMs);
            connection.connect();
            log.info("✅ Connected to Modbus {}:{}", host, port);
            connections.put(key, connection);
            nextConnectMs.remove(key);
            return connection;
        } catch (Exception e) {
            reportFailure(key, e.getMessage());
            return null;
        }
    }

    /**
     * Сброс соединения
     */
    private void resetConnection(String key, TCPMasterConnection connection) {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Exception e) {
            log.debug("Error closing connection: {}", e.getMessage());
        }
        connections.remove(key);
    }

    /**
     * Закрыть все соединения
     */
    public void disconnectAll() {
        for (var entry : connections.entrySet()) {
            try {
                entry.getValue().close();
                log.info("Disconnected: {}", entry.getKey());
            } catch (Exception e) {
                log.warn("Error disconnecting: {}", e.getMessage());
            }
        }
        connections.clear();
    }
}