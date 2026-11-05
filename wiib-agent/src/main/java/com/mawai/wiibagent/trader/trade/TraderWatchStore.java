package com.mawai.wiibagent.trader.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibagent.mapper.AiTraderWatchMapper;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 盯盘条件存取。一个币一组，set_watch 整组替换：旧的 ARMED 标 REPLACED，新的插成 ARMED。
 * 归档不删：时间线和复盘要看它当时布了什么。单 trader 唤醒串行（调度层互斥），select-then-write 无并发问题。
 */
@Component
@RequiredArgsConstructor
public class TraderWatchStore {

    private final AiTraderWatchMapper mapper;

    /** 本局还在盯的条件：按币、再按布下的先后 */
    public List<AiTraderWatch> armed(long traderId, int roundNo) {
        return mapper.selectList(new LambdaQueryWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getTraderId, traderId)
                .eq(AiTraderWatch::getRoundNo, roundNo)
                .eq(AiTraderWatch::getStatus, AiTraderWatch.STATUS_ARMED)
                .orderByAsc(AiTraderWatch::getSymbol)
                .orderByAsc(AiTraderWatch::getId));
    }

    /** 整组替换一个币的条件（已过 TradeGuard.validateWatch）；空列表就是清掉。一个事务：旧的作废和新的落库同进退 */
    @Transactional
    public void replace(long traderId, int roundNo, String symbol, List<WatchSpec> specs, long wakeTime) {
        retire(new LambdaUpdateWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getTraderId, traderId)
                .eq(AiTraderWatch::getRoundNo, roundNo)
                .eq(AiTraderWatch::getSymbol, symbol));
        for (WatchSpec s : specs) {
            AiTraderWatch w = new AiTraderWatch();
            w.setTraderId(traderId);
            w.setRoundNo(roundNo);
            w.setSymbol(symbol);
            w.setKind(s.kind());
            w.setLevel(s.level() == null ? null : BigDecimal.valueOf(s.level()));
            w.setPeriod(s.kind().startsWith("CLOSE_") ? s.period() : null);
            w.setAtTime(AiTraderWatch.AT_TIME.equals(s.kind()) ? TradeGuard.parseWatchTime(s.at()) : null);
            w.setNote(s.note());
            w.setStatus(AiTraderWatch.STATUS_ARMED);
            w.setCreatedWakeTime(wakeTime);
            mapper.insert(w);
        }
    }

    /** 重置开新局：本局还在盯的全部作废 */
    public void archiveRound(long traderId, int roundNo) {
        retire(new LambdaUpdateWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getTraderId, traderId)
                .eq(AiTraderWatch::getRoundNo, roundNo));
    }

    /** 范围内还在盯的标成 REPLACED。wrapper 更新不走自动填充，updated_at 手动带上 */
    private void retire(LambdaUpdateWrapper<AiTraderWatch> scope) {
        mapper.update(null, scope
                .eq(AiTraderWatch::getStatus, AiTraderWatch.STATUS_ARMED)
                .set(AiTraderWatch::getStatus, AiTraderWatch.STATUS_REPLACED)
                .set(AiTraderWatch::getUpdatedAt, LocalDateTime.now()));
    }

    /** 保留窗口外的过期轮次整局清除 */
    public void purgeRounds(long traderId, int maxRoundNo) {
        mapper.delete(new LambdaQueryWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getTraderId, traderId)
                .le(AiTraderWatch::getRoundNo, maxRoundNo));
    }

    /** 删 trader：不分轮次全清 */
    public void purgeAll(long traderId) {
        mapper.delete(new LambdaQueryWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getTraderId, traderId));
    }
}
