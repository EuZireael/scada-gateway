from typing import Dict, List
from asyncua import Server, ua
import asyncio
import logging
from .data_block import DataBlock
from .tag import Tag
from .modbus_server import ModbusServer

logger = logging.getLogger(__name__)

class PLCSimulator:
    """Главный класс симулятора контроллера с поддержкой OPC UA и Modbus TCP"""
    
    def __init__(self, config: dict):
        self.config = config
        self.plc_id = config['plc']['id']
        self.name = config['plc']['name']
        self.endpoint = config['plc']['endpoint']
        self.update_rate = config['plc']['update_rate']
        
        # Modbus порт (по умолчанию 5020)
        self.modbus_port = config.get('modbus_port', 5020)
        self.modbus_server = None
        
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
            
            # Инициализируем Modbus значения
            for tag in db.get_all_tags():
                if hasattr(tag, 'protocol') and tag.protocol == "modbus" and tag.modbus_address is not None:
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
                
                # Добавляем теги
                for tag in db.get_all_tags():
                    # Пропускаем Modbus теги для OPC UA
                    if hasattr(tag, 'protocol') and tag.protocol == "modbus":
                        continue
                    node = await self._add_tag_to_server(db_node, tag)
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
            variant_type = self._get_variant_type(tag)
            initial_value = self._convert_to_correct_type(tag._value, variant_type)
            variant = ua.Variant(initial_value, variant_type)
            
            var = await parent_node.add_variable(
                self.namespace_idx,
                tag.name,
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
                
                if hasattr(tag, 'protocol') and tag.protocol == "modbus":
                    is_modbus = True
                    modbus_address = getattr(tag, 'modbus_address', None)
                    modbus_type = getattr(tag, 'modbus_type', 'float32')
                elif hasattr(tag, 'modbus_address') and tag.modbus_address is not None:
                    is_modbus = True
                    modbus_address = tag.modbus_address
                    modbus_type = getattr(tag, 'modbus_type', 'float32')
                
                if is_modbus and modbus_address is not None:
                    try:
                        value = tag.value
                        if value is None:
                            continue
                        
                        # Конвертируем значение в правильный тип
                        if modbus_type == "float32":
                            self.modbus_server.update_register(modbus_address, value, "float32")
                        elif modbus_type == "int16":
                            self.modbus_server.update_register(modbus_address, int(value), "int16")
                        elif modbus_type == "uint16":
                            self.modbus_server.update_register(modbus_address, int(value), "uint16")
                        elif modbus_type == "bool":
                            self.modbus_server.update_register(modbus_address, 1 if value else 0, "bool")
                        
                        # Сохраняем значение для диагностики
                        self.modbus_values[modbus_address] = {
                            'tag': tag,
                            'value': value,
                            'last_update': asyncio.get_event_loop().time()
                        }
                        
                    except Exception as e:
                        logger.debug(f"Error updating Modbus register {modbus_address} for tag {tag.name}: {e}")
    
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
                
                for db in self.data_blocks.values():
                    db.update_simulation(dt)
                    
                    # Обновляем значения в OPC UA сервере
                    for tag in db.get_all_tags():
                        # Пропускаем Modbus теги для OPC UA
                        if hasattr(tag, 'protocol') and tag.protocol == "modbus":
                            modbus_update_count += 1
                            continue
                        
                        if hasattr(tag, 'opcua_node') and tag.opcua_node:
                            try:
                                if hasattr(tag, 'opcua_variant_type'):
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
            # Инициализация OPC UA
            await self.init_opcua_server()
            
            # Инициализация Modbus TCP
            await self.init_modbus_server()
            
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
                        if hasattr(tag, 'protocol') and tag.protocol == "modbus":
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
                
                logger.info("=" * 50)
                logger.info("Press Ctrl+C to stop")
                
                self.running = True
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
        
        if self.server_running:
            try:
                self.server.stop()
                logger.info("OPC UA server stopped")
            except Exception as e:
                logger.error(f"Error stopping server: {e}")
        
        self.server_running = False
    
    def get_stats(self):
        """Статистика работы"""
        modbus_count = 0
        for db in self.data_blocks.values():
            for tag in db.get_all_tags():
                if hasattr(tag, 'protocol') and tag.protocol == "modbus":
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