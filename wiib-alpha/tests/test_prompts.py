import copy

from wiib_alpha import ops, prompts
from wiib_alpha.data.panel import FEATURE_COLUMNS
from wiib_alpha.eval.params import Thresholds

FACTS = {"symbols": ["BTCUSDT", "ETHUSDT"], "from": "2024-09-21", "to": "2026-09-20", "rows": 70000,
         "liqFrom": "2026-04-27", "spotFrom": "2024-09-21", "spotMissing": 12, "holdoutFrom": "2026-06-22",
         "recentFrom": "2022-01-01", "costTiers": [5.0, 8.0, 12.0], "poolSize": 80,
         "poolSymbols": 481, "carryShare": 0.25, "g9Min": 0.1}
P = prompts.DEFAULT_POLICY
T = Thresholds()


def test_parse_object_and_list_tolerate_fences_and_chatter():
    assert prompts.parse_object('好的：\n```json\n{"expr": "$ret", "note": "x"}\n```') == {"expr": "$ret", "note": "x"}
    assert prompts.parse_object("没有 json") is None
    assert prompts.parse_object('{"a": 1} 然后 {"b": 2}') == {"a": 1}
    assert prompts.parse_list('结果：[{"name": "a"}, {"name": "b"}] 完') == [{"name": "a"}, {"name": "b"}]
    assert prompts.parse_list('[1, 2') is None
    assert prompts.parse_object('[1, 2]') is None


def test_system_prompt_lists_every_column_and_operator():
    # 生成、评审都要读公式，两份系统提示带同一份列和算子说明；复盘不看公式，不带
    for text in (prompts.system_gen(FACTS, "zh", P), prompts.system_review("zh", P)):
        for c in FEATURE_COLUMNS:
            assert f"${c}" in text
        for name in ops.OPERATORS:
            assert f"- {name}：{prompts.OPERATOR_DOCS[name]}" in text
        # 复杂度上限两个角色都看得到：评审给的改法也得写得出来
        assert "不同的子式 ≤ 40 个、不同的窗口值 ≤ 6 个、不同的常量值 ≤ 6 个" in text and "重复写只算一个" in text
    assert "改法要能在下面的复杂度上限内写出来" in prompts.system_review("zh", P)
    assert "- Mean：" not in prompts.system_reflect("zh")
    text = prompts.system_gen(FACTS, "zh", P)
    assert "2026-04-27" in text and "中文" in text
    assert "强平三列只有产品币有数" in text and "截面因子用不了" in text and "单独排序没有意义" in text
    assert "现货三列从 2024-09-21 起" in text and "12 个币没有现货对" in text
    assert "现货三列暂时没有数据" in prompts.system_gen({**FACTS, "spotFrom": None}, "zh", P)
    assert "2026-06-22 起是锁定的 holdout" in text and "单边 5 / 8 / 12 bps" in text
    assert "前 80 个币" in text and "共 481 个" in text and "BTCUSDT, ETHUSDT" in text
    # 已知效应带公式列出来；去老效应是标签不是门
    assert "- turnover_oi：Sum($quote_volume, 672) / $oi_value（换手" in text and "它不是门，但评审和人都会看" in text
    assert "t（ICIR × √月数）≥ 3" in text and "最近一段必须同号" in text
    # 门对齐交易方案：主档 24h、按波动分仓，单条净收益只报，G9 看加进交易方案后的净 Sharpe
    assert "主档 24 小时" in text and "按 7 天波动的倒数分仓" in text and "只报不判" in text
    assert "G9 加进实盘的交易方案后，2022-01-01 起那段的净 Sharpe 至少升 0.1" in text and "全段的增量只报不判" in text
    assert "单边 4 / 6.5 / 10 bps" in prompts.system_gen({**FACTS, "costTiers": [4.0, 6.5, 10.0]}, "zh", P)
    # 方案里两条腿的份额按设定渲染
    assert "方案是 75% 资金做库里因子的组合" in text and "、25% 做基差 carry" in text
    tenth = prompts.system_gen({**FACTS, "carryShare": 0.1}, "zh", P)
    assert "方案是 90% 资金做库里因子的组合" in tenth and "、10% 做基差 carry" in tenth
    assert "英文" in prompts.system_gen({**FACTS, "liqFrom": None}, "en", P)


