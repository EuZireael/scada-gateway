package com.scada.gateway.pac;

import org.luaj.vm2.Globals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;

/**
 * Одно TCP-соединение с PAC-контроллером (протокол driver-master). Держит сокет и свой
 * Lua-стейт: присланные контроллером Lua-ответы исполняются в стейте, значения тегов
 * читаются из таблицы {@code tags}. Запросы синхронные (запрос → ответ), поэтому методы
 * потокобезопасны через synchronized (как в оригинальном tcp_cmmctr с критической секцией).
 *
 * <p>Цикл: {@link #connect()} → {@link #handshake()} (версия/имя) → {@link #pollStates()}
 * (снимок всех состояний) → {@link #readValue} по каждому тегу. Запись — {@link #writeCommand}.
 */
public class PacConnection {

    private static final Logger log = LoggerFactory.getLogger(PacConnection.class);

    private final String host;
    private final int port;
    private final int timeoutMs;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Globals lua;
    private int pidx = 0;
    private boolean handshaked = false;

    public PacConnection(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /** Открыть сокет и завести чистый Lua-стейт. */
    public synchronized void connect() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);          // чтение не должно висеть вечно («тихая» смерть)
        s.setTcpNoDelay(true);
        this.socket = s;
        this.in = s.getInputStream();
        this.out = s.getOutputStream();
        this.lua = PacLua.newState();
        this.handshaked = false;
        this.pidx = 0;
    }

    /** Закрыть соединение (тихо). */
    public synchronized void close() {
        handshaked = false;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignore) { }
            socket = null;
        }
    }

    /** GET_INFO_ON_CONNECT: версия протокола и имя PAC. Исполняем ответ в Lua-стейте. */
    public synchronized void handshake() throws IOException {
        byte[] info = request(PacProtocol.CMD_GET_INFO_ON_CONNECT, null);
        PacLua.exec(lua, new String(info, StandardCharsets.UTF_8));
        int ver = PacLua.protocolVersion(lua);
        if (ver != PacProtocol.PROTOCOL_VERSION) {
            log.warn("PAC {}:{} версия протокола {} (поддерживается {})",
                    host, port, ver, PacProtocol.PROTOCOL_VERSION);
        }
        handshaked = true;
    }

    /** GET_DEVICES_STATES: снимок состояний всех устройств → исполнить Lua (наполняет tags). */
    public synchronized void pollStates() throws IOException {
        if (!handshaked) handshake();
        byte[] body = request(PacProtocol.CMD_GET_DEVICES_STATES, null);
        // Первые 2 байта тела — devices_request_id, дальше Lua-текст.
        int off = body.length >= PacProtocol.LUA_START_OFFSET ? PacProtocol.LUA_START_OFFSET : 0;
        PacLua.exec(lua, new String(body, off, body.length - off, StandardCharsets.UTF_8));
    }

    /** Значение тега из таблицы tags по ключу (channelId), приведённое к dataType. */
    public synchronized Object readValue(String key, String dataType) {
        return lua == null ? null : PacLua.read(lua, key, dataType);
    }

    /** EXEC_DEVICE_COMMAND: команда записи в PAC как Lua-строка set_cmd (актуатор). */
    public synchronized void writeCommand(String device, String field, Object value) throws IOException {
        String cmd = "__" + device + ":set_cmd('" + field + "', 1, " + PacLua.scalar(value) + ")";
        request(PacProtocol.CMD_EXEC_DEVICE_COMMAND, cmd.getBytes(StandardCharsets.UTF_8));
    }

    // --------------------------------------------------------------- транспорт --

    /** Отправить команду, получить и РАСПАКОВАТЬ тело ответа. */
    private byte[] request(int cmd, byte[] extra) throws IOException {
        pidx = (pidx + 1) & 0xFF;
        byte[] payload;
        if (extra == null || extra.length == 0) {
            payload = new byte[]{(byte) cmd};
        } else {
            payload = new byte[1 + extra.length];
            payload[0] = (byte) cmd;
            System.arraycopy(extra, 0, payload, 1, extra.length);
        }

        out.write(PacProtocol.buildRequest(pidx, payload));
        out.flush();

        byte[] hdr = readN(PacProtocol.RESPONSE_HEADER_LEN);
        if (hdr[0] != PacProtocol.NET_ID) {
            throw new IOException("PAC: неверный заголовок ответа");
        }
        int status = hdr[1] & 0xFF;
        int rpidx = hdr[2] & 0xFF;
        int alen = ((hdr[3] & 0xFF) << 8) | (hdr[4] & 0xFF);
        byte[] body = alen > 0 ? readN(alen) : new byte[0];
        if (status == PacProtocol.STATUS_ERROR) {
            throw new IOException("PAC вернул статус ошибки на команду " + cmd);
        }
        if (rpidx != pidx) {
            throw new IOException("PAC: рассинхрон пакета (ждали pidx=" + pidx + ", пришёл " + rpidx + ")");
        }
        if (body.length == 0) return body;
        try {
            return PacProtocol.inflate(body, 0, body.length);
        } catch (DataFormatException e) {
            throw new IOException("PAC: ошибка zlib-распаковки ответа", e);
        }
    }

    /** Прочитать РОВНО n байт (TCP может отдавать частями). EOF → исключение. */
    private byte[] readN(int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) throw new EOFException("PAC: соединение закрыто до получения ответа");
            read += r;
        }
        return buf;
    }
}
