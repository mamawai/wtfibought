package com.mawai.wiibfeed.book;

import com.mawai.wiibfeed.book.OrderBook.Levels;

import java.util.Arrays;

/** 盘口报文的手工解析（同 StreamParse 的路子，不走 JSON 反序列化）：一侧的 [["价","量"],...] 解成两个等长数组。 */
final class DepthParse {

    private DepthParse() {}

    /**
     * 取 key 后面的那个数组，key 形如 {@code "\"b\":["}。
     * 币安的报文不带空白，按固定位置跳：["价","量"] 的价从 [" 后开始，量从 "," 后开始。
     */
    static Levels levels(String raw, String key) {
        int i = raw.indexOf(key) + key.length();
        long[] px = new long[32];
        double[] qty = new double[32];
        int n = 0;
        while (raw.charAt(i) == '[') {
            int p0 = i + 2, p1 = raw.indexOf('"', p0);
            int q0 = p1 + 3, q1 = raw.indexOf('"', q0);
            if (n == px.length) {
                px = Arrays.copyOf(px, n * 2);
                qty = Arrays.copyOf(qty, n * 2);
            }
            px[n] = units(raw, p0, p1);
            qty[n++] = Double.parseDouble(raw.substring(q0, q1));
            i = q1 + 2;
            if (raw.charAt(i) == ',') i++;
        }
        return new Levels(Arrays.copyOf(px, n), Arrays.copyOf(qty, n));
    }

    /** 十进制价乘 1e8 取整："64599.20" → 6459920000000 */
    static long units(String s, int from, int to) {
        long v = 0;
        int frac = -1;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '.') {
                frac = 0;
                continue;
            }
            if (frac >= 0) frac++;
            v = v * 10 + (c - '0');
        }
        for (int k = Math.max(frac, 0); k < 8; k++) v *= 10;
        return v;
    }
}