def test_propose_and_implement_messages():
    text = prompts.propose_message(3, [{"name": "mom", "expr": "$ret", "hypothesis": "h"}],
                                   [{"outcome": "rejected_gates", "hypothesis": "old", "expr": "$x",
                                     "reason": f"G2 主档 IC 0.0062；G9 增量 -0.03；2022-01-01 起 1.66 → 1.82；{prompts.STUCK}：和第 1 版一样"},
                                    {"outcome": "rejected_review", "hypothesis": "bad", "expr": "$y", "reason": "机制不成立"}],
                                   [{"kind": "try", "text": "新的"}, {"kind": "avoid", "text": "旧的"}],
                                   prompts.outcomes([], P.families, T), "只看资金费率", "zh", P)
    assert "mom：$ret" in text and "只看资金费率" in text and "提出 3 条" in text
    # 门没过的只列门号，写不出新表达式那截和评审的评语原样
    assert (f"- [rejected_gates] old｜$x｜没过的门：G2、G9；{prompts.STUCK}：和第 1 版一样\n"
            "- [rejected_review] bad｜$y｜机制不成立") in text
    assert "- [try] 新的\n- [avoid] 旧的" in text
    # 还没有现在口径的候选：成败表整段不出
    assert "按数据类" not in text and "（h）\n\n之前几轮复盘写下的经验" in text
    # 机制清单带 ID，纯价格类列着但标明做不成，other 不带空括号
    assert "- basis：" in text and "- price：" in text and "已证做不成" in text
    assert "- other：清单外的机制\nfamily 填清单里的 ID" in text
    # 清单里没有强平（只有产品币有数）和时段（同一时刻所有币一样）
    assert "强平" not in text and "时段" not in text
    assert "已折算成每 8 小时的等价费率" in prompts.system_gen(FACTS, "zh", P)
    assert "经验（最新在前）：\n（还没有）" in prompts.propose_message(1, [], [], [], prompts.outcomes([], P.families, T), "",
                                                                    "zh", P)
    cand = {"name": "carry", "hypothesis": "费率高做空"}
    plain = prompts.implement_message(cand, None, P)
    assert "上一版" not in plain and "上一次输出的问题" not in plain and '"direction": 1 或 -1' in plain
    # 第一版就要想清楚：写之前的自查清单
    assert "想清楚再写" in plain and "资金费：排在做多那头和做空那头的币" in plain
    # 复评老候选补方向：只给假设和表达式，没有数字
    ask = prompts.direction_message({**cand, "expr": "-$funding_rate", "note": None})
    assert "费率高做空" in ask and "-$funding_rate" in ask and "IC" not in ask
    # 上一版的意见一直带着，输出本身的毛病接在后面
    text = prompts.implement_message(cand, "上一版表达式：$ret\n评审要求修改：换个窗口", P, "表达式不合法：x")
    assert text.index("评审要求修改：换个窗口") < text.index("上一次输出的问题：表达式不合法：x")
    # 评审打回：理由和改法都带上
    assert (prompts.revise_feedback({"expr": "$ret"}, {"suggestion": "换成 Mean($ret, 96)", "comment": "太毛"})
            == "上一版表达式：$ret\n评审意见：太毛\n评审建议的改法：换成 Mean($ret, 96)")


def test_policy_replaces_the_opinions_and_leaves_the_facts():
    mine = prompts.Policy({"basis": ("溢价", "premium", "7 天"), "other": ("清单外", "", "")}, "- 规则甲", "自查乙",
                          "你只判一件事：丙")
    gen = prompts.system_gen(FACTS, "zh", mine)
    assert "- 规则甲\n\n假设与说明" in gen and "参数越少越好" not in gen and "G9 加进实盘的交易方案后" in gen
    propose = prompts.propose_message(1, [], [], [], prompts.outcomes([], mine.families, T), "", "zh", mine)
    assert "- basis：溢价（列：premium；持有期：7 天）\n- other：清单外\n" in propose and "- price：" not in propose
    implement = prompts.implement_message({"name": "x", "hypothesis": "h"}, None, mine)
    assert "\n\n自查乙\n\n把这条假设写成" in implement and "想清楚再写" not in implement
    review = prompts.system_review("zh", mine)
    assert "你不对数字投票。你只判一件事：丙\n\n裁决：" in review and "经济上讲不讲得通" not in review
    # 存进库的那一版来回转一遍不变
    assert prompts.Policy.of(mine.to_row()) == mine


