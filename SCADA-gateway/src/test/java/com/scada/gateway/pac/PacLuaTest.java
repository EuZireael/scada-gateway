package com.scada.gateway.pac;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;

import static org.junit.jupiter.api.Assertions.*;

/** Извлечение значений тегов из Lua-ответа PAC (как это делает драйвер через свой Lua). */
class PacLuaTest {

    @Test
    void readValues_byDataType() {
        Globals g = PacLua.newState();
        PacLua.exec(g, "tags={}\ntags['9001']=1\ntags['9002']=22.5\ntags['9006']=0\n");

        assertEquals(Boolean.TRUE, PacLua.read(g, "9001", "BOOLEAN"));
        assertEquals(Boolean.FALSE, PacLua.read(g, "9006", "BOOLEAN"));
        assertEquals(22.5, (Double) PacLua.read(g, "9002", "FLOAT"), 1e-9);
    }

    @Test
    void intTruncatesToLong() {
        Globals g = PacLua.newState();
        PacLua.exec(g, "tags={}\ntags['5']=1497.7\n");
        assertEquals(1497L, PacLua.read(g, "5", "INT"));
    }

    @Test
    void missingKeyOrTable_null() {
        Globals g = PacLua.newState();
        assertNull(PacLua.read(g, "1", "FLOAT"), "нет таблицы tags → null");
        PacLua.exec(g, "tags={}\n");
        assertNull(PacLua.read(g, "9999", "FLOAT"), "нет ключа → null");
    }

    @Test
    void protocolVersion_fromInfo() {
        Globals g = PacLua.newState();
        PacLua.exec(g, "protocol_version=104\nPAC_name='PAC_DEMO'\nparams_CRC=0\n");
        assertEquals(104, PacLua.protocolVersion(g));
    }

    @Test
    void scalar_boolAsNumber() {
        assertEquals("1", PacLua.scalar(Boolean.TRUE));
        assertEquals("0", PacLua.scalar(Boolean.FALSE));
        assertEquals("42.5", PacLua.scalar(42.5));
    }
}
