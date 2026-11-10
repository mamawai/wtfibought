package com.mawai.wiibagent.trader.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibagent.mapper.AiTraderWatchMapper;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 盯盘条件存取。一个币一组，set_watch 整组替换：旧的 ARMED 标 REPLACED，新的插成 ARMED；哨兵命中标 FIRED；
 * 带预案动作的，执行结果记在 action_ok / action_result；
 * 哪轮唤醒把这次命中告诉了模型，记在 woke_at。归档不删：时间线和复盘要看它当时布了什么。
 * 在盯的名单一变就广播 {@link WatchesChanged}，哨兵重载。单 trader 唤醒串行（调度层互斥），select-then-write 无并发问题。
 */
@Component
@RequiredArgsConstructor
public class TraderWatchStore {

    private final AiTraderWatchMapper mapper;
    private final ApplicationEventPublisher events;

    /** 本局还在盯的条件：按币、再按布下的先后 */
    public List<AiTraderWatch> armed(long traderId, int roundNo) {
        return mapper.selectList(new LambdaQueryWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getTraderId, traderId)
                .eq(AiTraderWatch::getRoundNo, roundNo)
                .eq(AiTraderWatch::getStatus, AiTraderWatch.STATUS_ARMED)
                .orderByAsc(AiTraderWatch::getSymbol)
                .orderByAsc(AiTraderWatch::getId));
    }

    /** 全站还在盯的条件，哨兵装名单用 */
    public List<AiTraderWatch> allArmed() {
        return mapper.selectList(new LambdaQueryWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getStatus, AiTraderWatch.STATUS_ARMED));
    }

    /** 本局命中了、还没哪轮唤醒告诉过模型的条件，按命中先后 */
    public List<AiTraderWatch> firedUnreported(long traderId, int roundNo) {
        return mapper.selectList(new LambdaQueryWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getTraderId, traderId)
                .eq(AiTraderWatch::getRoundNo, roundNo)
                .eq(AiTraderWatch::getStatus, AiTraderWatch.STATUS_FIRED)
                .isNull(AiTraderWatch::getWokeAt)
                .orderByAsc(AiTraderWatch::getFiredAt));
    }

    /** 复盘窗口 (fromMs, toMs] 里布下的或命中的条件，按 id 先后 */
    public List<AiTraderWatch> inWindow(long traderId, int roundNo, long fromMs, long toMs) {
        return mapper.selectList(new LambdaQueryWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getTraderId, traderId)
                .eq(AiTraderWatch::getRoundNo, roundNo)
                .and(w -> w.gt(AiTraderWatch::getCreatedWakeTime, fromMs).le(AiTraderWatch::getCreatedWakeTime, toMs)
                        .or(x -> x.gt(AiTraderWatch::getFiredAt, fromMs).le(AiTraderWatch::getFiredAt, toMs)))
                .orderByAsc(AiTraderWatch::getId));
    }

    /** 这几条命中已经在 wakeTime 那轮告诉过模型了 */
    public void markWoken(Collection<Long> ids, long wakeTime) {
        if (ids.isEmpty()) {
            return;
        }
        mapper.update(null, new LambdaUpdateWrapper<AiTraderWatch>()
                .in(AiTraderWatch::getId, ids)
                .isNull(AiTraderWatch::getWokeAt)
                .set(AiTraderWatch::getWokeAt, wakeTime)
                .set(AiTraderWatch::getUpdatedAt, LocalDateTime.now()));
    }

    /** 这几条里已经告诉过模型的 */
    public Set<Long> wokenIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<AiTraderWatch>()
                        .in(AiTraderWatch::getId, ids)
                        .isNotNull(AiTraderWatch::getWokeAt))
                .stream().map(AiTraderWatch::getId).collect(Collectors.toSet());
    }

    /**
     * 整组替换一个币的条件（已过 TradeGuard.validateWatch）；空列表就是清掉。一个事务：旧的作废和新的落库同进退
     *
     * @param actions 和 specs 一一对应的预案动作 JSON，没带动作的那条为 null
     */
    @Transactional
    public void replace(long traderId, int roundNo, String symbol, List<WatchSpec> specs, List<String> actions, long wakeTime) {
        retire(new LambdaUpdateWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getTraderId, traderId)
                .eq(AiTraderWatch::getRoundNo, roundNo)
                .eq(AiTraderWatch::getSymbol, symbol));
        for (int i = 0; i < specs.size(); i++) {
            WatchSpec s = specs.get(i);
            AiTraderWatch w = new AiTraderWatch();
            w.setTraderId(traderId);
            w.setRoundNo(roundNo);
            w.setSymbol(symbol);
            w.setKind(s.kind());
            w.setLevel(s.level() == null ? null : BigDecimal.valueOf(s.level()));
            w.setPeriod(s.kind().startsWith("CLOSE_") ? s.period() : null);
            w.setAtTime(AiTraderWatch.AT_TIME.equals(s.kind()) ? TradeGuard.parseWatchTime(s.at()) : null);
            w.setNote(s.note());
            w.setActionJson(actions.get(i));
            w.setStatus(AiTraderWatch.STATUS_ARMED);
            w.setCreatedWakeTime(wakeTime);
            mapper.insert(w);
        }
        events.publishEvent(new WatchesChanged(traderId));
    }

    /** 命中：ARMED → FIRED，记时刻与价。已经不是 ARMED（刚被替换，或并发命中别人先到）返回 false */
    public boolean fire(long id, long at, BigDecimal price) {
        return mapper.update(null, new LambdaUpdateWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getId, id)
                .eq(AiTraderWatch::getStatus, AiTraderWatch.STATUS_ARMED)
                .set(AiTraderWatch::getStatus, AiTraderWatch.STATUS_FIRED)
                .set(AiTraderWatch::getFiredAt, at)
                .set(AiTraderWatch::getFiredPrice, price)
                .set(AiTraderWatch::getUpdatedAt, LocalDateTime.now())) == 1;
    }

    /** 预案执行完：结果记到这条条件上 */
    public void recordAction(long id, boolean ok, String result) {
        mapper.update(null, new LambdaUpdateWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getId, id)
                .set(AiTraderWatch::getActionOk, ok)
                .set(AiTraderWatch::getActionResult, result)
                .set(AiTraderWatch::getUpdatedAt, LocalDateTime.now()));
    }

    /** 重置开新局：本局还在盯的全部作废 */
    public void archiveRound(long traderId, int roundNo) {
        retire(new LambdaUpdateWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getTraderId, traderId)
                .eq(AiTraderWatch::getRoundNo, roundNo));
        events.publishEvent(new WatchesChanged(traderId));
    }

    /** 范围内还在盯的标成 REPLACED。wrapper 更新不走自动填充，updated_at 手动带上 */
    private void retire(LambdaUpdateWrapper<AiTraderWatch> scope) {
        mapper.update(null, scope
                .eq(AiTraderWatch::getStatus, AiTraderWatch.STATUS_ARMED)
                .set(AiTraderWatch::getStatus, AiTraderWatch.STATUS_REPLACED)
                .set(AiTraderWatch::getUpdatedAt, LocalDateTime.now()));
    }

    /** 保留窗口外的过期轮次整局清除；那些局开新局时已作废，没有在盯的，不用重载名单 */
    public void purgeRounds(long traderId, int maxRoundNo) {
        mapper.delete(new LambdaQueryWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getTraderId, traderId)
                .le(AiTraderWatch::getRoundNo, maxRoundNo));
    }

    /** 删 trader：不分轮次全清 */
    public void purgeAll(long traderId) {
        mapper.delete(new LambdaQueryWrapper<AiTraderWatch>()
                .eq(AiTraderWatch::getTraderId, traderId));
        events.publishEvent(new WatchesChanged(traderId));
    }
}
