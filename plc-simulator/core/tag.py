"""
Модель тега симулятора: одна переменная контроллера (OPC UA и/или Modbus).

Тег умеет отдавать значение тремя способами: из архива (generator="replay",
значение подставляет движок ArchiveReplay), синтетическим генератором (sine/
random_walk/pulse/…) или как статическое значение с лёгким шумом/дрейфом. У RW-тегов
значение может записать оператор через шлюз (клапан/мотор). Здесь же — конвертация
значения в Modbus-регистры и в dict для отправки.
"""
import random
import time
import math
import logging
from enum import Enum

from .archive_replay import ReplaySpec

logger = logging.getLogger(__name__)

class AccessType(Enum):
    """Доступ к тегу: RO — только чтение (датчик), RW — можно записать (актуатор)."""
    READ_ONLY = "RO"
    READ_WRITE = "RW"

class DataType(Enum):
    """Тип данных значения тега."""
    BOOL = "bool"
    INT = "int"
    FLOAT = "float"
    BYTE = "byte"
    STRING = "string"

class Protocol(Enum):
    """Протокол, по которому тег отдаётся наружу."""
    OPCUA = "opcua"
    MODBUS = "modbus"
    PAC = "pac"        # driver-master (PAC-контроллеры Savushkin/ptusa), см. core/pac_server.py

