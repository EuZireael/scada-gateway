#!/usr/bin/env python3
"""
Мини-«драйвер» PAC (протокол driver-master) — проверка pac-сервера симулятора И семя
будущего Java/LuaJ-клиента шлюза. Подключается к PAC-серверу, делает handshake
(GET_INFO), просит объектную модель (GET_DEVICES) и состояния (GET_DEVICES_STATES),
распаковывает zlib и печатает Lua; значения тегов вытаскивает наивным разбором Lua.

    python3 tools/pac_probe.py [host] [port]      # по умолчанию localhost:10000

Кадр запроса : 's' ServiceID FrameType pidx lenHi lenLo <payload>   (заголовок 6 байт)
Кадр ответа  : 's' status pidx lenHi lenLo <zlib(body)>             (заголовок 5 байт)
Тело devices/states после распаковки = [2 байта request_id LE] + Lua-текст.
"""
import re
import socket
import struct
import sys
import zlib

CMD_GET_INFO_ON_CONNECT = 10
CMD_GET_DEVICES = 100
CMD_GET_DEVICES_STATES = 101
NET_ID = ord('s')
SERVICE_ID = 1
FRAME_SINGLE = 1


class PacProbe:
    """Синхронный клиент driver-master: одно соединение, запрос → ответ."""

    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=3)
        self.pidx = 0

    def _recvall(self, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise IOError("соединение закрыто до получения полного ответа")
            buf.extend(chunk)
        return bytes(buf)

    def _request(self, cmd, extra=b""):
        """Отправить команду, получить и распаковать тело ответа (bytes)."""
        self.pidx = (self.pidx + 1) & 0xFF
        payload = bytes([cmd]) + extra
        length = len(payload)
        header = bytes([NET_ID, SERVICE_ID, FRAME_SINGLE, self.pidx,
                        (length >> 8) & 0xFF, length & 0xFF])
        self.sock.sendall(header + payload)

        rhdr = self._recvall(5)
        if rhdr[0] != NET_ID:
            raise IOError(f"неверный заголовок ответа: {rhdr!r}")
        status, rpidx = rhdr[1], rhdr[2]
        alen = (rhdr[3] << 8) | rhdr[4]
        body = self._recvall(alen) if alen else b""
        if status == 7:
            raise IOError("PAC вернул статус ошибки (7)")
        if rpidx != self.pidx:
            raise IOError(f"рассинхрон pidx: ждал {self.pidx}, пришёл {rpidx}")
        return zlib.decompress(body) if body else b""

    def get_info(self):
        return self._request(CMD_GET_INFO_ON_CONNECT).decode("utf-8", "replace")

    def get_devices(self):
        # devices_request_id (2 байта LE) драйвер шлёт в запросе; PAC вернёт свой.
        body = self._request(CMD_GET_DEVICES, struct.pack("<H", 0))
        rid = struct.unpack("<H", body[:2])[0]
        return rid, body[2:].decode("utf-8", "replace")

    def get_states(self):
        body = self._request(CMD_GET_DEVICES_STATES)
        rid = struct.unpack("<H", body[:2])[0]
        return rid, body[2:].decode("utf-8", "replace")

    def close(self):
        self.sock.close()


def parse_tags(lua):
    """Наивный разбор tags['id']=value из Lua (для наглядности)."""
    return {m.group(1): m.group(2)
            for m in re.finditer(r"tags\['([^']+)'\]=([^\n]+)", lua)}


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "localhost"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 10000

    probe = PacProbe(host, port)
    print(f"=== connected {host}:{port} ===\n")

    print("--- GET_INFO_ON_CONNECT ---")
    print(probe.get_info())

    rid, devices = probe.get_devices()
    print(f"--- GET_DEVICES (request_id={rid}) ---")
    print(devices)

    rid, states = probe.get_states()
    print(f"--- GET_DEVICES_STATES (request_id={rid}) ---")
    print(states)

    print("--- разобранные значения тегов ---")
    for node_id, value in parse_tags(states).items():
        print(f"  {node_id} = {value}")

    probe.close()


if __name__ == "__main__":
    main()