def scored(columns: list[str], family: str, ic: dict, leg: dict, gain: float | None) -> dict:
    """store.evaluated_candidates 的一行：落过库，档的键是字符串；gain 是 G9 判的那份增量"""
    return {"family": family, "eval": {"columns": columns, "primary": 96, "horizons": {"96": {"ic": ic, "leg": leg}},
                                       "gain": {"recentSharpe": {"gain": gain}}}}


def test_outcomes_tally_by_data_class_and_family():
    rows = [
        # 有信号（t = 0.8 × √20 ≈ 3.6）、扣成本还赚、增量过现在的线（0.10）；用到溢价和成交量，两类各算一次
        scored(["premium", "volume"], "basis", {"mean": 0.03, "icir": 0.8, "months": 20},
               {"netReturn": 0.2, "turnover": 0.25, "breakEvenBps": 15.0}, 0.15),
        # IC 够了但 t 不够（0.5 × √16 = 2）；净收益、盈亏平衡、增量落库成了 None
        scored(["premium"], "basis", {"mean": -0.02, "icir": -0.5, "months": 16},
               {"netReturn": None, "turnover": 0.75, "breakEvenBps": None}, None),
        # IC 不够；多空腿没有样本外期数；增量不到线；老候选的中文 family 算 other
        scored(["ret"], "动量", {"mean": 0.005, "icir": 2.0, "months": 20}, {"periods": 0}, 0.05),
    ]
    table = prompts.outcomes(rows, P.families, T)
    assert table["byClass"] == {
        "溢价": {"tried": 2, "signal": 1, "net": 1, "g9": 1, "turnover": 0.5, "breakEven": 15.0},
        "成交量": {"tried": 1, "signal": 1, "net": 1, "g9": 1, "turnover": 0.25, "breakEven": 15.0},
        "纯价格": {"tried": 1, "signal": 0, "net": 0, "g9": 0, "turnover": None, "breakEven": None},
    }
    assert table["byFamily"] == {
        "basis": {"tried": 2, "signal": 1, "net": 1, "g9": 1, "turnover": 0.5, "breakEven": 15.0},
        "other": {"tried": 1, "signal": 0, "net": 0, "g9": 0, "turnover": None, "breakEven": None},
    }
    text = prompts.propose_message(1, [], [], [], table, "", "zh", P)
    assert ("已试过的候选按数据类的成败（搜索段数字；有信号 = |IC| ≥ 0.01 且 t ≥ 3）：\n"
            "- 溢价：试过 2、有信号 1、扣成本还赚 1、过 G9 1、换手中位 0.50、盈亏平衡中位 15.0 bps\n"
            "- 成交量：试过 1、有信号 1、扣成本还赚 1、过 G9 1、换手中位 0.25、盈亏平衡中位 15.0 bps\n"
            "- 纯价格：试过 1、有信号 0、扣成本还赚 0、过 G9 0、换手中位 —、盈亏平衡中位 —\n"
            "按机制 ID：\n"
            "- basis：试过 2、有信号 1、扣成本还赚 1、过 G9 1、换手中位 0.50、盈亏平衡中位 15.0 bps\n"
            "- other：试过 1、有信号 0、扣成本还赚 0、过 G9 0、换手中位 —、盈亏平衡中位 —\n") in text
    # 放在库和经验之间
    assert text.index("库里已有的因子") < text.index("按数据类") < text.index("之前几轮复盘写下的经验")


