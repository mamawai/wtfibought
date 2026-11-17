package com.mawai.wiibfeed.book;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 全部币的盘口：depth20 那条连接卡着不推的时候不往外发 */
class OrderBooksTest {

    /** depth20 的一帧，每侧两档（算得出最小价位才出盘口） */
    private static final String RAW = "{\"b\":[[\"100.0\",\"1\"],[\"99.9\",\"2\"]],\"a\":[[\"100.1\",\"1\"],[\"100.2\",\"2\"]]}";

    @Test
    void 连接超过两秒没来帧_都不发_别的币来了帧就照发() {
        MarketBroadcaster broadcaster = mock(MarketBroadcaster.class);
        OrderBooks books = new OrderBooks(broadcaster);

        books.onTop("BTCUSDT", RAW, System.currentTimeMillis() - OrderBooks.STALE_MS - 1_000);
        books.publish();
        verify(broadcaster, never()).broadcastDepth(any(), any());

        books.onTop("ETHUSDT", RAW, System.currentTimeMillis());
        books.publish();
        verify(broadcaster).broadcastDepth(eq("BTCUSDT"), any());
    }
}
