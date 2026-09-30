package com.scada.gateway.script;

import com.scada.gateway.model.entity.TagEntity;

import java.time.Instant;

/**
 * Пользовательская обработка значений каналов (Lua-скрипты из {@code gateway.scripts.dir}).
 *
 * <p>Скрипт привязывается к каналам по маске имени и получает каждое снятое значение ДО того,
 * как оно уйдёт в Kafka / историю / алармы: масштабирование, перевод единиц, отсев кодов обрыва
 * датчика, фильтр, мёртвая зона. Необязательная обратная функция {@code write} переводит
 * значение команды оператора в то, что пишется в ПЛК. Подробности — {@link LuaValueScripts}.
 */
public interface ValueScripts {

    /** Скриптов нет — значения проходят как есть (одиночный шлюз без scripts/, тесты). */
    ValueScripts NONE = new ValueScripts() {
        @Override
        public Processed process(TagEntity tag, Object value, String quality, Instant timestamp) {
            return new Processed(value, quality);
        }

        @Override
        public Object toPlc(TagEntity tag, Object value) {
            return value;
        }
    };

    /** Значение и качество после скриптов канала. */
    record Processed(Object value, String quality) {}

    /**
     * Прогнать снятое значение через скрипты канала (по порядку привязок). Ошибка скрипта —
     * значение null и качество BAD: необработанное значение в чужих единицах ввело бы
     * оператора в заблуждение.
     */
    Processed process(TagEntity tag, Object value, String quality, Instant timestamp);

    /**
     * Значение команды оператора → значение для ПЛК: функции {@code write} скриптов канала в
     * обратном порядке. У скриптов без {@code write} значение проходит как есть.
     *
     * @throws ScriptFailure скрипт упал или вернул не значение — команда не исполняется
     */
    Object toPlc(TagEntity tag, Object value) throws ScriptFailure;

    /** Ошибка пользовательского скрипта при обработке команды. */
    class ScriptFailure extends Exception {
        public ScriptFailure(String message) {
            super(message);
        }
    }
}
