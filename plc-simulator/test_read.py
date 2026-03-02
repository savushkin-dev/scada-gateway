#!/usr/bin/env python3
"""
Тестовый клиент для проверки OPC UA сервера
"""

import asyncio
from asyncua import Client
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

async def test_read():
    """Тест чтения данных с симулятора"""
    url = "opc.tcp://localhost:4840"
    
    try:
        async with Client(url=url) as client:
            logger.info(f"Connected to {url}")
            
            # Получаем корневой узел
            root = client.get_root_node()
            objects = client.get_objects_node()
            
            # Ищем наш PLC
            logger.info("Searching for PLC node...")
            plc_node = None
            
            # Просматриваем все объекты
            children = await objects.get_children()
            for child in children:
                browse_name = await child.read_browse_name()
                logger.info(f"Found node: {browse_name}")
                if "S7-1200-SIM-001" in str(browse_name):
                    plc_node = child
                    logger.info(f"Found PLC node: {browse_name}")
                    break
            
            if not plc_node:
                logger.error("PLC node not found")
                return
            
            # Получаем все Data Blocks
            db_nodes = await plc_node.get_children()
            logger.info(f"Found {len(db_nodes)} Data Blocks")
            
            # Читаем все теги
            for db_node in db_nodes:
                db_name = await db_node.read_browse_name()
                logger.info(f"\nReading from {db_name}:")
                
                tags = await db_node.get_children()
                for tag in tags:
                    try:
                        tag_name = await tag.read_browse_name()
                        value = await tag.read_value()
                        
                        # Пробуем прочитать описание/единицы измерения
                        try:
                            description = await tag.read_description()
                            unit = description.Text if description else ""
                        except:
                            unit = ""
                        
                        logger.info(f"  {tag_name} = {value} {unit}")
                        
                    except Exception as e:
                        logger.error(f"Error reading tag: {e}")
            
            # Пробуем записать в RW тег (например, Speed)
            logger.info("\nTesting write operation...")
            try:
                # Находим тег Speed
                for db_node in db_nodes:
                    tags = await db_node.get_children()
                    for tag in tags:
                        tag_name = await tag.read_browse_name()
                        if tag_name.Name == "Speed":
                            # Читаем текущее значение
                            current = await tag.read_value()
                            logger.info(f"Current Speed: {current}")
                            
                            # Пробуем записать новое значение
                            new_value = current + 100
                            await tag.write_value(new_value)
                            logger.info(f"Wrote new value: {new_value}")
                            
                            # Проверяем что записалось
                            verified = await tag.read_value()
                            logger.info(f"Verified value: {verified}")
                            break
            except Exception as e:
                logger.error(f"Write test failed: {e}")
            
    except Exception as e:
        logger.error(f"Connection failed: {e}")

async def test_subscription():
    """Тест подписки на изменения"""
    url = "opc.tcp://localhost:4840"
    
    async with Client(url=url) as client:
        logger.info(f"Testing subscription on {url}")
        
        # Создаем подписку
        subscription = await client.create_subscription(100, None)
        
        # Находим тег Temperature
        objects = client.get_objects_node()
        plc_node = await objects.get_child(["2:S7-1200-SIM-001"])
        db_node = await plc_node.get_child(["2:DB1_MotorControl"])
        temp_node = await db_node.get_child(["2:Temperature"])
        
        # Переменная для хранения значений
        values = []
        
        # Функция обратного вызова
        def data_change_handler(node, val, data):
            values.append(val)
            logger.info(f"Temperature changed: {val}")
            if len(values) >= 5:
                asyncio.create_task(subscription.delete())
        
        # Подписываемся
        await subscription.subscribe_data_change(temp_node)
        
        # Ждем немного
        await asyncio.sleep(10)

async def main():
    """Главная функция"""
    logger.info("=" * 50)
    logger.info("Testing OPC UA Simulator")
    logger.info("=" * 50)
    
    # Тест чтения
    await test_read()
    
    # Тест подписки (опционально)
    # await test_subscription()

if __name__ == "__main__":
    asyncio.run(main())