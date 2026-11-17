package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibfeed.book.OrderBooks;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 增量深度：从报文里取币名和两个更新号（pu 接上一条、u 是这一条），原文交给本地盘口 */
class DepthDiffStreamHandlerTest {

    @Test
    void 取币名和更新号_原文交给盘口() {
        OrderBooks books = mock(OrderBooks.class);
        DepthDiffStreamHandler handler = new DepthDiffStreamHandler(new BinanceProperties(), books);
        String raw = "{\"stream\":\"btcusdt@depth@500ms\",\"data\":{\"e\":\"depthUpdate\",\"E\":1794879326994,\"T\":1794879326993,"
                + "\"s\":\"BTCUSDT\",\"ps\":\"BTCUSDT\",\"U\":12138464881501,\"u\":12138464952443,\"pu\":12138464881457,"
                + "\"b\":[[\"1000.00\",\"16.628\"]],\"a\":[[\"64600.10\",\"0.000\"]]}}";

        handler.onMessage(raw);

        verify(books).onDiff("BTCUSDT", 12138464881457L, 12138464952443L, raw);
    }

    @Test
    void 重连就让盘口从头攒() {
        OrderBooks books = mock(OrderBooks.class);
        DepthDiffStreamHandler handler = new DepthDiffStreamHandler(new BinanceProperties(), books);

        handler.onConnected(null);

        verify(books).resetDiff();
    }
}