def evaluated(gates: list[dict]) -> dict:
    """落过库的老结果：档的键是字符串，NaN 成了 None，没记主档（是 12h）"""
    return {"expr": "Mean($ret, 4)", "direction": -1, "gates": gates, "eval": {
        "horizons": {"48": {"ic": {"mean": 0.0123, "icir": 0.25,
                                   "monotonic": {"rho": 0.5, "spread": 0.00031, "spreadT": 2.5, "steps": [2.4, 0.3]}},
                            "leg": {"turnover": 0.214, "fundingBps": -1.26, "breakEvenBps": 3.24}}},
        "redundancy": {"maxAbs": 0.42, "with": "MA48", "ready": True},
        "neutral": {"mean": 0.0041, "icir": 0.5, "months": 36, "segments": [], "against": 10}}}


def test_neutral_brief_reports_share_left_and_t():
    ev = evaluated([])["eval"]
    assert prompts.neutral_brief(ev) == {"meanIc": 0.0041, "icir": 0.5, "t": 3.0, "retained": 0.3333,
                                         "against": 10}
    # 网格没算好是 None；落过库的 NaN 成了 None 也能算
    assert prompts.neutral_brief({**ev, "neutral": None}) is None
    assert prompts.neutral_brief({**ev, "neutral": {**ev["neutral"], "icir": None}})["t"] is None


def test_gate_feedback_lists_failed_gates_and_the_numbers():
    gates = [{"id": "G1", "name": "最小样本", "passed": True, "note": "够"},
             {"id": "G2", "name": "IC", "passed": False, "note": "ICIR 太低"},
             {"id": "G5", "name": "可交易", "passed": False, "note": "净收益为负"}]
    text = prompts.gate_feedback(evaluated(gates))
    assert text.startswith("上一版表达式：Mean($ret, 4)（声明的方向 -1，因子值越大越弱）\n"
                           "没过的门：\n- G2 IC：ICIR 太低\n- G5 可交易：净收益为负\n")
    assert "G1" not in text and "主档（12h）" in text and "评审" not in text
    # 上一版是照评审意见改的：门没过重写时那次的理由和改法还带着
    revised = prompts.gate_feedback({**evaluated(gates), "review": {"verdict": "revise", "comment": "度量不对",
                                                                     "suggestion": "按时段占比"}})
    assert "上一版是照评审意见改的，那次的意见：\n评审意见：度量不对\n评审建议的改法：按时段占比" in revised
    for part in ("IC 均值 0.0123", "ICIR 0.25", "截尾后顶组减底组每期 3.1 bps、t 2.5",
                 "三组形状：只有弱的一头有效（中、强两组分不开）", "换手 0.21", "每期资金费 -1.3 bps",
                 "盈亏平衡单边成本 3.2 bps", "与 MA48 的截面 |ρ| 0.42", "这些数字说明了什么由你判断",
                 "回归掉 10 条老效应后 IC 0.0041（剩原来的 0.33），t 3.0"):
        assert part in text, part
    ev = evaluated(gates)["eval"]
    ev["redundancy"] = {"maxAbs": None, "with": None, "ready": False}
    ev["horizons"]["48"]["leg"]["breakEvenBps"] = None
    assert "参照网格还没算好" in prompts.key_numbers(ev) and "盈亏平衡单边成本 NaN bps" in prompts.key_numbers(ev)
    # 现在的结果报最像的前 3 个，各带表达式
    ev["redundancy"] = {"maxAbs": 0.98, "with": "lib:twin", "ready": True, "top": [
        {"name": "lib:twin", "abs": 0.98, "expr": "Mean($ret, 8)"},
        {"name": "obs:carry", "abs": 0.4, "expr": "$premium"},
        {"name": "MA48", "abs": 0.21, "expr": "Mean($close, 48) / $close"}]}
    assert ("冗余前 3（截面 |ρ|）：lib:twin 0.98（Mean($ret, 8)）、obs:carry 0.40（$premium）、"
            "MA48 0.21（Mean($close, 48) / $close）") in prompts.key_numbers(ev)
    # 现在的结果记着主档 24h，数字取那一档
    ev["horizons"]["96"] = copy.deepcopy(ev["horizons"].pop("48"))
    ev["horizons"]["96"]["ic"]["mean"] = 0.0456
    assert "IC 均值 0.0456" in prompts.key_numbers({**ev, "primary": 96})
    # 挖掘时算过预检的，末尾带上预检的数
    assert "秩自相关" not in text
    ev["precheck"] = {"rankAutocorr": 0.9123, "coverage": 0.98, "points": 52, "fundingHigh": 1.234, "fundingLow": -0.56}
    assert prompts.key_numbers({**ev, "primary": 96}).endswith(
        "、24h 秩自相关 0.91、覆盖 0.98、因子值高的一头资金费 1.2 bps / 低的一头 -0.6 bps")


