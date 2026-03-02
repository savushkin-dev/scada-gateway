#!/usr/bin/env python3
"""
PLC Simulator for SCADA testing
Имитирует Siemens S7-1200 контроллер
"""

import asyncio
import logging
import sys
import yaml
import signal
from pathlib import Path

from core.plc import PLCSimulator

# Настройка логирования
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger(__name__)

class SimulatorApplication:
    """Главное приложение симулятора"""
    
    def __init__(self, config_path: str = "config/plc_config.yaml"):
        self.config_path = config_path
        self.plc = None
        self.running = False
        self.shutdown_event = asyncio.Event()
        
    def load_config(self):
        """Загрузить конфигурацию"""
        try:
            config_path = Path(__file__).parent / self.config_path
            with open(config_path, 'r') as f:
                config = yaml.safe_load(f)
            logger.info(f"Loaded configuration from {config_path}")
            return config
        except Exception as e:
            logger.error(f"Failed to load config: {e}")
            sys.exit(1)
    
    def handle_signal(self, sig):
        """Обработчик сигналов"""
        logger.info(f"Received signal {sig.name}")
        self.shutdown_event.set()
    
    async def shutdown(self):
        """Корректное завершение"""
        logger.info("Shutting down simulator...")
        self.running = False
        
        if self.plc:
            await self.plc.stop()
        
        logger.info("Simulator stopped")
    
    async def run(self):
        """Запуск приложения"""
        # Загружаем конфиг
        config = self.load_config()
        
        # Создаем симулятор PLC
        self.plc = PLCSimulator(config)
        
        # Загружаем Data Blocks
        self.plc.load_configuration(config['plc']['data_blocks'])
        
        # Выводим информацию
        logger.info("=" * 50)
        logger.info(f"PLC Simulator: {self.plc.name}")
        logger.info(f"PLC ID: {self.plc.plc_id}")
        logger.info(f"Endpoint: {self.plc.endpoint}")
        logger.info(f"Update rate: {self.plc.update_rate}s")
        
        stats = self.plc.get_stats()
        logger.info(f"Data Blocks: {stats['data_blocks']}")
        logger.info(f"Total tags: {stats['total_tags']}")
        logger.info("=" * 50)
        
        self.running = True
        
        # Устанавливаем обработчики сигналов
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(
                sig,
                lambda s=sig: self.handle_signal(s)
            )
        
        try:
            # Запускаем PLC в отдельной задаче
            plc_task = asyncio.create_task(self.plc.start())
            
            # Ждем сигнала завершения
            await self.shutdown_event.wait()
            
            # Отменяем задачу PLC
            plc_task.cancel()
            try:
                await plc_task
            except asyncio.CancelledError:
                pass
            
        except Exception as e:
            logger.error(f"Error during execution: {e}")
        finally:
            await self.shutdown()

async def main():
    """Точка входа"""
    app = SimulatorApplication()
    await app.run()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Received keyboard interrupt")
    except Exception as e:
        logger.error(f"Fatal error: {e}")
        sys.exit(1)