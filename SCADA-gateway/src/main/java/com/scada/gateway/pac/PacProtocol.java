package com.scada.gateway.pac;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Проводной протокол driver-master (PAC-контроллеры Savushkin/ptusa): константы кадра,
 * коды команд и чистые помощники (сборка запроса, zlib-распаковка тела). Оригинал —
 * C++ в папке {@code driver-master/} (класс tcp_cmmctr). Поддерживаем версию 104
 * (zlib + UTF-8); QuickLZ (легаси v102) не реализуем.
 *
 * <p>Кадр ЗАПРОСА (6 байт заголовка): {@code 's', ServiceID, FrameSingle, pidx, lenHi, lenLo}
 * + payload. Кадр ОТВЕТА (5 байт заголовка): {@code 's', status, pidx, lenHi, lenLo} + zlib(body).
 */
public final class PacProtocol {

    private PacProtocol() {
        // Утилитный класс — не инстанцируем.
    }

    /** Версия протокола: 104 = zlib + UTF-8 (текущая у реальных PAC). */
    public static final int PROTOCOL_VERSION = 104;

    public static final byte NET_ID = 's';          // магический байт кадра (заголовок[0]).
    public static final int SERVICE_ID = 1;          // PAC_CMMCTR_SERVICE_ID.
    public static final int FRAME_SINGLE = 1;        // тип кадра (одиночный).
    public static final int STATUS_ERROR = 7;        // ответ[1] == 7 → ошибка на стороне PAC.
    public static final int REQUEST_HEADER_LEN = 6;
    public static final int RESPONSE_HEADER_LEN = 5;

    // Смещение Lua-текста в теле ответов devices/states: первые 2 байта — devices_request_id.
    public static final int LUA_START_OFFSET = 2;

    // Коды команд (device_communicator::CMD).
    public static final int CMD_GET_INFO_ON_CONNECT = 10;
    public static final int CMD_GET_DEVICES = 100;
    public static final int CMD_GET_DEVICES_STATES = 101;
    public static final int CMD_EXEC_DEVICE_COMMAND = 102;

    /**
     * Собрать кадр запроса: {@code 's', ServiceID, FrameSingle, pidx, BE16-длина, payload}.
     * pidx — идентификатор пакета (счётчик), PAC обязан вернуть его в ответе.
     */
    public static byte[] buildRequest(int pidx, byte[] payload) {
        int len = payload.length;
        byte[] frame = new byte[REQUEST_HEADER_LEN + len];
        frame[0] = NET_ID;
        frame[1] = (byte) SERVICE_ID;
        frame[2] = (byte) FRAME_SINGLE;
        frame[3] = (byte) pidx;
        frame[4] = (byte) ((len >> 8) & 0xFF);       // длина payload, старший байт (BE16)
        frame[5] = (byte) (len & 0xFF);
        System.arraycopy(payload, 0, frame, REQUEST_HEADER_LEN, len);
        return frame;
    }

    /**
     * zlib-распаковка тела ответа. C++-драйвер жмёт тело zlib ({@code compress2}) и
     * распаковывает {@code uncompress} — это стандартный zlib-формат, который читает
     * {@link Inflater} (без nowrap).
     */
    public static byte[] inflate(byte[] data, int offset, int length) throws DataFormatException {
        Inflater inflater = new Inflater();
        inflater.setInput(data, offset, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, length * 3));
        byte[] buf = new byte[4096];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;   // данных больше нет — выходим (защита от бесконечного цикла)
                }
                out.write(buf, 0, n);
            }
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
