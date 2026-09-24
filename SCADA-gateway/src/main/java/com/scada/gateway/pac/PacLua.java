package com.scada.gateway.pac;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;

import com.scada.gateway.opcua.ValueCodec;

/**
 * Работа с Lua-стейтом PAC. Ответы контроллера приходят как Lua-скрипт: driver-master
 * исполняет его и читает значения как Lua-переменные. Здесь то же самое на LuaJ.
 *
 * <p>Снимок GET_DEVICES_STATES у ptusa — таблица {@code t} по имени прибора:
 * <pre>
 *   t={ LINE1V0={M=0, ST=1}, LINE1M1={M=0, ST=0, FRQ=12.5, RPM=750, ...}, ... }
 *   t.OBJECT1={CMD=0, CUR_REC='Танк №1', RT_PAR_F={0, 0, 1, ...}, PAR_MAIN={1, 0.20, ...}}
 *   t.SYSTEM={P_V_OFF_DELAY_TIME=1000, ...}
 * </pre>
 * Значение канала — {@code t[deviceName][fieldName]}; поле-массив адресуется как
 * {@code RT_PAR_F[12]} (Lua-индекс с 1), хвост после {@code ]} — подпись канала
 * ({@code PAR_MAIN[1].P_CZAD_S}) и в адрес не входит.
 */
public final class PacLua {

    private PacLua() {
        // Утилитный класс — не инстанцируем.
    }

    /**
     * Новый независимый Lua-стейт (на соединение). Скрипт присылает контроллер, поэтому
     * стейт урезан до чистых вычислений: без io/os/luajava и без чтения файлов — иначе
     * любой, кто ответит на порту PAC, исполнял бы код в JVM шлюза.
     */
    public static Globals newState() {
        Globals globals = new Globals();
        globals.load(new JseBaseLib());
        // PackageLib нужен остальным библиотекам при регистрации; сам require убираем ниже.
        globals.load(new PackageLib());
        globals.load(new TableLib());
        globals.load(new StringLib());
        globals.load(new JseMathLib());
        LoadState.install(globals);
        LuaC.install(globals);
        for (String unsafe : new String[]{"require", "package", "dofile", "loadfile"}) {
            globals.set(unsafe, LuaValue.NIL);
        }
        return globals;
    }

    /** Исполнить Lua-скрипт в стейте (наполняет глобальные переменные/таблицы). */
    public static void exec(Globals globals, String script) {
        globals.load(script).call();
    }

    /** protocol_version из ответа GET_INFO_ON_CONNECT (0, если не задан). */
    public static int protocolVersion(Globals globals) {
        return globals.get("protocol_version").optint(0);
    }

    /**
     * Значение поля прибора из снимка {@code t}, приведённое к dataType. null — если нет
     * снимка, прибора, поля или элемента массива, либо значение не приводится к типу.
     */
    public static Object read(Globals globals, String device, String field, String dataType) {
        if (device == null || field == null) return null;
        LuaValue snapshot = globals.get("t");
        if (!snapshot.istable()) return null;
        LuaValue dev = snapshot.get(device);
        if (!dev.istable()) return null;
        return convert(fieldValue(dev, field), dataType);
    }

    /** Поле прибора: {@code ST} или элемент массива {@code RT_PAR_F[12]} (подпись после ] отбрасывается). */
    private static LuaValue fieldValue(LuaValue dev, String field) {
        int lb = field.indexOf('[');
        if (lb < 0) return dev.get(field);
        int rb = field.indexOf(']', lb);
        if (rb < 0) return LuaValue.NIL;
        LuaValue array = dev.get(field.substring(0, lb));
        if (!array.istable()) return LuaValue.NIL;
        try {
            return array.get(Integer.parseInt(field.substring(lb + 1, rb).trim()));
        } catch (NumberFormatException e) {
            return LuaValue.NIL;
        }
    }

    private static Object convert(LuaValue v, String dataType) {
        if (v.isnil()) return null;
        if (dataType != null && dataType.trim().toUpperCase().startsWith("STRING")) return v.tojstring();
        if (!v.isnumber()) return null;
        if (ValueCodec.isBool(dataType)) return v.toint() != 0;
        if (ValueCodec.isInt(dataType)) return (long) v.todouble();
        return v.todouble();
    }

    /** Скалярное значение для set_cmd: boolean→1/0, число→как есть. */
    public static String scalar(Object value) {
        if (value instanceof Boolean) return ((Boolean) value) ? "1" : "0";
        return String.valueOf(value);
    }
}
