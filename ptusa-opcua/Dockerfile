# OPC UA-фасад эмулятора мойки (bridge.py). Сама прошивка ptusa — отдельный контейнер
# (debian:stable: glibc ≥ 2.38), фасад — Python 3.11 (asyncua 1.1.8), поэтому два сервиса.
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY bridge.py .
# asyncua слушает на хосте из OPCUA_ENDPOINT (не на 127.0.0.1) — проверка стучится на свой хост.
EXPOSE 4840
HEALTHCHECK --interval=10s --timeout=5s --retries=6 --start-period=10s \
  CMD python -c "import socket; socket.create_connection((socket.gethostname(), 4840), 2).close()"
CMD ["python", "-u", "bridge.py"]
