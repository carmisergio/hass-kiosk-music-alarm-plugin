// SPDX-License-Identifier: Apache-2.0
package com.carmisergio.kiosk.musicalarm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A minimal JSON reader. Android's org.json is not on the JVM the tests run on. */
final class Json {
    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /** Objects become maps, arrays lists, numbers doubles. */
    static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.space();
        if (json.at != text.length()) throw json.error("Trailing data");
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    static String string(Map<String, Object> object, String key) {
        Object value = object == null ? null : object.get(key);
        return value instanceof String ? (String) value : null;
    }

    private Object value() {
        space();
        if (at >= text.length()) throw error("Unexpected end");
        char c = text.charAt(at);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (text.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
        if (text.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
        if (text.startsWith("null", at)) { at += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> result = new LinkedHashMap<>();
        at++;
        space();
        if (peek('}')) return result;
        while (true) {
            space();
            if (at >= text.length() || text.charAt(at) != '"') throw error("Expected a key");
            String key = string();
            space();
            expect(':');
            result.put(key, value());
            space();
            if (peek('}')) return result;
            expect(',');
        }
    }

    private List<Object> array() {
        List<Object> result = new ArrayList<>();
        at++;
        space();
        if (peek(']')) return result;
        while (true) {
            result.add(value());
            space();
            if (peek(']')) return result;
            expect(',');
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        at++;
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            if (at >= text.length()) break;
            char e = text.charAt(at++);
            switch (e) {
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (at + 4 > text.length()) throw error("Bad escape");
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default: out.append(e);
            }
        }
        throw error("Unterminated string");
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
        if (start == at) throw error("Unexpected character");
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException e) {
            throw error("Bad number");
        }
    }

    private boolean peek(char c) {
        if (at < text.length() && text.charAt(at) == c) { at++; return true; }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("Expected '" + c + "'");
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at " + at);
    }
}
