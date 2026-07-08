package com.mawai.wiibquant.agent.chat;

import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.alibaba.cloud.ai.graph.store.StoreSearchResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryServiceTest {

    private final Store store = mock(Store.class);
    private final ChatMemoryService service = new ChatMemoryService(store);

    @Test
    void rememberExtractsMentionedSymbolAndCounts() {
        when(store.getItem(anyList(), anyString())).thenReturn(Optional.empty());

        service.remember(1L, "BTC 现在脆弱度怎么样", "脆弱度61，偏高");

        ArgumentCaptor<StoreItem> captor = ArgumentCaptor.forClass(StoreItem.class);
        verify(store).putItem(captor.capture());
        StoreItem item = captor.getValue();
        assertThat(item.getKey()).isEqualTo("BTCUSDT");
        assertThat(item.getNamespace()).containsExactly("workbench", "1");
        assertThat(item.getValue().get("count")).isEqualTo(1L);
    }

    @Test
    void rememberAccumulatesExistingCount() {
        StoreItem existing = StoreItem.of(List.of("workbench", "1"), "BTCUSDT", Map.of("count", 4L));
        when(store.getItem(anyList(), anyString())).thenReturn(Optional.of(existing));

        service.remember(1L, "btc 波动预测", "H6 预计 120bps");

        ArgumentCaptor<StoreItem> captor = ArgumentCaptor.forClass(StoreItem.class);
        verify(store).putItem(captor.capture());
        assertThat(captor.getValue().getValue().get("count")).isEqualTo(5L);
    }

    @Test
    void rememberSkipsWhenNoWatchSymbolMentioned() {
        service.remember(1L, "今天天气如何", "不知道");

        verify(store, never()).putItem(any());
    }

    @Test
    void recallBuildsMemoryPrefix() {
        StoreItem item = StoreItem.of(List.of("workbench", "1"), "BTCUSDT",
                Map.of("count", 5L, "lastQuestion", "脆弱度怎么样"));
        when(store.searchItems(any())).thenReturn(StoreSearchResult.of(List.of(item), 1, 0, 5));

        String memory = service.recall(1L);

        assertThat(memory).contains("BTCUSDT").contains("5次").contains("脆弱度怎么样");
    }

    @Test
    void recallDegradesToEmptyOnFailure() {
        when(store.searchItems(any())).thenThrow(new RuntimeException("db down"));

        assertThat(service.recall(1L)).isEmpty();
    }
}
