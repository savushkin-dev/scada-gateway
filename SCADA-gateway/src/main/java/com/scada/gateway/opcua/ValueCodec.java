package com.scada.gateway.opcua;

import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;

/**
 * Конвертация значений между Java-объектами и OPC UA {@link Variant}.
 *
 * <p>Выделено из god-класса OpcUaClientServiceDB. Это чистые функции — без состояния,
 * сети и Spring, — поэтому их легко и быстро покрыть модульными тестами (см.
 * {@code ValueCodecTest}) без поднятия контекста и без моков. Это и есть первый,
 * самый безопасный «шов» декомпозиции: одна ответственность → отдельный класс → тесты.
 *
 * <p>Методы статические намеренно: у кодека нет собственного состояния, плодить бин незачем.
 */
public final class ValueCodec {

    private ValueCodec() {
        // Утилитный класс — не инстанцируем.
    }

    /**
     * Имя типа тега начинается с префикса? Сравнение ИМЕННО по префиксу, а не на
     * равенство: в конфиге встречаются и {@code INT}, и {@code INT16}, и {@code INT32},
     * и точное сравнение молча роняло целый тег в вещественную ветку.
     */
    private static boolean is(String dataType, String prefix) {
        return dataType != null && dataType.trim().toUpperCase().startsWith(prefix);
    }

    /** Тег объявлен булевым. */
    public static boolean isBool(String dataType) {
        return is(dataType, "BOOL");
    }

    /** Тег объявлен целым: INT, INTEGER, INT16, INT32. */
    public static boolean isInt(String dataType) {
        return is(dataType, "INT");
    }

    /** Тег объявлен вещественным: FLOAT, REAL, DOUBLE. */
    public static boolean isFloat(String dataType) {
        return is(dataType, "FLOAT") || is(dataType, "REAL") || is(dataType, "DOUBLE");
    }

    /** Тег объявлен строковым: STRING. */
    public static boolean isString(String dataType) {
        return is(dataType, "STRING");
    }

    /** Значение команды → OPC UA {@link Variant} нужного типа (по имени типа тега). */
    public static Variant toVariant(String dataType, Object value) {
        String dt = dataType == null ? "" : dataType.trim().toUpperCase();
        if (dt.startsWith("BOOL")) return new Variant(toBool(value));
        if (dt.startsWith("INT"))  return new Variant(toInt(value));
        if (dt.startsWith("FLOAT") || dt.startsWith("REAL")) return new Variant(toFloat(value));
        if (dt.startsWith("DOUBLE")) return new Variant(toDouble(value));
        return new Variant(value);
    }

    /**
     * OPC UA {@link Variant} → Java-значение. Беззнаковый {@link UInteger} из Milo
     * разворачиваем в {@code long}, чтобы дальше по конвейеру (JDBC/Kafka) был обычный
     * Java-Number, а не специальный тип стека.
     */
    public static Object extractValue(Variant variant) {
        if (variant == null || variant.isNull()) return null;

        Object v = variant.getValue();

        if (v instanceof UInteger) {
            return ((UInteger) v).longValue();
        }

        return v;
    }

    /** В boolean: Number → «≠0 = true», иначе парсинг строки ("true"/"false"). */
    public static Boolean toBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    /** В int: Number усекается, иначе парсинг строки. Бросает при нечисловой строке. */
    public static Integer toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(v).trim());
    }

    /** Во float: Number сужается, иначе парсинг строки. Бросает при нечисловой строке. */
    public static Float toFloat(Object v) {
        if (v instanceof Number n) return n.floatValue();
        return Float.parseFloat(String.valueOf(v).trim());
    }

    /** В double: Number расширяется, иначе парсинг строки. Бросает при нечисловой строке. */
    public static Double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(v).trim());
    }
}
