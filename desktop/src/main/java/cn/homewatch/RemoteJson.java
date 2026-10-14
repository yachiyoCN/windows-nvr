package cn.homewatch;

import java.util.*;

/** Small bounded JSON codec for the dependency-free remote protocol. */
final class RemoteJson {
    private RemoteJson() {}
    static String encode(Object value) { StringBuilder out = new StringBuilder(); append(out, value); return out.toString(); }
    private static void append(StringBuilder out, Object v) {
        if (v == null) out.append("null");
        else if (v instanceof String || v instanceof Enum<?>) quote(out, v.toString());
        else if (v instanceof Number n) { if (n instanceof Double d && !Double.isFinite(d)) out.append("null"); else out.append(n); }
        else if (v instanceof Boolean) out.append(v);
        else if (v instanceof Map<?, ?> m) { out.append('{'); boolean first = true; for (var e : m.entrySet()) { if (!first) out.append(','); first = false; quote(out, e.getKey().toString()); out.append(':'); append(out, e.getValue()); } out.append('}'); }
        else if (v instanceof Iterable<?> i) { out.append('['); boolean first = true; for (Object e : i) { if (!first) out.append(','); first = false; append(out, e); } out.append(']'); }
        else quote(out, v.toString());
    }
    private static void quote(StringBuilder out, String v) { out.append('"'); for (int i = 0; i < v.length(); i++) { char c = v.charAt(i); switch (c) { case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t"); default -> { if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c); } } } out.append('"'); }
    static Map<String,Object> object(String text) { Object v = new Parser(text).parse(); if (!(v instanceof Map<?,?>)) throw new IllegalArgumentException("JSON object required"); @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>) v; return m; }
    private static final class Parser {
        final String s; int p; Parser(String s) { if (s.length() > 16_384) throw new IllegalArgumentException(); this.s = s; }
        Object parse() { Object v = value(0); ws(); if (p != s.length()) throw new IllegalArgumentException(); return v; }
        void ws() { while (p < s.length() && " \t\r\n".indexOf(s.charAt(p)) >= 0) p++; }
        Object value(int depth) { if (depth > 12) throw new IllegalArgumentException(); ws(); if (p >= s.length()) throw new IllegalArgumentException(); char c = s.charAt(p); if (c == '"') return string(); if (c == '{') { p++; Map<String,Object> m = new LinkedHashMap<>(); ws(); if (take('}')) return m; do { ws(); String k = string(); ws(); need(':'); Object v = value(depth+1); if (m.containsKey(k) || m.size() >= 128) throw new IllegalArgumentException(); m.put(k,v); ws(); if (take('}')) return m; need(','); } while (true); } if (c == '[') { p++; List<Object> l = new ArrayList<>(); ws(); if (take(']')) return l; do { if (l.size() >= 256) throw new IllegalArgumentException(); l.add(value(depth+1)); ws(); if (take(']')) return l; need(','); } while (true); } for (String token : List.of("true", "false", "null")) if (s.startsWith(token,p)) { p+=token.length(); return token.equals("null") ? null : token.equals("true"); } int start=p; while (p<s.length() && "0123456789+-.eE".indexOf(s.charAt(p)) >=0) p++; if(start==p)throw new IllegalArgumentException(); double n=Double.parseDouble(s.substring(start,p)); if(!Double.isFinite(n))throw new IllegalArgumentException(); return n; }
        boolean take(char c) { if (p<s.length() && s.charAt(p)==c) { p++; return true; } return false; }
        void need(char c) { if (!take(c)) throw new IllegalArgumentException(); }
        String string() { need('"'); StringBuilder out = new StringBuilder(); while(p<s.length()) { char c=s.charAt(p++); if(c=='"')return out.toString(); if(c<32)throw new IllegalArgumentException(); if(c=='\\') { if(p>=s.length())throw new IllegalArgumentException(); char e=s.charAt(p++); c=switch(e) { case '"','\\','/' -> e; case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case 'u' -> { if(p+4>s.length())throw new IllegalArgumentException(); char u=(char)Integer.parseInt(s.substring(p,p+4),16);p+=4;yield u; } default -> throw new IllegalArgumentException(); }; } out.append(c); } throw new IllegalArgumentException(); }
    }
}
