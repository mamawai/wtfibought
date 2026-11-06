package com.mawai.wiibfeed.stream;

/** Binance WS 报文手动解析工具：indexOf 抠字段，避开 JSON 反序列化开销。跨流共用。 */
final class StreamParse {

    private StreamParse() {}

    /** 从 start 起取到下一个引号为止的字符串值 */
    static String extractQuoted(String raw, int start) {
        int end = raw.indexOf('"', start);
        return raw.substring(start, end);
    }

    /** 从 from 之后找 key（形如 {@code "\"s\":\""}），取其引号内的值；找不到返回 null */
    static String extractField(String raw, String key, int from) {
        int idx = raw.indexOf(key, from);
        if (idx < 0) return null;
        int vStart = idx + key.length();
        int vEnd = raw.indexOf('"', vStart);
        return vEnd > vStart ? raw.substring(vStart, vEnd) : null;
    }

    /** 组合流 {"stream":...,"data":{...}} 取出 data 对象（按括号配平）；单流没有 data 包装，原样返回 */
    static String dataObject(String raw) {
        int dataIdx = raw.indexOf("\"data\":");
        if (dataIdx < 0) return raw;
        int dataStart = dataIdx + 7;
        int depth = 0;
        for (int i = dataStart; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return raw.substring(dataStart, i + 1);
        }
        return raw.substring(dataStart);
    }

    /** 解析时间戳字段（idx 指向 {@code "\"E\":"} 这类 4 字符键的起点）；解析失败返回 fallback */
    static long getEventTime(String json, int idx, long fallback) {
        if (idx >= 0) {
            int start = idx + 4;
            int end = json.indexOf(',', start);
            if (end < 0) end = json.indexOf('}', start);
            if (end > start) fallback = Long.parseLong(json.substring(start, end).trim());
        }
        return fallback;
    }
}
