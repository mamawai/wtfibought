package com.mawai.wiibfeed.book;

import tools.jackson.databind.JsonNode;
import com.mawai.wiibfeed.book.OrderBook.Levels;
import org.junit.jupiter.api.Test;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/** 本地盘口：depth20 管最优 20 档，增量攒更远的；按价格单位归并成买卖各若干行 */
class OrderBookTest {

    /** "价:量" 一档，最优价在前 */
    private static Levels lv(String... levels) {
        long[] px = new long[levels.length];
        double[] qty = new double[levels.length];
        for (int i = 0; i < levels.length; i++) {
            String[] p = levels[i].split(":");
            px[i] = DepthParse.units(p[0], 0, p[0].length());
            qty[i] = Double.parseDouble(p[1]);
        }
        return new Levels(px, qty);
    }

    /** 第 i 个单位的一侧，写成 [[价,量],...] */
    private static String side(OrderBook book, int i, String side) throws Exception {
        return ladder(book, i).path(side).toString();
    }

    private static JsonNode ladder(OrderBook book, int i) throws Exception {
        return MAPPER.readTree(book.snapshot()).path("ladders").get(i);
    }

    private static OrderBook bookAt100() {
        OrderBook book = new OrderBook();
        book.onTop(lv("100.0:1", "99.9:2", "99.8:3"), lv("100.1:1", "100.2:2", "100.3:3"));
        return book;
    }

    @Test
    void depth20没来过或断了_不出盘口() {
        OrderBook book = new OrderBook();
        book.onDiff(0, 10, lv("99.5:4"), lv());
        assertThat(book.snapshot()).isNull();

        OrderBook cut = bookAt100();
        cut.resetTop();
        assertThat(cut.snapshot()).isNull();
    }

    @Test
    void 最小单位_depth20原样出() throws Exception {
        OrderBook book = bookAt100();

        assertThat(side(book, 0, "b")).isEqualTo("[[100,1],[99.9,2],[99.8,3]]");
        assertThat(side(book, 0, "a")).isEqualTo("[[100.1,1],[100.2,2],[100.3,3]]");
    }

    @Test
    void 大单位_买盘往下卖盘往上_同一格的量加在一起() throws Exception {
        OrderBook book = bookAt100();

        assertThat(ladder(book, 1).path("step").asString()).isEqualTo("1");
        assertThat(side(book, 1, "b")).isEqualTo("[[100,1],[99,5]]");
        assertThat(side(book, 1, "a")).isEqualTo("[[101,6]]");
        assertThat(ladder(book, 3).path("step").asString()).isEqualTo("100");
        assertThat(side(book, 3, "b")).isEqualTo("[[100,1],[0,5]]");
        assertThat(side(book, 3, "a")).isEqualTo("[[200,6]]");
    }

    @Test
    void 二十档以外用增量补_以内的只认depth20() throws Exception {
        OrderBook book = bookAt100();

        book.onDiff(0, 10, lv("99.9:100", "99.5:4"), lv("100.2:100", "100.6:5"));

        assertThat(side(book, 0, "b")).isEqualTo("[[100,1],[99.9,2],[99.8,3],[99.5,4]]");
        assertThat(side(book, 0, "a")).isEqualTo("[[100.1,1],[100.2,2],[100.3,3],[100.6,5]]");
    }

    @Test
    void 增量里量为0是这一档撤了() throws Exception {
        OrderBook book = bookAt100();
        book.onDiff(0, 10, lv("99.5:4", "99.4:6"), lv());

        book.onDiff(10, 20, lv("99.5:0"), lv());

        assertThat(side(book, 0, "b")).isEqualTo("[[100,1],[99.9,2],[99.8,3],[99.4,6]]");
    }

    @Test
    void 更新号接不上_攒的清掉从这条重攒() throws Exception {
        OrderBook book = bookAt100();
        assertThat(book.onDiff(0, 10, lv("99.5:4"), lv())).isTrue();
        assertThat(book.onDiff(10, 20, lv("99.4:6"), lv())).isTrue();

        assertThat(book.onDiff(25, 30, lv("99.3:7"), lv())).isFalse();

        assertThat(side(book, 0, "b")).isEqualTo("[[100,1],[99.9,2],[99.8,3],[99.3,7]]");
        assertThat(book.onDiff(30, 40, lv("99.2:8"), lv())).isTrue();
    }

    @Test
    void 增量重连_从头攒_下一条不管更新号() throws Exception {
        OrderBook book = bookAt100();
        book.onDiff(0, 10, lv("99.5:4"), lv());

        book.resetDiff();

        assertThat(side(book, 0, "b")).isEqualTo("[[100,1],[99.9,2],[99.8,3]]");
        assertThat(book.onDiff(999, 1000, lv("99.3:7"), lv())).isTrue();
    }

    @Test
    void 最小价位_相邻档价差的最大公约数() throws Exception {
        OrderBook book = new OrderBook();
        book.onTop(lv("100.00:1", "99.50:1", "99.00:1"), lv("100.50:1", "101.50:1"));
        assertThat(ladder(book, 0).path("step").asString()).isEqualTo("0.5");

        book.onTop(lv("100.00:1", "99.90:1"), lv("100.50:1"));
        assertThat(ladder(book, 0).path("step").asString()).isEqualTo("0.1");
    }

    @Test
    void 每侧最多十行() throws Exception {
        String[] bids = new String[20];
        String[] asks = new String[20];
        for (int i = 0; i < 20; i++) {
            bids[i] = (1000 - i) + ".0:1";
            asks[i] = (1001 + i) + ".0:1";
        }
        OrderBook book = new OrderBook();
        book.onTop(lv(bids), lv(asks));

        assertThat(ladder(book, 0).path("b")).hasSize(OrderBook.ROWS);
        assertThat(ladder(book, 0).path("a")).hasSize(OrderBook.ROWS);
        assertThat(ladder(book, 0).path("b").get(9).toString()).isEqualTo("[991,1]");
    }

    @Test
    void 太远的档不留_价走开再回来也没有() throws Exception {
        // 最小价位 0.1，留到最优价 ±4000 以内
        OrderBook book = new OrderBook();
        book.onTop(lv("10000.0:1", "9999.9:1"), lv("10000.1:1", "10000.2:1"));
        book.onDiff(0, 10, lv("9990.0:7", "3000.0:9"), lv());
        assertThat(side(book, 0, "b")).isEqualTo("[[10000,1],[9999.9,1],[9990,7]]");

        // 价到了 20000：9990 落到范围外，出盘口时扔掉；这时候来的 9989 增量也不收
        book.onTop(lv("20000.0:1", "19999.9:1"), lv("20000.1:1", "20000.2:1"));
        book.snapshot();
        book.onDiff(10, 20, lv("9989.0:5"), lv());

        book.onTop(lv("10000.0:1", "9999.9:1"), lv("10000.1:1", "10000.2:1"));
        assertThat(side(book, 0, "b")).isEqualTo("[[10000,1],[9999.9,1]]");
    }
}