class Tag:
    """Модель тега (переменной) контроллера с поддержкой OPC UA, Modbus и PAC"""

    def __init__(self, config):
        """Строит тег из секции YAML: адресация (address/replay_source, opcua/modbus),
        тип и доступ, пределы min/max, генератор сигнала и флаги шума/дрейфа."""
        # Основные поля
        self.name = config['name']
        self.address = config.get('address', config['name'])
        # Источник данных архива (replay). По умолчанию = address, но может
        # отличаться: несколько тегов могут проигрывать ОДНУ серию архива
        # (дублирование данных), сохраняя при этом свой уникальный address/nodeId.
        # Как именно серия превращается в значение канала (фильтр кодов обрыва,
        # масштаб, интеграл, возраст операции) — см. core/archive_replay.ReplaySpec.
        self.replay_spec = ReplaySpec.from_config(config, self.address)
        self.replay_source = self.replay_spec.source
        self.replay_offset = self.replay_spec.offset
        # Объектная модель прибора (как настоящий ПЛК): устройство + поле.
        # OPC UA-узлы группируются в object-node на прибор с переменными-полями.
        self.device = config.get('device')       # напр. "LINE1V0"
        self.field = config.get('field')         # напр. "ST"
        self.dev_type = config.get('dev_type')   # напр. "V"
        self.data_type = DataType(config['type'])
        self.access = AccessType(config.get('access', 'RO'))
        self.unit = config.get('unit', '')
        self.min_value = config.get('min')
        self.max_value = config.get('max')
        
        # Протокол (OPC UA или Modbus)
        protocol_value = config.get('protocol', 'opcua')
        self.protocol = Protocol(protocol_value) if isinstance(protocol_value, str) else protocol_value
        
        # OPC UA поля
        self.opcua_node = config.get('opcua_node')
        self.opcua_variant_type = None
        
        # Modbus поля
        self.modbus_address = config.get('modbus_address')
        self.modbus_type = config.get('modbus_type', 'float32')
        # Архив числовой, а часть каналов по базе — строковые (списки рецептов и
        # программ, ответ на команду). Шаблон превращает число из архива в метку
        # вида "REC-07", чтобы строковый тег тоже жил, а не стоял пустым.
        self.replay_format = config.get('replay_format')
        
        # Инициализация значения
        initial_value = config.get('initial', 0)
        self._value = self._convert_initial(initial_value)
        self._original_value = self._value
        self.quality = "GOOD"
        self.timestamp = time.time()
        
        # Для симуляции
        self.noise_enabled = config.get('noise_enabled', True)
        self.drift_enabled = config.get('drift_enabled', True)
        self.drift_rate = config.get('drift_rate', 0.001)
        
        # Генератор сигнала
        self.generator = config.get('generator')
        self.generator_params = config.get('generator_params', {})
        
        # Для генераторов
        self._last_time = time.time()
        self._phase = 0.0
        self._random_walk_value = self._value

        # Латч оператора для RW-тегов, у которых ЕСТЬ источник данных
        # (generator/replay). Пока оператор не записал своё значение, тег ведётся
        # архивом/генератором и остаётся writable; после первой записи оператора
        # «защёлкивается» на ручном значении и держит его (см. plc.py update_loop).
        # Для чистых актуаторов (без generator) латч не используется.
        self._operator_override = False
        self._last_pushed = None

        # OPC UA аттрибуты
        self.opcua_node_obj = None
        
        logger.debug(f"Created tag {self.address} ({self.protocol.value}): {self._value}")
    
    def _convert_initial(self, value):
        """Конвертировать начальное значение в нужный тип"""
        if self.data_type == DataType.BOOL:
            return bool(value)
        elif self.data_type == DataType.INT:  
            return int(value)
        elif self.data_type == DataType.FLOAT:
            return float(value)
        elif self.data_type == DataType.BYTE:
            return int(value) & 0xFF
        else:
            return str(value)
    
    def _generate_anomaly(self):
        """Генерация аномального значения (выход за пределы)"""
        if self.max_value is not None:
            # Аномалия: значение на 30-100% выше максимума
            anomaly = self.max_value * random.uniform(1.3, 2.0)
            logger.info(f"🔥 ANOMALY! {self.name} = {anomaly:.2f} (normal max: {self.max_value})")
            return anomaly
        elif self.min_value is not None:
            # Аномалия: значение на 30-100% ниже минимума
            anomaly = self.min_value * random.uniform(0.1, 0.7)
            logger.info(f"🔥 ANOMALY! {self.name} = {anomaly:.2f} (normal min: {self.min_value})")
            return anomaly
        return self._value * 2
    
    def _generate_value(self, dt: float = 1.0):
        """Генерация значения на основе заданного типа сигнала"""
        if self.generator is None:
            return self._value
        
        try:
            current_time = time.time()
            dt_actual = min(dt, 0.1)
            self._last_time = current_time
            
            if self.generator == "constant":
                return self._value
                
            elif self.generator == "random_walk":
                step = self.generator_params.get('step', 1.0)
                self._random_walk_value += random.uniform(-step, step) * dt_actual
                new_value = self._random_walk_value
                
                # Ограничиваем
                if self.min_value is not None:
                    new_value = max(self.min_value, new_value)
                if self.max_value is not None:
                    new_value = min(self.max_value, new_value)
                
                self._random_walk_value = new_value
                return new_value
                
            elif self.generator == "sine":
                amplitude = self.generator_params.get('amplitude', 1.0)
                period = self.generator_params.get('period', 60.0)
                frequency = 2 * math.pi / period
                self._phase += frequency * dt_actual
                if self._phase > 2 * math.pi:
                    self._phase -= 2 * math.pi
                
                base_value = self._original_value
                return base_value + amplitude * math.sin(self._phase)
                
            elif self.generator == "sawtooth":
                amplitude = self.generator_params.get('amplitude', 1.0)
                period = self.generator_params.get('period', 60.0)
                t = current_time % period
                return self._value + amplitude * (2 * t / period - 1)
                
            elif self.generator == "pulse":
                period = self.generator_params.get('period', 10.0)
                duty_cycle = self.generator_params.get('duty_cycle', 0.5)
                t = current_time % period
                on_value = self.generator_params.get('on_value', 1)
                off_value = self.generator_params.get('off_value', 0)
                return on_value if t < period * duty_cycle else off_value
                
            elif self.generator == "triangle":
                amplitude = self.generator_params.get('amplitude', 1.0)
                period = self.generator_params.get('period', 60.0)
                t = current_time % period
                return self._value + amplitude * (1 - abs(2 * t / period - 1)) * 2 - amplitude
                
        except Exception as e:
            logger.warning(f"Error generating value for {self.name}: {e}")
        
        return self._value
    
    @property
    def value(self):
        """Текущее значение с учетом шума и генератора"""
        # Replay-теги: значение задаётся движком архива напрямую в _value,
        # никакого шума, дрейфа и аномалий — отдаём ровно как в архиве.
        if self.generator == "replay":
            return self._value

        # 🔥 3% вероятность генерации аномалии (выхода за пределы)
        if random.random() < 0.03 and (self.max_value is not None or self.min_value is not None):
            return self._generate_anomaly()
        
        current_value = self._value
        
        # Применяем генератор если есть
        if self.generator:
            generated = self._generate_value()
            current_value = generated
        
        # Добавляем шум
        if self.noise_enabled and self.data_type in [DataType.INT, DataType.FLOAT]:
            noise_level = abs(current_value) * 0.02 if current_value != 0 else 0.1
            noise = random.gauss(0, noise_level)
            noisy_value = current_value + noise
            
            # Ограничиваем
            if self.min_value is not None:
                noisy_value = max(self.min_value, noisy_value)
            if self.max_value is not None:
                noisy_value = min(self.max_value, noisy_value)
            
            if self.data_type == DataType.INT:
                return int(noisy_value)
            return float(noisy_value)
        
        # Ограничиваем без шума
        if self.min_value is not None and current_value < self.min_value:
            return self.min_value
        if self.max_value is not None and current_value > self.max_value:
            return self.max_value
            
        return current_value
    
    @value.setter
    def value(self, new_value):
        """Установка значения (только для RW тегов)"""
        if self.access == AccessType.READ_WRITE:
            self._value = self._convert_initial(new_value)
            self._original_value = self._value
            self.timestamp = time.time()
            logger.debug(f"Tag {self.address} set to {self._value}")
    
    def set_replay_value(self, raw_value):
        """Установить значение из архива (replay), минуя проверку доступа RW."""
        if raw_value is None:
            return
        if self.replay_format:
            self._value = self.replay_format % int(raw_value)
        else:
            self._value = self._convert_initial(raw_value)
        self.timestamp = time.time()

    def update_simulation(self, dt: float = 1.0):
        """Обновление симуляции (дрейф)"""
        if not self.drift_enabled or self.access == AccessType.READ_WRITE:
            return
        
        # Медленный дрейф значения (только если нет генератора)
        if self.generator is None and self.data_type in [DataType.INT, DataType.FLOAT]:
            drift = random.uniform(-self.drift_rate, self.drift_rate) * dt
            new_value = self._value + drift
            
            # Ограничиваем
            if self.min_value is not None:
                new_value = max(self.min_value, new_value)
            if self.max_value is not None:
                new_value = min(self.max_value, new_value)
            
            # Сохраняем в правильном типе
            if self.data_type == DataType.INT:
                self._value = int(new_value)
            else:
                self._value = float(new_value)
    
    def get_modbus_registers(self):
        """Получить Modbus регистры для значения"""
        if self.modbus_address is None:
            return None
            
        try:
            value = self.value
            if self.modbus_type == "float32":
                import struct
                packed = struct.pack('<f', float(value))
                reg1 = int.from_bytes(packed[0:2], 'little')
                reg2 = int.from_bytes(packed[2:4], 'little')
                return [(self.modbus_address, reg1), (self.modbus_address + 1, reg2)]
            elif self.modbus_type == "int16":
                return [(self.modbus_address, int(value) & 0xFFFF)]
            elif self.modbus_type == "int32":
                val = int(value) & 0xFFFFFFFF
                return [(self.modbus_address, val & 0xFFFF), (self.modbus_address + 1, (val >> 16) & 0xFFFF)]
            elif self.modbus_type == "bool":
                return [(self.modbus_address, 1 if value else 0)]
            else:
                return [(self.modbus_address, int(value) & 0xFFFF)]
        except Exception as e:
            logger.error(f"Failed to get Modbus registers for {self.name}: {e}")
            return None
    
    def to_dict(self):
        """Для отправки в Kafka"""
        return {
            'name': self.name,
            'address': self.address,
            'protocol': self.protocol.value,
            'value': self.value,
            'quality': self.quality,
            'timestamp': self.timestamp,
            'unit': self.unit,
            'data_type': self.data_type.value,
            'modbus_address': self.modbus_address,
            'opcua_node': self.opcua_node
        }
