package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibcommon.entity.AiTrader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 还没成功告诉过模型的事件行，按币存在 ai_trader.unreported_events 里（JSON 数组）。
 * 对账出来的先存进来，哪一轮管这个币就由哪一轮在开场白里报；那一轮成功了才清，失败了下一轮接着报。
 * 几轮可以同时在跑，读改写在这里排队。
 */
@Component
@RequiredArgsConstructor
public class UnreportedEvents {

    /** 一行事件：归哪个币（不归某一个币的为 null），和成好文的那一行 */
    public record Line(String symbol, String text) {
    }

    private static final TypeReference<List<Line>> LINES = new TypeReference<>() {
    };

    private final AiTraderMapper traderMapper;

    /** 新出来的几行存进去 */
    public synchronized void add(long traderId, List<Line> lines) {
        if (lines.isEmpty()) {
            return;
        }
        List<Line> all = read(traderId);
        all.addAll(lines);
        write(traderId, all);
    }

    /** 这一轮该报的：整盘轮全部，单币轮只有那个币的 */
    public synchronized List<Line> due(long traderId, WakeScope scope) {
        return read(traderId).stream()
                .filter(l -> !scope.single() || scope.symbol().equals(l.symbol())).toList();
    }

    /** 这几行已经告诉过模型，清掉 */
    public synchronized void clear(long traderId, List<Line> reported) {
        if (reported.isEmpty()) {
            return;
        }
        List<Line> all = read(traderId);
        reported.forEach(all::remove);
        write(traderId, all);
    }

    private List<Line> read(long traderId) {
        AiTrader row = traderMapper.selectById(traderId);
        String json = row == null ? null : row.getUnreportedEvents();
        return json == null ? new ArrayList<>() : new ArrayList<>(MAPPER.readValue(json, LINES));
    }

    private void write(long traderId, List<Line> lines) {
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, traderId)
                .set(AiTrader::getUnreportedEvents, lines.isEmpty() ? null : MAPPER.writeValueAsString(lines))
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
    }
}
