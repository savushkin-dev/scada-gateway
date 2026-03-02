import random
import time
import logging
from enum import Enum

logger = logging.getLogger(__name__)

class AccessType(Enum):
    READ_ONLY = "RO"
    READ_WRITE = "RW"

class DataType(Enum):
    BOOL = "bool"
    INT = "int"
    FLOAT = "float"
    BYTE = "byte"
    STRING = "string"

class Tag:
    """Модель тега (переменной) контроллера"""
    
    def __init__(self, config):
        self.name = config['name']
        self.address = config['address']
        self.data_type = DataType(config['type'])
        self.access = AccessType(config.get('access', 'RO'))
        self.unit = config.get('unit', '')
        self.min_value = config.get('min')
        self.max_value = config.get('max')
        
        # Инициализация значения
        self._value = self._convert_initial(config['initial'])
        self._original_value = self._value
        self.quality = "GOOD"  # GOOD, BAD, UNCERTAIN
        self.timestamp = time.time()
        
        # Для симуляции
        self.noise_enabled = True
        self.drift_enabled = True
        self.drift_rate = 0.001
        
        # OPC UA аттрибуты
        self.opcua_node = None
        self.opcua_variant_type = None
        
        logger.debug(f"Created tag {self.address}: {self._value}")
    
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
    
    @property
    def value(self):
        """Текущее значение с учетом шума"""
        if not self.noise_enabled:
            return self._value
        
        # Добавляем шум (2% от значения)
        if self.data_type in [DataType.INT, DataType.FLOAT]:
            noise = random.gauss(0, abs(self._value) * 0.02)
            noisy_value = self._value + noise
            
            # Ограничиваем
            if self.min_value is not None:
                noisy_value = max(self.min_value, noisy_value)
            if self.max_value is not None:
                noisy_value = min(self.max_value, noisy_value)
            
            if self.data_type == DataType.INT:
                return int(noisy_value)
            return float(noisy_value)
        
        return self._value
    
    @value.setter
    def value(self, new_value):
        """Установка значения (только для RW тегов)"""
        if self.access == AccessType.READ_WRITE:
            self._value = self._convert_initial(new_value)
            self.timestamp = time.time()
            logger.debug(f"Tag {self.address} set to {self._value}")
    
    def update_simulation(self, dt):
        """Обновление симуляции (дрейф и т.д.)"""
        if not self.drift_enabled or self.access == AccessType.READ_WRITE:
            return
        
        # Медленный дрейф значения
        if self.data_type in [DataType.INT, DataType.FLOAT]:
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
    
    def to_dict(self):
        """Для отправки в Kafka"""
        return {
            'address': self.address,
            'value': self.value,
            'quality': self.quality,
            'timestamp': self.timestamp,
            'unit': self.unit
        }