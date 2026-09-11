package com.mawai.wiibagent.replay;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词成文与入参校验。重点钉：盲测局的成文里只有相对时间标签（前端给什么就是什么，
 * 后端不会自作主张补真实日期）；评估不需要用户先写看法，一点就评（对着成交与走势评行为）。
 * <p>
 * 中文侧断言原样保留——这批只是把文案外置进词表，成文一个字没变；英文侧零中文由
 * {@code PromptI18nTest} 单管。
 */
class ReplayCoachPromptsTest {

    private static final ReplayCoachPrompts COACH = new ReplayCoachPrompts(new PromptCatalog());
    private static final AgentLang ZH = AgentLang.ZH;

    private static ReplayCoachRequest hint(List<ReplayCoachRequest.Bar> bars) {
        return new ReplayCoachRequest(ReplayCoachRequest.MODE_HINT, null, "ETHUSDT", 15, true, "D1 00:00", bars, 101234.567,
                List.of(new ReplayCoachRequest.Position("LONG", 1.5, 3400.5, 7.5, 123.456)), null, null);
    }

    private static ReplayCoachRequest.Bar bar(String t, double c) {
        return new ReplayCoachRequest.Bar(t, c - 1, c + 2, c - 3, c, 1234.5);
    }

    @Test
    void 盲测提示成文只含相对时间标签与给定数据() {
        String text = COACH.user(hint(List.of(bar("D1 09:00", 3400), bar("D1 09:15", 3410.25))), ZH);
        assertThat(text).contains("盲测")
                .contains("复盘段从 D1 00:00 开始")
                .contains("D1 09:00 3399 3402 3397 3400 1234.5")
                .contains("D1 09:15 3409.25 3412.25 3407.25 3410.25 1234.5")
                .contains("当前权益 101234.57")
                .contains("多 1.5 @ 均价 3400.5 7.5x 浮盈 +123.46")   // 有效杠杆带一位小数
                .doesNotContain("2024").doesNotContain("2025");
        assertThat(COACH.system(hint(List.of(bar("D1 09:00", 1))), ZH))
                .contains("不要猜测这是哪一天");
    }

    @Test
    void 评估成文带成交与统计且用评估系统提示() {
        ReplayCoachRequest r = new ReplayCoachRequest(ReplayCoachRequest.MODE_REVIEW, 7L, "BTCUSDT", 60, false, "2025-03-01 00:00",
                List.of(bar("2025-03-01 08:00", 60000)), null, null,
                List.of(new ReplayCoachRequest.Trade("SHORT", 0.5, 10, 60100, 59800, 148.2,
                        "2025-03-01 08:00", "2025-03-01 12:00", "MANUAL", true)),
                new ReplayCoachRequest.Stats(3, 2, 1, 520.4, 0.0052, 0.031, 12.3, 100000, 100520.4));
        String text = COACH.user(r, ZH);
        assertThat(text).contains("真实时间")
                .contains("1. 空 10x 开 2025-03-01 08:00 @ 60100 → 减仓 2025-03-01 12:00 @ 59800 数量 0.5 盈亏 +148.2")
                .contains("净利 +520.4（+0.52%）")
                .contains("最大回撤 +3.1%");
        assertThat(COACH.system(r, ZH)).contains("交易行为");
        // 一笔没做的局也能评（不要求用户输入任何东西）
        ReplayCoachRequest empty = new ReplayCoachRequest(ReplayCoachRequest.MODE_REVIEW, 7L, "BTCUSDT", 60, false, null,
                List.of(bar("x", 1)), null, null, List.of(), null);
        assertThat(COACH.validate(empty, ZH)).isNull();
        assertThat(COACH.user(empty, ZH)).contains("整局没有交易");
    }

    @Test
    void 校验挡住空K线超量K线与非法mode() {
        assertThat(COACH.validate(hint(List.of()), ZH)).isEqualTo("K 线为空");
        assertThat(COACH.validate(hint(Collections.nCopies(ReplayCoachPrompts.MAX_BARS + 1, bar("D1", 1))), ZH))
                .startsWith("K 线过多");
        assertThat(COACH.validate(hint(List.of(bar("D1", 1))), ZH)).isNull();
        ReplayCoachRequest badMode = new ReplayCoachRequest("CHAT", null, "BTCUSDT", 60, false, null,
                List.of(bar("x", 1)), null, null, null, null);
        assertThat(COACH.validate(badMode, ZH)).isEqualTo("mode 只能是 HINT/REVIEW");
    }
}
