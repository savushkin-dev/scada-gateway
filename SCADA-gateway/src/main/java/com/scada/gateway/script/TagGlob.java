package com.scada.gateway.script;

import java.util.regex.Pattern;

/**
 * Маска имени канала: {@code *} — любая последовательность символов, {@code ?} — один символ,
 * остальное буквально. Не java.nio glob: в именах каналов есть {@code [ ]} (
 * {@code OBJECT1.RT_PAR_F[12]}), а там это класс символов.
 *
 * <pre>
 *   Барановичи-1.BN1_MCA1.*.LINE?TE?.V     — температуры всех линий
 *   *.OBJECT1.RT_PAR_F[7]                   — ровно этот элемент массива
 * </pre>
 */
public final class TagGlob {

    private final String glob;
    private final Pattern pattern;

    public TagGlob(String glob) {
        this.glob = glob;
        StringBuilder re = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    re.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                re.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) re.append(Pattern.quote(literal.toString()));
        this.pattern = Pattern.compile(re.toString(), Pattern.DOTALL);
    }

    public boolean matches(String tagName) {
        return tagName != null && pattern.matcher(tagName).matches();
    }

    @Override
    public String toString() {
        return glob;
    }
}
