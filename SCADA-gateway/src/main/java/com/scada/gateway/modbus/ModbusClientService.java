package com.scada.gateway.modbus;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.io.ModbusTCPTransaction;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersRequest;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersResponse;
import com.ghgande.j2mod.modbus.net.TCPMasterConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModbusClientService {

    private static final Logger log = LoggerFactory.getLogger(ModbusClientService.class);

    private final ConcurrentHashMap<String, TCPMasterConnection> connections = new ConcurrentHashMap<>();

    /**
     * Чтение FLOAT (2 регистра, Holding Registers / FC03)
     * Использует little-endian порядок байт (как в Python)
     */
    public Float readFloat(String host, int port, int address, int unitId) {
        String key = host + ":" + port;
        TCPMasterConnection connection = getConnection(key, host, port);

        if (connection == null) {
            log.error("❌ Modbus connection is null for {}:{}", host, port);
            return null;
        }

        try {
            synchronized (connection) {
                if (!connection.isConnected()) {
                    connection.connect();
                }

                // Преобразуем 40001 → 0
                int modbusAddress = address - 40001;

                if (modbusAddress < 0) {
                    log.error("❌ Invalid Modbus address: {}", address);
                    return null;
                }

                ReadMultipleRegistersRequest request =
                        new ReadMultipleRegistersRequest(modbusAddress, 2);

                request.setUnitID(unitId);

                ModbusTCPTransaction transaction =
                        new ModbusTCPTransaction(connection);

                transaction.setRequest(request);
                transaction.execute();

                ReadMultipleRegistersResponse response =
                        (ReadMultipleRegistersResponse) transaction.getResponse();

                if (response != null && response.getWordCount() >= 2) {

                    int reg1 = response.getRegisterValue(0);
                    int reg2 = response.getRegisterValue(1);

                    // little-endian (как в Python struct.pack('<f', value))
                    int littleEndian = (reg2 << 16) | (reg1 & 0xFFFF);
                    float valueLE = Float.intBitsToFloat(littleEndian);

                    log.debug("📡 Modbus raw [{}]: reg1={}, reg2={}", address, reg1, reg2);
                    log.debug("📡 LE={}", valueLE);

                    return valueLE;
                } else {
                    log.warn("⚠️ Empty Modbus response for address {}", address);
                }
            }

        } catch (ModbusException e) {
            log.error("❌ Modbus exception {}:{} addr {} -> {}", host, port, address, e.getMessage());
            resetConnection(key, connection);
        } catch (Exception e) {
            log.error("❌ General error {}:{} addr {} -> {}", host, port, address, e.getMessage());
            resetConnection(key, connection);
        }

        return null;
    }

    /**
     * Чтение INT16 (1 регистр)
     */
    public Integer readInt16(String host, int port, int address, int unitId) {
        String key = host + ":" + port;
        TCPMasterConnection connection = getConnection(key, host, port);

        if (connection == null) {
            log.error("❌ Modbus connection is null for {}:{}", host, port);
            return null;
        }

        try {
            synchronized (connection) {
                if (!connection.isConnected()) {
                    connection.connect();
                }

                int modbusAddress = address - 40001;

                if (modbusAddress < 0) {
                    log.error("❌ Invalid Modbus address: {}", address);
                    return null;
                }

                ReadMultipleRegistersRequest request =
                        new ReadMultipleRegistersRequest(modbusAddress, 1);

                request.setUnitID(unitId);

                ModbusTCPTransaction transaction =
                        new ModbusTCPTransaction(connection);

                transaction.setRequest(request);
                transaction.execute();

                ReadMultipleRegistersResponse response =
                        (ReadMultipleRegistersResponse) transaction.getResponse();

                if (response != null && response.getWordCount() >= 1) {
                    int value = response.getRegisterValue(0);
                    log.debug("📡 Modbus INT16 [{}] = {}", address, value);
                    return value;
                }
            }

        } catch (Exception e) {
            log.error("❌ Modbus INT16 error: {}", e.getMessage());
            resetConnection(key, connection);
        }

        return null;
    }

    /**
     * Чтение BOOL из регистра (проверяем младший бит)
     */
    public Boolean readBoolean(String host, int port, int address, int unitId) {
        Integer intValue = readInt16(host, port, address, unitId);
        if (intValue != null) {
            return (intValue & 0x01) != 0;
        }
        return null;
    }

    /**
     * Получение или создание соединения
     */
    private TCPMasterConnection getConnection(String key, String host, int port) {
        return connections.computeIfAbsent(key, k -> {
            try {
                log.info("📡 Creating Modbus connection to {}:{}", host, port);
                InetAddress addr = InetAddress.getByName(host);
                TCPMasterConnection connection = new TCPMasterConnection(addr);
                connection.setPort(port);
                connection.connect();
                log.info("✅ Connected to Modbus {}:{}", host, port);
                return connection;
            } catch (Exception e) {
                log.error("❌ Failed to connect to {}:{} -> {}", host, port, e.getMessage());
                return null;
            }
        });
    }

    /**
     * Сброс соединения
     */
    private void resetConnection(String key, TCPMasterConnection connection) {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Exception e) {
            log.debug("Error closing connection: {}", e.getMessage());
        }
        connections.remove(key);
    }

    /**
     * Закрыть все соединения
     */
    public void disconnectAll() {
        for (var entry : connections.entrySet()) {
            try {
                entry.getValue().close();
                log.info("Disconnected: {}", entry.getKey());
            } catch (Exception e) {
                log.warn("Error disconnecting: {}", e.getMessage());
            }
        }
        connections.clear();
    }
}