package io.github.pcdae.adloadtest.controller;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer, plus a reader for the JSON the controller consumes: JMeter's report
 * statistics.json and InfluxDB query responses.
 */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        if (value == null || value instanceof Double d && (d.isNaN() || d.isInfinite())) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                append(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Collection<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                append(sb, item);
            }
            sb.append(']');
        } else {
            string(sb, value.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Parses JSON into maps, lists, strings, doubles, booleans and nulls. */
    static Object parse(String text) {
        Reader r = new Reader(text);
        Object value = r.value();
        r.skipSpace();
        if (r.pos != text.length()) {
            throw r.error("trailing characters");
        }
        return value;
    }

    private static final class Reader {
        private final String s;
        private int pos;

        Reader(String s) {
            this.s = s;
        }

        Object value() {
            skipSpace();
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{' -> {
                    pos++;
                    Map<String, Object> map = new LinkedHashMap<>();
                    skipSpace();
                    if (peek('}')) {
                        return map;
                    }
                    do {
                        skipSpace();
                        String key = string();
                        skipSpace();
                        expect(':');
                        map.put(key, value());
                        skipSpace();
                    } while (peek(','));
                    expect('}');
                    return map;
                }
                case '[' -> {
                    pos++;
                    List<Object> list = new ArrayList<>();
                    skipSpace();
                    if (peek(']')) {
                        return list;
                    }
                    do {
                        list.add(value());
                        skipSpace();
                    } while (peek(','));
                    expect(']');
                    return list;
                }
                case '"' -> {
                    return string();
                }
                default -> {
                    for (String word : new String[] {"true", "false", "null", "NaN"}) {
                        if (s.startsWith(word, pos)) {
                            pos += word.length();
                            return switch (word) {
                                case "true" -> Boolean.TRUE;
                                case "false" -> Boolean.FALSE;
                                case "NaN" -> Double.NaN;
                                default -> null;
                            };
                        }
                    }
                    int start = pos;
                    while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                        pos++;
                    }
                    if (start == pos) {
                        throw error("unexpected '" + c + "'");
                    }
                    return Double.parseDouble(s.substring(start, pos));
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(e);
                }
            }
            throw error("unterminated string");
        }

        void skipSpace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private boolean peek(char c) {
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
        }

        IllegalStateException error(String message) {
            return new IllegalStateException("Invalid JSON at " + pos + ": " + message);
        }
    }
}
