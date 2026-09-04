# ============================================================================
# Образ симулятора PLC (Python). Поднимает OPC UA (:4840) и Modbus TCP (:5020),
# проигрывает архив BN1_MCA1 в реальном времени.
#
# Python 3.11: asyncua 1.1.8 / pymodbus 3.7.4 работают стабильно
# (на 3.14 у asyncua-клиента баг issubclass — на 3.11 его нет).
# ============================================================================
FROM python:3.14-slim
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Весь симулятор, включая config/ и data/archive_replay.pkl.gz (нужен для replay).
COPY . .

# OPC UA + Modbus TCP
EXPOSE 4840 5020

# OPCUA_ENDPOINT задаётся в docker-compose.yml (opc.tcp://simulator:4840),
# чтобы сервер анонсировал резолвимое в сети имя.
CMD ["python", "simulator.py", "config/replay_config.yaml"]
