package com.mawai.wiibservice.agent.trading.exit.playbook;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPath;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import com.mawai.wiibservice.agent.trading.runtime.TradingDecisionContext;

import java.time.LocalDateTime;

/**
 * 单一退出剧本。
 *
 * <p>每个实现只负责一种入场路径对应的管仓规则，比如突破、趋势或均值回归。
 * 引擎会按 {@link ExitPlan#path()} 找到对应剧本，再把当前仓位和入场时锁定的 ExitPlan 传进来。</p>
 */
interface ExitPlaybook {

    ExitPath path();

    /**
     * 判断一个仓位本轮是否需要主动动作。
     *
     * <p>返回 HOLD 表示这个仓位本身不动作；是否允许本轮继续开新仓，由
     * {@link ExitPlaybookDecision#entryEvaluationBlocked()} 决定。</p>
     */
    ExitPlaybookDecision evaluate(TradingDecisionContext decision,
                                  FuturesPositionDTO position,
                                  ExitPlan plan,
                                  LocalDateTime now);
}
