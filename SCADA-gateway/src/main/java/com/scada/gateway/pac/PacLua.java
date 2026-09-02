package com.scada.gateway.pac;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * Работа с Lua-стейтом PAC. Ответы контроллера приходят как Lua-скрипт: driver-master
 * исполняет его и читает значения тегов как Lua-переменные. Здесь то же самое на LuaJ:
 * исполнить присланный скрипт в {@link Globals} и достать значение из таблицы {@code tags}
 * по ключу (= channelId/node.id), приведя к типу тега.
 *
 * <p>Значения в протоколе — числа (T_NUMBER, float32): boolean кодируется 1/0. Строки
 * (T_STRING) поддержим при появлении строковых тегов.
 */
public final class PacLua {

    private PacLua() {
        // Утилитный класс — не инстанцируем.
    }

    /** Новый независимый Lua-стейт (на соединение). */
    public static Globals newState() {
        return JsePlatform.standardGlobals();
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
     * Значение тега из таблицы {@code tags} по ключу, приведённое к dataType.
     * null — если tags нет или ключа нет (тег недоступен в этом снимке).
     */
    public static Object read(Globals globals, String key, String dataType) {
        LuaValue tags = globals.get("tags");
        if (tags.isnil()) return null;
        LuaValue v = tags.get(LuaValue.valueOf(key));
        if (v.isnil()) return null;
        if ("BOOLEAN".equalsIgnoreCase(dataType)) return v.toint() != 0;
        if ("INT".equalsIgnoreCase(dataType) || "INTEGER".equalsIgnoreCase(dataType)) {
            return (long) v.todouble();
        }
        return v.todouble();
    }

    /** Скалярное значение для set_cmd: boolean→1/0, число→как есть. */
    public static String scalar(Object value) {
        if (value instanceof Boolean) return ((Boolean) value) ? "1" : "0";
        return String.valueOf(value);
    }
}
