package com.scada.gateway.pac;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Клиент PAC против фейкового PAC в процессе — повторяет поведение эмулятора ptusa:
 * приветствие «PAC accept», статус 12, zlib-тела, снимок {@code t} по имени прибора и
 * код результата команды записи. Без внешнего стенда, поэтому гоняется в CI.
 */
class PacConnectionTest {

    private static final String INFO = "protocol_version = 104; PAC_name = \"FAKE\"; params_CRC=0;";
    private static final String STATES = "t=\n\t{\n\tLINE1V0={M=0, ST=1},\n\t}\n"
            + "t.OBJECT1 = t.OBJECT1 or {}\nt.OBJECT1=\n\t{\n\tRT_PAR_F=\n\t\t{\n\t\t0, 2.5,\n\t\t},\n\t}\n";

    private FakePac pac;

    @AfterEach
    void stop() throws IOException {
        if (pac != null) pac.close();
    }

    @Test
    void readsSnapshotAfterBanner() throws Exception {
        pac = new FakePac(true);
        PacConnection conn = new PacConnection("127.0.0.1", pac.port(), 2000);
        try {
            conn.connect();
            conn.pollStates();
            assertEquals(1L, conn.readValue("LINE1V0", "ST", "INT32"));
            assertEquals(2.5, (Double) conn.readValue("OBJECT1", "RT_PAR_F[2]", "FLOAT"), 1e-9);
        } finally {
            conn.close();
        }
    }

    @Test
    void failsWithoutBanner() throws Exception {
        pac = new FakePac(false);
        PacConnection conn = new PacConnection("127.0.0.1", pac.port(), 500);
        assertThrows(IOException.class, conn::connect, "без приветствия PAC соединение не принимается");
        assertFalse(conn.isConnected());
    }

    @Test
    void writeCommand_appliedAndRejected() throws Exception {
        pac = new FakePac(true);
        PacConnection conn = new PacConnection("127.0.0.1", pac.port(), 2000);
        try {
            conn.connect();
            conn.writeCommand("LINE1V0", "M", 1L);
            assertEquals("__LINE1V0:set_cmd('M', 1, 1)", pac.commands.get(0));

            IOException e = assertThrows(IOException.class, () -> conn.writeCommand("NO_SUCH", "M", 1L));
            assertTrue(e.getMessage().contains("код 1"), e.getMessage());
        } finally {
            conn.close();
        }
    }

    /** Однопоточный фейковый PAC: одно соединение, кадры driver-master как у ptusa. */
    private static final class FakePac implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private final Thread thread;

        FakePac(boolean banner) throws IOException {
            thread = new Thread(() -> serve(banner), "fake-pac");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve(boolean banner) {
            try (Socket s = server.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                if (banner) out.write(PacProtocol.BANNER);
                else out.write(new byte[]{'s', 12, 1, 0, 0});   // сразу кадр, без приветствия
                out.flush();
                while (true) {
                    byte[] hdr = new byte[PacProtocol.REQUEST_HEADER_LEN];
                    in.readFully(hdr);
                    byte[] payload = new byte[((hdr[4] & 0xFF) << 8) | (hdr[5] & 0xFF)];
                    in.readFully(payload);
                    out.write(response(hdr[3], payload));
                    out.flush();
                }
            } catch (IOException ignore) {
                // клиент закрыл соединение или тест закрыл сервер
            }
        }

        private byte[] response(byte pidx, byte[] payload) {
            int cmd = payload[0] & 0xFF;
            byte[] body = switch (cmd) {
                case PacProtocol.CMD_GET_INFO_ON_CONNECT -> INFO.getBytes(StandardCharsets.UTF_8);
                case PacProtocol.CMD_GET_DEVICES_STATES -> withRequestId(STATES);
                case PacProtocol.CMD_EXEC_DEVICE_COMMAND -> {
                    String lua = new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
                    commands.add(lua);
                    yield lua.startsWith("__LINE1V0:") ? new byte[]{0, 0} : new byte[]{1, 0};
                }
                default -> new byte[0];
            };
            byte[] packed = body.length == 0 ? body : zlib(body);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write('s');
            frame.write(12);
            frame.write(pidx);
            frame.write(packed.length >> 8);
            frame.write(packed.length & 0xFF);
            frame.writeBytes(packed);
            return frame.toByteArray();
        }

        private static byte[] withRequestId(String lua) {
            byte[] text = lua.getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[text.length + 2];
            System.arraycopy(text, 0, body, 2, text.length);
            return body;
        }

        private static byte[] zlib(byte[] data) {
            Deflater deflater = new Deflater();
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf));
            deflater.end();
            return out.toByteArray();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
