"""
Ядро симулятора ПЛК: поднимает OPC UA-сервер (asyncua) и Modbus TCP-сервер, держит
Data Blocks с тегами и крутит цикл обновления значений.

Цикл (update_loop) на каждом шаге: подставляет значения из архива (replay),
записывает свежие значения в OPC UA-узлы (RW-теги, наоборот, читает обратно — чтобы
не затирать команду оператора) и обновляет Modbus-регистры. OPC UA-теги группируются
в объектную модель прибор→поля, как у настоящего ПЛК. Точка входа — simulator.py.
"""
from typing import Dict, List
from asyncua import Server, ua
import asyncio
import logging
import math
import struct
import os
from pathlib import Path
from .data_block import DataBlock
from .tag import Tag
from .modbus_server import ModbusServer
from .pac_server import PACServer
from .archive_replay import ArchiveReplay

logger = logging.getLogger(__name__)


def _operator_touched(node_val, last_pushed) -> bool:
    """Записал ли оператор в узел новое значение с прошлого нашего пуша.

    Сравниваем текущее значение узла с тем, что мы туда положили сами, с допуском
    на округление до float32 (иначе честный round-trip архивного значения выглядел
    бы как «оператор перебил»). last_pushed=None — мы ещё ничего не писали."""
    if last_pushed is None:
        return False
    try:
        return not math.isclose(float(node_val), float(last_pushed),
                                rel_tol=1e-4, abs_tol=1e-6)
    except (TypeError, ValueError):
        return node_val != last_pushed


