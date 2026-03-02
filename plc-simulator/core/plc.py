from typing import Dict, List
from asyncua import Server, ua
import asyncio
import logging
from .data_block import DataBlock
from .tag import Tag

logger = logging.getLogger(__name__)

class PLCSimulator:
    """Главный класс симулятора контроллера"""
    
    def __init__(self, config: dict):
        self.config = config
        self.plc_id = config['plc']['id']
        self.name = config['plc']['name']
        self.endpoint = config['plc']['endpoint']
        self.update_rate = config['plc']['update_rate']
        
        # OPC UA сервер
        self.server = Server()
        self.namespace_idx = None
        
        # Data Blocks
        self.data_blocks: Dict[int, DataBlock] = {}
        
        # Состояние
        self.running = False
        self.update_task = None
        self.server_running = False
        
        # Словарь для хранения OPC UA узлов
        self.opcua_nodes = {}
        
        # Счетчики для диагностики
        self.read_count = 0
        self.write_count = 0
        
    def load_configuration(self, data_blocks_config: List[dict]):
        """Загрузить конфигурацию Data Blocks"""
        for db_config in data_blocks_config:
            db = DataBlock(
                db_number=db_config['db_number'],
                name=db_config['name'],
                tags_config=db_config['tags']
            )
            self.data_blocks[db.db_number] = db
        
        logger.info(f"Loaded {len(self.data_blocks)} Data Blocks")
    
    def _get_variant_type(self, tag: Tag) -> ua.VariantType:
        """Получить правильный тип Variant для тега"""
        type_mapping = {
            "bool": ua.VariantType.Boolean,
            "int": ua.VariantType.Int32,  # Явно Int32, не Int64
            "float": ua.VariantType.Float,  # Float, не Double
            "byte": ua.VariantType.Byte,
            "string": ua.VariantType.String
        }
        return type_mapping.get(tag.data_type.value, ua.VariantType.Float)
    
    def _convert_to_correct_type(self, value, variant_type: ua.VariantType):
        """Конвертировать значение в правильный тип"""
        try:
            if variant_type == ua.VariantType.Int32:
                return int(value)
            elif variant_type == ua.VariantType.Float:
                return float(value)
            elif variant_type == ua.VariantType.Boolean:
                return bool(value)
            elif variant_type == ua.VariantType.Byte:
                return int(value) & 0xFF
            elif variant_type == ua.VariantType.String:
                return str(value)
            else:
                return value
        except Exception as e:
            logger.error(f"Type conversion error: {e}")
            return value
    
    async def init_opcua_server(self):
        """Инициализация OPC UA сервера"""
        try:
            await self.server.init()
            self.server.set_endpoint(self.endpoint)
            self.server.set_server_name(self.name)
            
            # Настройка сервера
            self.server.set_security_policy([ua.SecurityPolicyType.NoSecurity])
            
            # Регистрируем namespace
            uri = f"http://{self.plc_id}"
            self.namespace_idx = await self.server.register_namespace(uri)
            
            # Получаем корневой узел объектов
            objects = self.server.get_objects_node()
            
            # Создаем узел PLC
            plc_node = await objects.add_object(
                self.namespace_idx, 
                self.plc_id
            )
            
            # Для каждого Data Block создаем папку
            for db_number, db in self.data_blocks.items():
                # Создаем узел для Data Block
                db_node = await plc_node.add_object(
                    self.namespace_idx, 
                    f"DB{db_number}_{db.name}"
                )
                
                # Добавляем теги
                for tag in db.get_all_tags():
                    node = await self._add_tag_to_server(db_node, tag)
                    # Сохраняем ссылку на узел
                    self.opcua_nodes[tag.address] = node
            
            self.server_running = True
            logger.info(f"OPC UA server initialized at {self.endpoint}")
            logger.info(f"Namespace index: {self.namespace_idx}")
            logger.info(f"Added {len(self.opcua_nodes)} tags to address space")
            
        except Exception as e:
            logger.error(f"Failed to initialize OPC UA server: {e}")
            raise
    
    async def _add_tag_to_server(self, parent_node, tag: Tag):
        """Добавить тег в OPC UA сервер"""
        try:
            # Получаем правильный тип для OPC UA
            variant_type = self._get_variant_type(tag)
            
            # Конвертируем начальное значение в правильный тип
            initial_value = self._convert_to_correct_type(tag._value, variant_type)
            
            # Создаем Variant с правильным типом
            variant = ua.Variant(initial_value, variant_type)
            
            # Создаем переменную с явным указанием типа
            var = await parent_node.add_variable(
                self.namespace_idx,
                tag.name,
                variant
            )
            
            # Устанавливаем доступ (RW или RO)
            await var.set_writable(tag.access.value == "RW")
            
            # Добавляем описание через атрибуты
            try:
                # Устанавливаем DisplayName
                display_name = f"{tag.name}"
                if tag.unit:
                    display_name += f" [{tag.unit}]"
                await var.write_display_name(ua.LocalizedText(display_name))
                
                # Устанавливаем Description
                if tag.unit:
                    await var.write_description(
                        ua.LocalizedText(f"Unit: {tag.unit}")
                    )
            except Exception as e:
                logger.debug(f"Could not set attributes for {tag.name}: {e}")
            
            # Сохраняем тип для последующего использования при обновлениях
            tag.opcua_variant_type = variant_type
            tag.opcua_node = var
            
            logger.debug(f"Added tag {tag.address} with type {variant_type}")
            return var
            
        except Exception as e:
            logger.error(f"Failed to add tag {tag.address}: {e}")
            raise
    
    async def update_loop(self):
        """Цикл обновления значений"""
        last_time = asyncio.get_event_loop().time()
        
        while self.running:
            try:
                current_time = asyncio.get_event_loop().time()
                dt = current_time - last_time
                
                # Обновляем все теги
                update_count = 0
                for db in self.data_blocks.values():
                    db.update_simulation(dt)
                    
                    # Обновляем значения в OPC UA сервере
                    for tag in db.get_all_tags():
                        if hasattr(tag, 'opcua_node') and tag.opcua_node:
                            try:
                                # Конвертируем значение в правильный тип
                                if hasattr(tag, 'opcua_variant_type'):
                                    corrected_value = self._convert_to_correct_type(
                                        tag.value, 
                                        tag.opcua_variant_type
                                    )
                                    
                                    # Создаем Variant с правильным типом
                                    variant = ua.Variant(
                                        corrected_value, 
                                        tag.opcua_variant_type
                                    )
                                    
                                    # Записываем значение
                                    await tag.opcua_node.write_value(variant)
                                    update_count += 1
                            except Exception as e:
                                logger.debug(f"Error updating {tag.address}: {e}")
                
                # Считаем статистику
                self.read_count += update_count
                
                last_time = current_time
                await asyncio.sleep(self.update_rate)
                
            except asyncio.CancelledError:
                logger.info("Update loop cancelled")
                break
            except Exception as e:
                logger.error(f"Error in update loop: {e}")
                await asyncio.sleep(1)
    
    async def start(self):
        """Запуск симулятора"""
        logger.info(f"Starting PLC Simulator: {self.name}")
        
        try:
            # Инициализация OPC UA
            await self.init_opcua_server()
            
            # Запуск сервера
            async with self.server:
                logger.info(f"PLC Simulator running at {self.endpoint}")
                logger.info("=" * 50)
                logger.info("Available tags:")
                for address in self.opcua_nodes.keys():
                    logger.info(f"  {address}")
                logger.info("=" * 50)
                logger.info("Press Ctrl+C to stop")
                
                self.running = True
                self.update_task = asyncio.create_task(self.update_loop())
                
                # Держим сервер запущенным
                try:
                    await asyncio.Future()
                except asyncio.CancelledError:
                    logger.info("Stopping simulator...")
                finally:
                    self.running = False
                    if self.update_task:
                        self.update_task.cancel()
                        try:
                            await self.update_task
                        except asyncio.CancelledError:
                            pass
                            
        except Exception as e:
            logger.error(f"Failed to start simulator: {e}")
            await self.stop()
            raise
    
    async def stop(self):
        """Остановка симулятора"""
        logger.info("Stopping PLC Simulator")
        self.running = False
        
        if self.update_task:
            self.update_task.cancel()
            try:
                await self.update_task
            except asyncio.CancelledError:
                pass
        
        if self.server_running:
            try:
                self.server.stop()  # Убрал await, так как это может быть синхронный метод
                logger.info("OPC UA server stopped")
            except Exception as e:
                logger.error(f"Error stopping server: {e}")
        
        self.server_running = False
    
    def get_stats(self):
        """Статистика работы"""
        return {
            'plc_id': self.plc_id,
            'uptime': 'N/A',
            'read_count': self.read_count,
            'write_count': self.write_count,
            'data_blocks': len(self.data_blocks),
            'total_tags': sum(len(db.get_all_tags()) for db in self.data_blocks.values())
        }