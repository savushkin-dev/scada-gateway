from typing import Dict, List
from .tag import Tag
import logging

logger = logging.getLogger(__name__)

class DataBlock:
    """Имитация Data Block (DB) контроллера Siemens"""
    
    def __init__(self, db_number: int, name: str, tags_config: List[dict]):
        self.db_number = db_number
        self.name = name
        self.tags: Dict[str, Tag] = {}
        
        # Создаем теги
        for tag_config in tags_config:
            tag = Tag(tag_config)
            self.tags[tag.name] = tag
            
        logger.info(f"Created DB{db_number}.{name} with {len(self.tags)} tags")
    
    def get_tag_by_address(self, address: str) -> Tag:
        """Получить тег по адресу (например 'DB1.Speed')"""
        expected = f"DB{self.db_number}.{address}"
        for tag in self.tags.values():
            if tag.address == expected:
                return tag
        return None
    
    def get_all_tags(self) -> List[Tag]:
        """Получить все теги DB"""
        return list(self.tags.values())
    
    def update_simulation(self, dt: float):
        """Обновить все теги в DB"""
        for tag in self.tags.values():
            tag.update_simulation(dt)
    
    def to_dict(self):
        """Для диагностики"""
        return {
            'db_number': self.db_number,
            'name': self.name,
            'tags': {name: tag.value for name, tag in self.tags.items()}
        }