class PLCSimulator:
    """Главный класс симулятора контроллера с поддержкой OPC UA, Modbus TCP и PAC (driver-master)"""

    def __init__(self, config: dict):
        """Разбирает конфиг (plc: id/name/endpoint/update_rate, modbus_port, replay),
        готовит OPC UA-сервер и — если replay включён — движок воспроизведения архива.
        Endpoint и modbus-порт можно переопределить через env (OPCUA_ENDPOINT/MODBUS_PORT)."""
        self.config = config
        self.plc_id = config['plc']['id']
        self.name = config['plc']['name']
        # OPC UA endpoint можно переопределить через env OPCUA_ENDPOINT.
        # В Docker важно анонсировать РЕЗОЛВИМОЕ имя (напр. opc.tcp://simulator:4840),
        # а не 0.0.0.0 — иначе клиент после discovery не переподключится.
        self.endpoint = os.environ.get('OPCUA_ENDPOINT', config['plc']['endpoint'])
        self.update_rate = config['plc']['update_rate']

        # Modbus порт: env MODBUS_PORT или из конфига (по умолчанию 5020).
        self.modbus_port = int(os.environ.get('MODBUS_PORT', config.get('modbus_port', 5020)))
        self.modbus_server = None

        # PAC-порт (протокол driver-master): env PAC_PORT или из конфига (по умолчанию 10000).
        # Сервер поднимается только если в конфиге есть теги с protocol: pac.
        self.pac_port = int(os.environ.get('PAC_PORT', config.get('pac_port', 10000)))
        self.pac_server = None

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
        
        # Словарь для хранения последних значений Modbus
        self.modbus_values = {}
        # Что мы сами положили в регистры: база для обратного чтения.
        self._modbus_pushed = {}
        
        # Счетчики для диагностики
        self.read_count = 0
        self.write_count = 0

        # Воспроизведение архива (replay)
        self.replay = None
        replay_cfg = config.get('replay', {})
        if replay_cfg.get('enabled'):
            self.replay = ArchiveReplay(
                data_path=replay_cfg['data_path'],
                speed=replay_cfg.get('speed', 1.0),
                loop=replay_cfg.get('loop', True),
                base_dir=Path(__file__).resolve().parent.parent,
            )

    def load_configuration(self, data_blocks_config: List[dict]):
        """Загрузить конфигурацию Data Blocks"""
        for db_config in data_blocks_config:
            db = DataBlock(
                db_number=db_config['db_number'],
                name=db_config['name'],
                tags_config=db_config['tags']
            )
            self.data_blocks[db.db_number] = db
            
            # Инициализируем Modbus значения
            for tag in db.get_all_tags():
                if hasattr(tag, 'protocol') and getattr(tag.protocol, "value", tag.protocol) == "modbus" and tag.modbus_address is not None:
                    self.modbus_values[tag.modbus_address] = {
                        'tag': tag,
                        'value': tag.value
                    }
        
        logger.info(f"Loaded {len(self.data_blocks)} Data Blocks")
        logger.info(f"Found {len(self.modbus_values)} Modbus tags")
    
    def _get_variant_type(self, tag: Tag) -> ua.VariantType:
        """Получить правильный тип Variant для тега"""
        type_mapping = {
            "bool": ua.VariantType.Boolean,
            "int": ua.VariantType.Int32,
            "float": ua.VariantType.Float,
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

                # ОБЪЕКТНАЯ МОДЕЛЬ КАК У РЕАЛЬНОГО ПЛК: OPC UA-теги группируются
                # в object-node на прибор (device) с переменными-полями (field)
                # внутри. Так браузинг сервера показывает LINE1V0 → {ST, M},
                # а не плоский список. nodeId поля не меняется (ns=2;s=<node.id>),
                # поэтому привязка шлюза к базе каналов остаётся прежней.
                device_nodes = {}   # имя прибора -> object-node
                for tag in db.get_all_tags():
                    # В OPC UA-адресное пространство берём ТОЛЬКО opcua-теги
                    # (modbus обслуживает Modbus-сервер, pac — PAC-сервер).
                    proto = getattr(tag, 'protocol', None)
                    if proto is not None and getattr(proto, "value", proto) != "opcua":
                        continue

                    parent = db_node
                    device = getattr(tag, 'device', None)
                    if device:
                        # browsename прибора = <тип>_<имя> (уникально, без коллизий)
                        dev_key = f"{getattr(tag, 'dev_type', '') or 'DEV'}_{device}"
                        if dev_key not in device_nodes:
                            device_nodes[dev_key] = await db_node.add_object(
                                self.namespace_idx, dev_key)
                        parent = device_nodes[dev_key]

                    node = await self._add_tag_to_server(parent, tag)
                    self.opcua_nodes[tag.address] = node
                logger.info(f"OPC UA: {len(device_nodes)} объектов-устройств")
            
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
            variant_type = self._get_variant_type(tag)
            initial_value = self._convert_to_correct_type(tag._value, variant_type)
            variant = ua.Variant(initial_value, variant_type)

            # Явный строковый NodeId = address, чтобы шлюз мог обращаться
            # по предсказуемому id (ns=2;s=<address>), а не по авто-числовому.
            node_id = ua.NodeId(tag.address, self.namespace_idx)

            # browsename: под объектом-прибором = имя поля (ST/M/V…), иначе node.id.
            browse = getattr(tag, 'field', None) or tag.name
            var = await parent_node.add_variable(
                node_id,
                browse,
                variant
            )
            
            await var.set_writable(tag.access.value == "RW")
            
            try:
                display_name = f"{tag.name}"
                if tag.unit:
                    display_name += f" [{tag.unit}]"
                await var.write_display_name(ua.LocalizedText(display_name))
                
                if tag.unit:
                    await var.write_description(
                        ua.LocalizedText(f"Unit: {tag.unit}")
                    )
            except Exception as e:
                logger.debug(f"Could not set attributes for {tag.name}: {e}")
            
            tag.opcua_variant_type = variant_type
            tag.opcua_node = var
            
            logger.debug(f"Added tag {tag.address} with type {variant_type}")
            return var
            
        except Exception as e:
            logger.error(f"Failed to add tag {tag.address}: {e}")
            raise
    
    async def init_modbus_server(self):
        """Инициализация Modbus TCP сервера"""
        try:
            self.modbus_server = ModbusServer(self.modbus_port)
            loop = asyncio.get_event_loop()
            await loop.run_in_executor(None, self.modbus_server.start)
            logger.info(f"Modbus TCP server started on port {self.modbus_port}")
        except Exception as e:
            logger.error(f"Failed to start Modbus server: {e}")
    
    @staticmethod
    def _modbus_encode(value, modbus_type):
        """Значение тега -> список регистров (как его увидит датастор)."""
        if modbus_type == "float32":
            packed = struct.pack("<f", float(value))
            return [int.from_bytes(packed[0:2], "little"), int.from_bytes(packed[2:4], "little")]
        if modbus_type == "int32":
            raw = int(value) & 0xFFFFFFFF
            return [raw & 0xFFFF, (raw >> 16) & 0xFFFF]
        if modbus_type == "bool":
            return [1 if value else 0]
        return [int(value) & 0xFFFF]

    @staticmethod
    def _modbus_decode(regs, modbus_type):
        """Регистры -> значение тега. int16 разворачиваем СО ЗНАКОМ, иначе запись
        отрицательного кода вернулась бы оператору как 65531."""
        if modbus_type == "float32":
            raw = regs[0].to_bytes(2, "little") + regs[1].to_bytes(2, "little")
            return struct.unpack("<f", raw)[0]
        if modbus_type == "int32":
            raw = regs[0] | (regs[1] << 16)
            return raw - 0x100000000 if raw >= 0x80000000 else raw
        if modbus_type == "bool":
            return bool(regs[0])
        raw = regs[0]
        return raw - 0x10000 if raw >= 0x8000 else raw

    def _modbus_operator_write(self, tag, address, modbus_type):
        """Значение, записанное шлюзом в регистр, или None если запись не приходила."""
        count = 2 if modbus_type in ("float32", "int32") else 1
        regs = self.modbus_server.read_registers(address, count)
        if not regs or len(regs) < count:
            return None
        if address not in self._modbus_pushed:
            return None                      # мы ещё ничего не клали в регистр — рано
                                              # считать его бланковое содержимое записью
                                              # оператора (симметрично _operator_touched)
        if regs == self._modbus_pushed.get(address):
            return None                      # в регистре ровно то, что положили мы
        try:
            return self._modbus_decode(regs, modbus_type)
        except Exception:
            return None

    async def update_modbus_tags(self):
        """Обновление Modbus регистров"""
        if not self.modbus_server or not self.modbus_server.running:
            return
        
        for db in self.data_blocks.values():
            for tag in db.get_all_tags():
                # Проверяем, является ли тег Modbus
                is_modbus = False
                modbus_address = None
                modbus_type = "float32"
                
                if hasattr(tag, 'protocol') and getattr(tag.protocol, "value", tag.protocol) == "modbus":
                    is_modbus = True
                    modbus_address = getattr(tag, 'modbus_address', None)
                    modbus_type = getattr(tag, 'modbus_type', 'float32')
                elif hasattr(tag, 'modbus_address') and tag.modbus_address is not None:
                    is_modbus = True
                    modbus_address = tag.modbus_address
                    modbus_type = getattr(tag, 'modbus_type', 'float32')
                
                if is_modbus and modbus_address is not None:
                    try:
                        # Сначала обратное чтение: шлюз пишет команду прямо в датастор
                        # сервера, и без этой проверки мы затёрли бы её своим значением.
                        # Для OPC UA то же самое делает ветка RW в update_loop.
                        written = self._modbus_operator_write(tag, modbus_address, modbus_type)
                        if written is not None:
                            tag.value = written
                            tag._operator_override = True

                        value = tag.value
                        if value is None:
                            continue

                        # Конвертируем значение в правильный тип
                        if modbus_type == "float32":
                            self.modbus_server.update_register(modbus_address, value, "float32")
                        elif modbus_type == "int32":
                            self.modbus_server.update_register(modbus_address, int(value), "int32")
                        elif modbus_type == "int16":
                            self.modbus_server.update_register(modbus_address, int(value), "int16")
                        elif modbus_type == "uint16":
                            self.modbus_server.update_register(modbus_address, int(value), "uint16")
                        elif modbus_type == "bool":
                            self.modbus_server.update_register(modbus_address, 1 if value else 0, "bool")
                        self._modbus_pushed[modbus_address] = self._modbus_encode(value, modbus_type)
                        
                        # Сохраняем значение для диагностики
                        self.modbus_values[modbus_address] = {
                            'tag': tag,
                            'value': value,
                            'last_update': asyncio.get_event_loop().time()
                        }
                        
                    except Exception as e:
                        logger.debug(f"Error updating Modbus register {modbus_address} for tag {tag.name}: {e}")

    def _pac_tags(self):
        """Все теги с protocol=pac (третий тип контроллера, протокол driver-master)."""
        result = []
        for db in self.data_blocks.values():
            for tag in db.get_all_tags():
                proto = getattr(tag, 'protocol', None)
                if proto is not None and getattr(proto, "value", proto) == "pac":
                    result.append(tag)
        return result

    async def init_pac_server(self):
        """Инициализация PAC-сервера (driver-master). Поднимается только если есть pac-теги."""
        pac_tags = self._pac_tags()
        if not pac_tags:
            return
        # Объектная модель прибор->поля (как у настоящего PAC) — для CMD_GET_DEVICES.
        devmap = {}
        for tag in pac_tags:
            dev = getattr(tag, 'device', None) or tag.name
            entry = devmap.setdefault(dev, {
                'device': dev,
                'dev_type': getattr(tag, 'dev_type', '') or 'DEV',
                'fields': [],
            })
            entry['fields'].append({'field': getattr(tag, 'field', None) or tag.name,
                                    'node_id': tag.address})
        try:
            self.pac_server = PACServer(self.pac_port, pac_name=self.name)
            self.pac_server.set_devices(list(devmap.values()))
            self.pac_server.on_write = self._pac_write
            loop = asyncio.get_event_loop()
            await loop.run_in_executor(None, self.pac_server.start)
            logger.info(f"PAC server started on port {self.pac_port} "
                        f"({len(pac_tags)} tags, {len(devmap)} devices)")
        except Exception as e:
            logger.error(f"Failed to start PAC server: {e}")

    async def update_pac_tags(self):
        """Обновление снимка значений PAC-сервера (node.id -> текущее значение тега)."""
        if not self.pac_server or not self.pac_server.running:
            return
        snapshot = {}
        for tag in self._pac_tags():
            try:
                value = tag.value
                if value is not None:
                    snapshot[tag.address] = value
            except Exception as e:
                logger.debug(f"Error reading PAC tag {tag.address}: {e}")
        self.pac_server.update_snapshot(snapshot)

    def _pac_write(self, device, field, value):
        """Команда записи драйвера (EXEC_DEVICE_COMMAND) -> установить RW-тег device.field."""
        for tag in self._pac_tags():
            if (getattr(tag, 'device', None) == device
                    and getattr(tag, 'field', None) == field
                    and getattr(getattr(tag, 'access', None), 'value', None) == "RW"):
                tag.value = value
                tag._operator_override = True   # защёлкиваемся, см. apply_replay
                return
        logger.warning(f"PAC: RW-тег {device}.{field} не найден — запись отброшена")

    def apply_replay(self):
        """Подставить в теги текущие значения из архива (replay)."""
        if not self.replay:
            return
        offset = self.replay.current_offset()
        for db in self.data_blocks.values():
            for tag in db.get_all_tags():
                if getattr(tag, 'generator', None) != "replay":
                    continue
                # Оператор перебил значение — архив его больше не трогает. Для
                # OPC UA это дублирует латч в update_loop, а для PAC это ЕДИНСТВЕННАЯ
                # защита: там нет обратного чтения узла, и без проверки ручная
                # уставка жила бы до ближайшего цикла реплея.
                if getattr(tag, '_operator_override', False):
                    continue
                # Данные берём по replay_source (может быть общим для нескольких
                # тегов), а не по address — так один архивный сигнал дублируется
                # на много каналов, при этом каждый тег остаётся уникальным узлом.
                src = getattr(tag, 'replay_source', tag.address)
                if not self.replay.has(src):
                    continue
                # Одна архивная серия достаётся многим каналам, и без сдвига они
                # меняются синхронно — на мнемосхеме это сразу видно как подделка.
                # Индивидуальный сдвиг разводит их по времени внутри той же записи.
                shift = getattr(tag, 'replay_offset', 0) or 0
                pos = offset + shift
                if self.replay.duration > 0:
                    pos %= self.replay.duration
                value = self.replay.value_at(src, pos)
                tag.set_replay_value(value)

    async def update_loop(self):
        """Цикл обновления значений"""
        last_time = asyncio.get_event_loop().time()

        while self.running:
            try:
                current_time = asyncio.get_event_loop().time()
                dt = current_time - last_time

                # Обновляем все теги
                update_count = 0
                modbus_update_count = 0

                # Сначала подставляем значения из архива (если включён replay)
                self.apply_replay()

                for db in self.data_blocks.values():
                    db.update_simulation(dt)
                    
                    # Обновляем значения в OPC UA сервере
                    for tag in db.get_all_tags():
                        # В OPC UA пишем только opcua-теги; modbus/pac обслуживают свои серверы.
                        proto = getattr(tag, 'protocol', None)
                        if proto is not None and getattr(proto, "value", proto) != "opcua":
                            if getattr(proto, "value", proto) == "modbus":
                                modbus_update_count += 1
                            continue
                        
                        if hasattr(tag, 'opcua_node') and tag.opcua_node:
                            try:
                                # RW-теги управляются оператором через шлюз: НЕ затираем
                                # их значением генератора, а читаем узел обратно — иначе
                                # записанная команда жила бы 0.5с.
                                is_rw = getattr(getattr(tag, 'access', None), 'value', None) == "RW"
                                if is_rw:
                                    node_val = await tag.opcua_node.read_value()
                                    has_source = getattr(tag, 'generator', None) is not None
                                    if not has_source:
                                        # Чистый актуатор (клапан/мотор) — значение
                                        # целиком за оператором, источника данных нет.
                                        tag.value = node_val
                                    elif tag._operator_override or _operator_touched(node_val, tag._last_pushed):
                                        # Оператор перебил значение — защёлкиваемся на
                                        # ручном и держим его, архив больше не пишем.
                                        tag._operator_override = True
                                        tag.value = node_val
                                    elif hasattr(tag, 'opcua_variant_type'):
                                        # Оператор пока не вмешивался — ведём тег от
                                        # архива/генератора, узел остаётся writable
                                        # (вариант B: живые данные + перебить записью).
                                        corrected_value = self._convert_to_correct_type(
                                            tag.value,
                                            tag.opcua_variant_type
                                        )
                                        await tag.opcua_node.write_value(ua.Variant(
                                            corrected_value,
                                            tag.opcua_variant_type
                                        ))
                                        tag._last_pushed = corrected_value
                                        update_count += 1
                                elif hasattr(tag, 'opcua_variant_type'):
                                    corrected_value = self._convert_to_correct_type(
                                        tag.value,
                                        tag.opcua_variant_type
                                    )
                                    variant = ua.Variant(
                                        corrected_value,
                                        tag.opcua_variant_type
                                    )
                                    await tag.opcua_node.write_value(variant)
                                    update_count += 1
                            except Exception as e:
                                logger.debug(f"Error updating {tag.address}: {e}")
                
                # Обновляем Modbus регистры
                await self.update_modbus_tags()

                # Обновляем снимок значений PAC-сервера (driver-master)
                await self.update_pac_tags()

                self.read_count += update_count
                self.write_count += modbus_update_count
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
            # Загрузка архива для replay (если включён)
            if self.replay:
                self.replay.load()

            # Инициализация OPC UA
            await self.init_opcua_server()
            
            # Инициализация Modbus TCP
            await self.init_modbus_server()

            # Инициализация PAC-сервера (driver-master) — только если есть pac-теги
            await self.init_pac_server()

            # Запуск сервера
            async with self.server:
                logger.info(f"PLC Simulator running at {self.endpoint}")
                logger.info("=" * 50)
                logger.info("Available OPC UA tags:")
                for address in self.opcua_nodes.keys():
                    logger.info(f"  {address}")
                
                # Показываем Modbus теги
                modbus_tags = []
                for db in self.data_blocks.values():
                    for tag in db.get_all_tags():
                        if hasattr(tag, 'protocol') and getattr(tag.protocol, "value", tag.protocol) == "modbus":
                            modbus_address = getattr(tag, 'modbus_address', 'N/A')
                            modbus_type = getattr(tag, 'modbus_type', 'float32')
                            modbus_tags.append(f"  {tag.name} (address={modbus_address}, type={modbus_type})")
                        elif hasattr(tag, 'modbus_address') and tag.modbus_address is not None:
                            modbus_type = getattr(tag, 'modbus_type', 'float32')
                            modbus_tags.append(f"  {tag.name} (address={tag.modbus_address}, type={modbus_type})")
                
                if modbus_tags:
                    logger.info("Available Modbus tags:")
                    for tag_info in modbus_tags:
                        logger.info(tag_info)

                # Показываем PAC теги (driver-master)
                pac_tags = self._pac_tags()
                if pac_tags:
                    logger.info("Available PAC tags (driver-master):")
                    for tag in pac_tags:
                        dev = getattr(tag, 'device', '') or ''
                        fld = getattr(tag, 'field', '') or ''
                        logger.info(f"  {tag.address} ({dev}.{fld})")

                logger.info("=" * 50)
                logger.info("Press Ctrl+C to stop")
                
                self.running = True
                if self.replay:
                    self.replay.start()
                self.update_task = asyncio.create_task(self.update_loop())
                
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
        
        if self.modbus_server:
            try:
                self.modbus_server.stop()
                logger.info("Modbus TCP server stopped")
            except Exception as e:
                logger.error(f"Error stopping Modbus server: {e}")

        if self.pac_server:
            try:
                self.pac_server.stop()
                logger.info("PAC server stopped")
            except Exception as e:
                logger.error(f"Error stopping PAC server: {e}")

        # OPC UA-сервер (asyncua) здесь останавливать НЕ нужно: его жизненным циклом
        # владеет `async with self.server:` в start(), чей __aexit__ сам делает
        # await self.server.stop() при отмене задачи. Прежний явный self.server.stop()
        # был багом — Server.stop() это КОРУТИНА, вызванная без await: она молча
        # отбрасывалась (RuntimeWarning: coroutine never awaited) и ничего не гасила
        # поверх уже остановленного сервера. Modbus/PAC-серверы (выше) — наоборот,
        # не под async with, поэтому их останавливаем здесь явно.
        self.server_running = False
    
    def get_stats(self):
        """Статистика работы"""
        modbus_count = 0
        for db in self.data_blocks.values():
            for tag in db.get_all_tags():
                if hasattr(tag, 'protocol') and getattr(tag.protocol, "value", tag.protocol) == "modbus":
                    modbus_count += 1
                elif hasattr(tag, 'modbus_address') and tag.modbus_address is not None:
                    modbus_count += 1
        
        return {
            'plc_id': self.plc_id,
            'uptime': 'N/A',
            'read_count': self.read_count,
            'write_count': self.write_count,
            'data_blocks': len(self.data_blocks),
            'total_tags': sum(len(db.get_all_tags()) for db in self.data_blocks.values()),
            'opcua_tags': len(self.opcua_nodes),
            'modbus_tags': modbus_count
        }