def test_reflect_message_shows_outcomes_numbers_and_old_lessons():
    good = {**evaluated([]), "seq": 1, "name": "carry", "hypothesis": "费率高的币之后弱", "status": "passed",
            "reason": None, "evaluations": 2}
    bad = {"seq": 2, "name": "bad", "hypothesis": "写不出来", "status": "failed", "reason": "连试三次都没写出合法表达式"}
    gated = {**good, "seq": 3, "name": "gated", "family": "liquidity", "status": "rejected_gates", "reason": "G5 xx；G9 yy",
             "gates": [{"id": "G5", "name": "排列检验", "passed": False}, {"id": "G6", "name": "冗余", "passed": True},
                       {"id": "G9", "name": "增量", "passed": False}]}
    text = prompts.reflect_message([good, bad, gated], [{"kind": "avoid", "text": "别用强平列"}], "en")
    # 不给表达式和整段门的备注，只给结局、没过的门和四个数
    assert "1. [carry] 假设：费率高的币之后弱\n   结局：过门、过评审、过同批 BH，进观察区等人入库；评估了 2 版\n   主档：IC " in text
    assert "盈亏平衡 3.2 bps" in text and "Mean($ret, 4)" not in text and "$" not in text.split("已有的经验")[0]
    assert "2. [bad] 假设：写不出来\n   结局：失败（连试三次都没写出合法表达式）；评估了 0 版" in text
    assert "3. [gated]（liquidity）" in text and "硬门拒（没过的门：G5 排列检验、G9 增量）" in text and "G5 xx" not in text
    assert "- [avoid] 别用强平列" in text and "英文" in text
    assert "英文不超过 50 词" in prompts.system_reflect("en") and "holdout 之前" in prompts.system_reflect("zh")
    # 单条候选的结局出题时另有列表，经验只写换个假设也用得上的规律
    assert "不写某一条候选的复盘" in prompts.system_reflect("zh") and "不抄表达式" in prompts.system_reflect("zh")


def test_lesson_fits_rejects_expressions_and_overlong_text():
    assert prompts.lesson_fits("持仓量类的慢因子换手低，单腿能过成本", "zh")
    assert not prompts.lesson_fits("别写成 Std($oi, 672)", "zh") and not prompts.lesson_fits("x (y)", "zh")
    # 全角括号是正常中文，不算表达式
    assert prompts.lesson_fits("不要用（成交额÷笔数）这种笔均", "zh")
    assert not prompts.lesson_fits("字" * 81, "zh") and prompts.lesson_fits("字" * 80, "zh")
    assert prompts.lesson_fits(" ".join(["word"] * 50), "en") and not prompts.lesson_fits(" ".join(["word"] * 51), "en")


