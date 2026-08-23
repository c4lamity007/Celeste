package dev.c4lam.celeste.media;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small JSON object parser for the line-delimited Windows media helper. */
final class MiniJson {
    private MiniJson() {
    }

    static Map<String, Object> parseObject(String json) {
        Object value = new Parser(json).parseValue();
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text == null ? "" : text;
        }

        private Object parseValue() {
            whitespace();
            if (index >= text.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            return switch (text.charAt(index)) {
                case '{' -> object();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> value = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return value;
            while (true) {
                whitespace();
                String key = string();
                whitespace();
                expect(':');
                value.put(key, parseValue());
                whitespace();
                if (take('}')) return value;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                char character = text.charAt(index++);
                if (character == '"') return value.toString();
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (index >= text.length()) throw new IllegalArgumentException("Invalid JSON escape");
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"', '\\', '/' -> value.append(escape);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(unicode());
                    default -> throw new IllegalArgumentException("Invalid JSON escape");
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private char unicode() {
            if (index + 4 > text.length()) throw new IllegalArgumentException("Invalid Unicode escape");
            try {
                char value = (char) Integer.parseInt(text.substring(index, index + 4), 16);
                index += 4;
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid Unicode escape", exception);
            }
        }

        private Object number() {
            int start = index;
            while (index < text.length() && "-+0123456789.eE".indexOf(text.charAt(index)) >= 0) index++;
            String raw = text.substring(start, index);
            try {
                return raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0
                    ? Double.parseDouble(raw)
                    : Long.parseLong(raw);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid JSON number", exception);
            }
        }

        private Object literal(String token, Object value) {
            if (!text.startsWith(token, index)) throw new IllegalArgumentException("Invalid JSON literal");
            index += token.length();
            return value;
        }

        private void whitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        }

        private boolean take(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw new IllegalArgumentException("Expected '" + expected + "'");
        }
    }
}