def test_eval_brief_rounds_and_maps_nan():
    ev = {
        "horizons": {48: {"ic": {"mean": 0.123456, "icir": float("nan"), "months": 3, "segments": [0.1, float("nan")],
                                 "monotonic": {"rho": 0.9, "spread": 0.0123456, "groups": [0.0] * 5}},
                          "leg": {"netReturn": 0.05, "permPct": 0.99, "hitRate": 0.6, "exposure": 0.3,
                                  "breakEvenBps": 7.123456}}},
        "redundancy": {"maxAbs": 0.2, "with": "MA48", "ready": True},
        "complexity": {"nodes": 3, "windows": 1, "constants": 0, "cross": 0},
    }
    gates = [{"id": "G0", "passed": True, "note": "ok"}]
    brief = prompts.eval_brief(ev, gates)
    assert brief["primary"] == "12h"
    assert brief["horizons"]["12h"]["meanIc"] == 0.1235 and brief["horizons"]["12h"]["icir"] is None
    assert brief["horizons"]["12h"]["segments"] == [0.1, None]
    assert brief["horizons"]["12h"]["monotonic"] == 0.9 and brief["horizons"]["12h"]["spread"] == 0.0123
    # 之前落库的老结果没有顶减底的 t 和形状
    assert brief["horizons"]["12h"]["spreadT"] is None and brief["horizons"]["12h"]["shape"] is None
    assert brief["horizons"]["12h"]["breakEvenBps"] == 7.1235 and brief["horizons"]["12h"]["turnover"] is None
    # 老结果没算过 recent 那段
    assert brief["horizons"]["12h"]["recentIc"] is None and brief["horizons"]["12h"]["recentSharpe"] is None
    assert "recentFrom" not in brief
    # 之前落库的结果没有最像的前几个
    assert brief["redundancy"] == {"maxAbs": 0.2, "with": "MA48", "ready": True, "top": []}
    top = [{"name": "MA48", "abs": 0.212345, "expr": "Mean($close, 48) / $close"}]
    assert prompts.eval_brief({**ev, "redundancy": {**ev["redundancy"], "top": top}}, [])["redundancy"]["top"] == [
        {"name": "MA48", "abs": 0.2123, "expr": "Mean($close, 48) / $close"}]
    assert brief["gates"] == [{"id": "G0", "passed": True, "note": "ok"}]
    assert "holdout" not in brief and "timeseries" not in brief and "precheck" not in brief
    pre = {"rankAutocorr": 0.912345, "coverage": 1.0, "points": 52, "fundingHigh": 1.23456, "fundingLow": None}
    assert prompts.eval_brief({**ev, "precheck": pre}, gates)["precheck"] == {
        "rankAutocorr": 0.9123, "coverage": 1.0, "points": 52, "fundingHigh": 1.2346, "fundingLow": None}
    review = prompts.review_message({"name": "x", "hypothesis": "h", "expr": "$ret", "direction": -1}, brief)
    assert "MA48" in review and "声明的方向：因子值越大，未来 24 小时相对越弱" in review
    # 落过库的 holdout、时序报告键是字符串，NaN 已经成了 None
    ev["holdout"] = {"since": 5, "at": 9, "horizons": {"48": {"mean": 0.05, "icir": None, "months": 1, "samples": 60,
                                                              "monotonic": 0.7}}}
    ev["timeseries"] = {"symbols": ["BTCUSDT"], "horizons": {"48": {
        "mean": 0.02, "icir": 0.4, "months": 3, "sameSignSymbols": 1, "bySymbol": {"BTCUSDT": 0.021234},
        "netReturn": -0.1, "permPct": 0.5}}}
    brief = prompts.eval_brief(ev, gates)
    # 之前算的 holdout 没有标准误和逐点的 t
    assert brief["holdout"] == {"since": 5, "horizons": {"12h": {"meanIc": 0.05, "icir": None, "se": None, "t": None,
                                                                 "months": 1, "monotonic": 0.7, "samples": 60}}}
    assert brief["timeseries"] == {"horizons": {"12h": {"meanIc": 0.02, "icir": 0.4, "sameSignSymbols": 1,
                                                        "netReturn": -0.1}},
                                   "bySymbol": {"BTCUSDT": 0.0212}}
    # G9 的增量：老结果是三档 IC，现在是加进交易方案后的净 Sharpe
    old = prompts.eval_brief({**ev, "gain": {"horizons": {"48": {"base": 0, "with": 0.031, "gain": 0.031}},
                                             "sharpe": {"base": 0, "with": 1.2, "gain": 1.2}}}, gates)
    assert old["gain"] == {"12h": 0.031} and old["gainSharpe"]["gain"] == 1.2
    new = prompts.eval_brief({**ev, "gain": {"sharpe": {"base": 1.0, "with": 1.3, "gain": 0.3},
                                             "recentSharpe": {"base": 0.8, "with": 1.0, "gain": 0.2},
                                             "netReturn": {"base": 2.0, "with": 2.5}},
                              "recentFrom": 1_640_995_200_000}, gates)
    assert "gain" not in new and new["gainSharpe"] == {"base": 1.0, "with": 1.3, "gain": 0.3}
    assert new["gainSharpeRecent"] == {"base": 0.8, "with": 1.0, "gain": 0.2} and new["recentFrom"] == "2022-01-01"
