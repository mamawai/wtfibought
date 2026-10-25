"""挖掘图的提示词：生成角色（提假设、写表达式、门没过重写、轮后写经验）与评审角色。

DSL 语法、算子表、列表、窗口换算表从代码里拼，加算子不用改提示词。提示词固定中文，假设、评语与经验的语言按界面语言指定。
喂回给生成角色的数字（门没过的反馈、轮后复盘）只来自搜索段，不带 holdout。
"""

from __future__ import annotations

import json
import math
import re
from typing import Any, Mapping, Sequence

import numpy as np

from . import ops, reference
from .data.panel import FEATURE_COLUMNS
from .dsl import COMPLEXITY, MAX_TEXT, MAX_WINDOW, parse
from .eval.cs_ic import SHAPES, shape
from .eval.gates import fmt
from .eval.params import Thresholds

_LANG_NAME = {"zh": "中文", "en": "英文"}

OPERATOR_DOCS = {
    "Abs": "绝对值", "Sign": "符号 -1/0/1", "Log": "自然对数，非正数为 NaN",
    "Pow": "x 的 y 次方", "Greater": "两者取大", "Less": "两者取小",
    "Gt": "x > y 出 1 否则 0", "Lt": "x < y 出 1 否则 0", "If": "If(c, a, b)：c 非零取 a 否则取 b",
    "Ref": "n 根 bar 之前的值", "Delta": "当前值减 n 根之前的值",
    "Mean": "窗内均值", "Sum": "窗内求和", "Std": "窗内样本标准差", "Var": "窗内样本方差",
    "Max": "窗内最大", "Min": "窗内最小", "Med": "窗内中位数", "Mad": "窗内平均绝对偏差",
    "Rank": "当前值在窗内的分位 (0,1]", "Skew": "窗内偏度", "Kurt": "窗内超额峰度",
    "EMA": "span=w 的指数均线", "WMA": "线性加权均线，最新一根权重最大",
    "Quantile": "Quantile(x, w, q)：窗内 q 分位数", "IdxMax": "窗内最大值离现在几根 bar", "IdxMin": "窗内最小值离现在几根 bar",
    "Slope": "窗内对 bar 序号回归的斜率", "Rsquare": "该回归的 R²", "Resi": "当前点相对该回归线的残差",
    "Corr": "Corr(x, y, w)：窗内相关系数", "Cov": "Cov(x, y, w)：窗内协方差",
    "CsRank": "截面秩：同一时刻 x 在池内币里的分位 (0,1]", "CsDemean": "截面去均值：x 减同一时刻池内币的均值",
    "CsZ": "截面 z 分数：(x − 同一时刻池内币均值) / 截面标准差",
}

COLUMN_DOCS = {
    "open": "开盘价", "high": "最高价", "low": "最低价", "close": "收盘价",
    "volume": "成交量（币）", "quote_volume": "成交额（USDT）", "trades": "成交笔数",
    "taker_buy_base": "主动买入量（币）", "taker_buy_quote": "主动买入额（USDT）",
    "ret": "本根 bar 的对数收益 log(close/上一根 close)",
    "funding_rate": "最近一次已结算的资金费率，已折算成每 8 小时的等价费率（各币结算间隔不同，有 1、4、8 小时的），一期之内不变",
    "btc_close": "同一时刻 BTC 收盘价，BTC 自己的面板上是空的", "btc_ret": "同一时刻 BTC 的对数收益，BTC 自己的面板上是空的",
    "liq_long": "本根 bar 内多头被强平的名义额（USDT），只有产品币有数",
    "liq_short": "本根 bar 内空头被强平的名义额（USDT），只有产品币有数", "liq_count": "本根 bar 内强平笔数，只有产品币有数",
    "premium": "永续价相对指数价的溢价率，本根 bar 收盘值，正数是永续更贵；资金费率就是由它结算出来的",
    "oi": "全市场持仓量（币），约 5 分钟一条取收盘前最近值", "oi_value": "全市场持仓名义（USDT）",
    "ls_top_acct": "大户多空账户数之比", "ls_top_pos": "大户多空持仓量之比", "ls_acct": "全体账户多空数之比",
    "taker_ls_vol": "taker 买量与卖量之比",
    "hour": "bar 开始时刻的 UTC 小时 0~23；同一时刻所有币都一样，只能和别的列交互着用",
}

WINDOW_TABLE = "4=1h、16=4h、48=12h、96=24h、192=48h、672=7d"

# 机制清单：ID → (机制, 用哪些列, 典型持有期)，出题按 ID 选；price 是已证做不成的纯价格类，other 给清单外的机制
FAMILIES = {
    "basis": ("基差 / carry：溢价高的币在付费、被做空，折价的反过来", "premium、funding_rate", "7 天到 3 周"),
    "oi_crowd": ("持仓拥挤：持仓在变而价格没怎么动，有人在悄悄建仓或平仓", "oi、oi_value、ret", "1~7 天"),
    "ls_split": ("大户与全体分歧：大户持仓多空比和全体账户多空比背离", "ls_top_pos、ls_top_acct、ls_acct", "1~7 天"),
    "taker_persist": ("taker 失衡的持续性：主动买卖失衡连着多日同一个方向", "taker_buy_quote、quote_volume、taker_ls_vol", "3~7 天"),
    "liquidity": ("流动性 / 规模：成交额、持仓名义、换手的相对水平和变化", "quote_volume、oi_value、trades", "7 天到 3 周"),
    "idio_vol": ("对 BTC 的特质波动：剔掉 BTC 带动的部分后这个币自己的波动", "ret、btc_ret", "7 天"),
    "funding_premium_gap": ("资金费与溢价错位：已结算的费率和当前溢价对不上", "funding_rate、premium", "1~7 天"),
    "volume_weighted_basis": ("量能给溢价当权重：成交额加权的溢价对比等权的", "premium、quote_volume、volume", "7 天"),
    "price": ("纯价格类：动量、反转、突破、K 线形态、振幅、相对 BTC。已证做不成：换手高、成本吃掉毛收益，再加空头尾部", "", ""),
    "other": ("清单外的机制", "", ""),
}

# 按用到的列分的数据类，出题时统计成败用；强平列只有产品币有数，不算
DATA_CLASSES = {
    "溢价": ("premium",), "资金费": ("funding_rate",), "持仓量": ("oi", "oi_value"),
    "大户多空比": ("ls_top_acct", "ls_top_pos"), "全体多空比": ("ls_acct",),
    "taker": ("taker_buy_base", "taker_buy_quote", "taker_ls_vol"),
    "成交量": ("volume", "quote_volume", "trades"), "纯价格": ("open", "high", "low", "close", "ret"),
    "BTC": ("btc_close", "btc_ret"), "hour": ("hour",),
}


# ========== 生成角色 ==========


def _dsl_docs() -> str:
    """列、语法、算子：生成、评审、复盘三个角色都要读公式，共用这一份"""
    columns = "\n".join(f"- ${c}：{COLUMN_DOCS.get(c, '')}" for c in FEATURE_COLUMNS)
    operators = "\n".join(f"- {name}：{OPERATOR_DOCS.get(name, '')}" for name in ops.OPERATORS)
    return f"""可用的列（表达式里写成 $列名）：
{columns}

表达式语法：Python 表达式写法，只允许 + - * / 与负号、数字字面量、$列、下面这些函数。窗口单位是 bar（15 分钟），必须是 1~{MAX_WINDOW} 的整数字面量，换算：{WINDOW_TABLE}。
{operators}

截面算子（CsRank / CsDemean / CsZ）在同一时刻跨池内币算，不看别的时刻。评估本来就按截面排序，最外层套 CsRank 不改变 IC；它们的用处是把各币量纲不同的量先拉到可比的尺度再做运算或滚动，比如先截面标准化再和别的量相减。

复杂度上限，解析时就拒：不同的子式 ≤ {COMPLEXITY['nodes']} 个、不同的窗口值 ≤ {COMPLEXITY['windows']} 个、不同的常量值 ≤ {COMPLEXITY['constants']} 个（分位数也算常量）、不同的截面算子 ≤ {COMPLEXITY['cross']} 个，整条不超过 {MAX_TEXT} 字。都按不同的数：同一个子式、同一个窗口值或常量值重复写只算一个，比如四个桶都用 Sum(…, 672) 只算 1 个窗口。"""


def system_gen(facts: Mapping[str, Any], lang: str) -> str:
    known = "\n".join(f"- {name}：{text}（{what}）"
                      for name, (text, what) in {**reference.BASELINES, **reference.KNOWN_EFFECTS}.items())
    # 强平桶只给产品币装配（refresh 里 s in product），池里别的币全是空的
    liq = (f"强平三列只有产品币有数、从 {facts['liqFrom']} 起，池里别的币全是空的，截面因子用不了" if facts.get("liqFrom")
           else "强平三列暂时没有数据")
    tiers = " / ".join(f"{c:g}" for c in facts["costTiers"])
    # 交易方案里两条腿的份额，页面上设的
    combo, carry = f"{1 - facts['carryShare']:.0%}", f"{facts['carryShare']:.0%}"
    return f"""你在给一套加密永续合约的因子挖掘系统提假设、写因子表达式。

数据：币安 USDT 永续，每月初按过去 30 天日均成交额取前 {facts['poolSize']} 个币进池（历史上进过池的共 {facts['poolSymbols']} 个，含已下架的），15 分钟 K 线，{facts['from']} 到 {facts['to']}。{liq}。
{facts['holdoutFrom']} 起是锁定的 holdout：硬门、评审、你看到的所有数字都只来自它之前的数据；过门的候选会在 holdout 上单独算一次 IC 给人决定入不入库，结果不会反馈给你。
标准答案是截面的：每 6/12/24 小时取一个时刻，把当时在池的币按因子值排序，与它们之后 6/12/24 小时的对数涨跌幅（信号那根收盘后晚 15 分钟建仓起算）算 Spearman 相关（截面 rank IC），主档 24 小时，和实盘的交易方案一样 24 小时调一次仓。因子要回答的是"此刻哪些币接下来比别的币强"，不是"这个币接下来涨不涨"。
另外会对 {', '.join(facts['symbols'])} 这几个产品币按时间序列算一份时序报告给评审看，不做门。

{_dsl_docs()}

评估是代码硬门，不是人看：
- G0 前视自检。
- G1 样本量。
- G2 主档 |IC| ≥ 0.01、月度 IC 均值的 t（ICIR × √月数）≥ 3，且按因子分 3 组、每期收益两头各截掉 5% 后，顶组减底组每期之差和 IC 同向、t ≥ 2（三组的形状只报不判，只有一头有效也行）。
- G3 四段至少三段同号，且最近一段必须同号。
- G4 三档同号。
- G5 多空腿（排进头部 1/5 做多、尾部 1/5 做空，掉出 2/5 才平，中间不动，每边按 7 天波动的倒数分仓、单个币最多是这一边平均仓位的 2 倍，每 24 小时调一次）的截面排列检验显著；这条腿按币的流动性分档扣单边 {tiers} bps 成本、持有期间按真实结算记录付或收资金费（多头付正费率、空头收）后的净收益也会算出来，只报不判。
- G6 与参照集、库里的因子、观察区里的候选截面相关 |ρ| < 0.7，参照集里有规模（成交额、持仓名义）、换手（成交额 ÷ 持仓）、7 天波动、7 天最大涨幅、7 天和 3 周动量、对 BTC 的 beta、资金费率水平、基差水平这些早有文献的效应，和它们高度相关就是老效应换了个列名。
- G7 复杂度（上限见上面表达式语法那段，解析时就会拒）。
- G8 换个窗口的翻版不算新；加法、乘法换个左右也不算。
- G9 加进实盘的交易方案后净 Sharpe 至少升 0.1（随机因子加进去，95% 都升不到这么多，平均还是往下拉）：方案是 {combo} 资金做库里因子的组合（每个时刻先转截面秩再加权：权重的符号跟 24 小时 IC，大小是这条因子单独做上面那条多空腿扣完成本和资金费的净收益，单独做不赚钱的权重是 0；多空两头按上面的缓冲带选币、按波动分仓）、{carry} 做基差 carry（做空 7 天溢价高的、做多折价的，不碰上市不满一年的币），24 小时调一次，扣成本和资金费，你的因子加进组合那部分。
- G10 主档截面 IC 的符号要和你声明的方向一样。
- 另外每条都会算"去老效应"：把下面这些已知效应一起回归掉，剩下的 IC 还有原来的百分之几、显不显著；它不是门，但评审和人都会看，老效应和别的量混在一起、相关度压到 0.7 以下的，在这里会露出来。
- 同批过门的还会互相查一遍：结构重合或截面相关 ≥ 0.7 的只留增量大的那条；留下的再算一次相对排在前面那几条的增量写给人看，不是门。
- 写表达式时同时声明方向：按假设，因子值越大未来 24 小时相对越强填 1，越弱填 -1。方向由假设决定；把表达式取负、同时把方向翻过来，G10 的结果不变。IC 和假设反号，说明数据不支持这条假设。
- 已知效应（名字：公式（是什么））；"去老效应"回归掉的是其中 {len(reference.KNOWN_EFFECTS)} 条老效应，再加 24 小时反转（Alpha158 的 {reference.REVERSAL}），G6 除了这些还比 Alpha158 的一百多条量价因子：
{known}
- 门没过会把没过的门和关键数字给你，同一假设最多再写两版；每一版都算一次试验，进同批多重检验。
- 之前几轮复盘写下的经验（lessons）会放在出题提示词里。
- 截面上比的是币与币：价格、成交量、持仓量的绝对水平在币之间不可比，直接排序排出来的是"哪个币大"。用收益、比值、z-score、秩这类各币可比的量。
- 换手越高成本越高：24 小时持有期上，变化很快的短窗信号要扣更多成本。
- 资金费率一期才变一次（各币结算间隔不同，这一列已折算成每 8 小时的等价费率），对它做短窗差分几乎全是 0。强平列只有产品币有数，截面上用不了；$hour 同一时刻所有币都一样，单独排序没有意义。
- 参数越少越好，先有一句能说清的经济理由，再写公式。

假设与说明用{_LANG_NAME[lang]}写，因子名用短英文 slug。"""


def propose_message(k: int, library: Sequence[Mapping[str, Any]], tried: Sequence[Mapping[str, Any]],
                    lessons: Sequence[Mapping[str, Any]], outcomes: Mapping[str, Any], note: str, lang: str) -> str:
    """lessons、tried 都是最新在前；outcomes 是 outcomes() 算的两张成败表"""
    lib = "\n".join(f"- {f['name']}：{f['expr']}（{f['hypothesis']}）" for f in library) or "（空）"
    past = "\n".join(f"- [{t['outcome']}] {t['hypothesis']}｜{t['expr'] or '未写出'}｜{t['reason'] or ''}"
                     for t in tried) or "（无）"
    extra = f"\n本轮方向要求：{note}\n" if note.strip() else ""
    return f"""库里已有的因子（别重复，也别换个窗口翻版）：
{lib}
{_outcome_lines(outcomes)}
之前几轮复盘写下的经验（最新在前）：
{_lessons(lessons)}

已经试过的假设与结果（别再提）：
{past}
{extra}
可选的机制：
{_families()}
family 填清单里的 ID；机制不在清单里的填 other。

提出 {k} 条彼此不同的假设。只输出一个 JSON 数组，不要代码块、不要任何别的字：
[{{"name": "短英文 slug", "family": "清单里的 ID", "hypothesis": "一两句话（用{_LANG_NAME[lang]}）：什么现象、为什么能预测未来 24 小时哪些币比别的币强"}}]"""


def implement_message(cand: Mapping[str, Any], feedback: str | None, problem: str | None = None) -> str:
    """feedback 是上一版的门或评审意见；problem 是上一次输出本身的毛病（不是 JSON、表达式不合法、和评估过的某版一模一样）"""
    parts = [f"假设 [{cand['name']}]：{cand['hypothesis']}"]
    if feedback:
        parts.append(feedback)
    if problem:
        parts.append(f"上一次输出的问题：{problem}\n请改正。")
    body = "\n\n".join(parts)
    return f"""{body}

写之前先过一遍这几条，想清楚再写，别先交一版简单的、等门的数字回来再往上加东西：
1. 截面上排出来的到底是什么：会不会只是系统提示里那些已知效应换了个说法，和库里的因子差在哪。
2. 换手：窗口多长、信号变得多快，24 小时调一次仓扣不扣得起成本。
3. 资金费：排在做多那头和做空那头的币，费率通常各是正是负，持仓期间哪头在付。
4. 方向：按假设，因子值越大越强还是越弱。

把这条假设写成一条表达式，并按假设声明方向：因子值越大、未来 24 小时相对越强填 1，越弱填 -1。只输出一个 JSON 对象，不要代码块、不要任何别的字：
{{"expr": "表达式", "direction": 1 或 -1, "note": "一句话说明公式怎么对应假设"}}"""


def direction_message(cand: Mapping[str, Any]) -> str:
    """复评老候选用：它们写表达式时还没有声明方向这一项，只给假设与表达式补一个，不给任何数字"""
    return f"""假设 [{cand['name']}]：{cand['hypothesis']}
表达式：{cand['expr']}
公式说明：{cand.get('note') or '（无）'}

按这条假设，这个表达式的值越大，币未来 24 小时相对越强还是越弱？越强填 1，越弱填 -1。只输出一个 JSON 对象，不要代码块、不要任何别的字：
{{"direction": 1 或 -1}}"""


def gate_feedback(cand: Mapping[str, Any]) -> str:
    """门没过：每道没过的门一句，再附主档的关键数字；只摆数字，不替模型下结论。
    之前被评审打回过的（cand 带着 review），上一版就是照它改的，那次的意见一起带上"""
    failed = "\n".join(f"- {g['id']} {g['name']}：{g['note']}" for g in cand["gates"] if not g["passed"])
    said = "越大越强" if cand["direction"] > 0 else "越大越弱"
    reviewed = f"\n上一版是照评审意见改的，那次的意见：\n{_review_text(cand['review'])}" if cand.get("review") else ""
    return f"""上一版表达式：{cand['expr']}（声明的方向 {cand['direction']}，因子值{said}）
没过的门：
{failed}
主档（{hours(primary_of(cand['eval']))}）搜索段的数字：{key_numbers(cand['eval'])}{reviewed}
同一假设，换一种写法；这些数字说明了什么由你判断。方向由假设决定，沿用上一版声明的 {cand['direction']}，这一版填别的也按 {cand['direction']} 判。"""


def revise_feedback(cand: Mapping[str, Any], review: Mapping[str, Any]) -> str:
    return f"上一版表达式：{cand['expr']}\n{_review_text(review)}"


def _review_text(review: Mapping[str, Any]) -> str:
    """评审的理由和改法都给：理由说哪里不对，改法说怎么改"""
    return (f"评审意见：{review.get('comment') or '（没写）'}\n"
            f"评审建议的改法：{review.get('suggestion') or '（没给）'}")


def primary_of(ev: Mapping[str, Any]) -> int:
    """判门用的那一档；落过库的老结果没记，是 12h"""
    return int(ev.get("primary") or 48)


def key_numbers(ev: Mapping[str, Any]) -> str:
    """主档 IC、ICIR、顶减底、三组形状、换手、资金费、盈亏平衡成本、冗余、去老效应后的 IC，算过预检的再带预检的数，一行；
    落过库的键是字符串，先归一。冗余报最像的前 3 个带表达式；之前落库的结果没有 top，只报最像的那一个"""
    h = {int(k): v for k, v in ev["horizons"].items()}[primary_of(ev)]
    ic, leg, red, pure = h["ic"], h["leg"], ev["redundancy"], neutral_brief(ev)
    mono = ic["monotonic"]
    spread = mono["spread"]
    top = red.get("top") or []
    if not red["ready"]:
        redundancy = "冗余：参照网格还没算好"
    elif top:
        peers = "、".join(f"{x['name']} {fmt(x['abs'], 2)}（{x['expr']}）" for x in top)
        redundancy = f"冗余前 {len(top)}（截面 |ρ|）：{peers}"
    elif red["with"]:
        redundancy = f"冗余：与 {red['with']} 的截面 |ρ| {fmt(red['maxAbs'], 2)}"
    else:
        redundancy = "冗余：没有参照因子"
    parts = [
        f"月度截面 IC 均值 {fmt(ic['mean'])}", f"ICIR {fmt(ic['icir'], 2)}",
        f"截尾后顶组减底组每期 {fmt(None if spread is None else spread * 1e4, 1)} bps、t {fmt(mono['spreadT'], 1)}",
        f"三组形状：{SHAPES.get(shape(mono, ic['mean']), '算不出')}",
        f"每期换手 {fmt(leg.get('turnover'), 2)}（两条腿全换是 1）",
        f"多空腿每期资金费 {fmt(leg.get('fundingBps'), 1)} bps（正是收、负是付，已算进收益）",
        f"盈亏平衡单边成本 {fmt(leg.get('breakEvenBps'), 1)} bps", redundancy,
        (f"回归掉 {pure['against']} 条老效应后 IC {fmt(pure['meanIc'])}（剩原来的 {fmt(pure['retained'], 2)}），t {fmt(pure['t'], 1)}"
         if pure else "去老效应：参照网格还没算好"),
    ]
    pre = ev.get("precheck")
    if pre:
        parts.append(f"24h 秩自相关 {fmt(pre['rankAutocorr'], 2)}、覆盖 {fmt(pre['coverage'], 2)}、"
                     f"因子值高的一头资金费 {fmt(pre['fundingHigh'], 1)} bps / 低的一头 {fmt(pre['fundingLow'], 1)} bps")
    return "、".join(parts)


def neutral_brief(ev: Mapping[str, Any]) -> dict[str, Any] | None:
    """去老效应标签：回归掉老效应后的主档 IC、它占原 IC 的比例、t（|ICIR| × √月数）；网格没算好是 None。
    落过库的 NaN 已经是 None，键是字符串"""
    pure = ev.get("neutral")
    if not pure:
        return None
    raw = _f({int(k): v for k, v in ev["horizons"].items()}[primary_of(ev)]["ic"]["mean"])
    mean, icir = _f(pure["mean"]), _f(pure["icir"])
    return {"meanIc": _r(mean), "icir": _r(icir), "t": _r(abs(icir) * math.sqrt(pure["months"])),
            "retained": _r(mean / raw if raw else math.nan), "against": pure["against"]}


def _lessons(lessons: Sequence[Mapping[str, Any]]) -> str:
    return "\n".join(f"- [{x['kind']}] {x['text']}" for x in lessons) or "（还没有）"


def _families() -> str:
    """机制清单一条一行；没有列和持有期的（price、other）只写机制"""
    return "\n".join(f"- {fid}：{what}（列：{cols}；持有期：{hold}）" if cols else f"- {fid}：{what}"
                     for fid, (what, cols, hold) in FAMILIES.items())


def outcomes(rows: Sequence[Mapping[str, Any]]) -> dict[str, dict[str, dict[str, Any]]]:
    """评估过的候选（store.evaluated_candidates）在主档上的成败，按用到的数据类、按机制 ID 各汇总一张：
    试过几条、有信号几条（和 G2 同口径）、单条多空腿扣完成本还赚几条、过 G9 几条，换手和盈亏平衡成本的中位数。
    用到几类数据就在几类里各算一次；family 不在清单里的（老候选是中文）算 other"""
    t = Thresholds()
    by_class: dict[str, list[dict[str, Any]]] = {}
    by_family: dict[str, list[dict[str, Any]]] = {}
    for r in rows:
        ev = r["eval"]
        h = ev["horizons"][str(ev["primary"])]
        ic, leg = h["ic"], h["leg"]
        item = {"signal": abs(_f(ic["mean"])) >= t.ic_min and abs(_f(ic["icir"])) * math.sqrt(ic["months"]) >= t.t_min,
                "net": _f(leg.get("netReturn")) > 0,
                "g9": any(g["id"] == "G9" and g["passed"] for g in r["gates"]),
                "turnover": leg.get("turnover"), "breakEven": leg.get("breakEvenBps")}
        columns = parse(r["expr"], FEATURE_COLUMNS).columns()
        for name, cols in DATA_CLASSES.items():
            if columns & set(cols):
                by_class.setdefault(name, []).append(item)
        by_family.setdefault(r["family"] if r["family"] in FAMILIES else "other", []).append(item)
    return {"byClass": {name: _tally(by_class[name]) for name in DATA_CLASSES if name in by_class},
            "byFamily": {fid: _tally(by_family[fid]) for fid in FAMILIES if fid in by_family}}


def _tally(items: list[dict[str, Any]]) -> dict[str, Any]:
    """成败表的一行；没数的不进中位数"""
    def median(key: str) -> float | None:
        xs = [x[key] for x in items if x[key] is not None]
        return float(np.median(xs)) if xs else None

    return {"tried": len(items), "signal": sum(x["signal"] for x in items), "net": sum(x["net"] for x in items),
            "g9": sum(x["g9"] for x in items), "turnover": median("turnover"), "breakEven": median("breakEven")}


def _outcome_lines(table: Mapping[str, Any]) -> str:
    """两张成败表；都空（还没有现在口径的候选）整段不出"""
    if not table["byClass"] and not table["byFamily"]:
        return ""
    t = Thresholds()

    def line(name: str, x: Mapping[str, Any]) -> str:
        turnover = "—" if x["turnover"] is None else f"{x['turnover']:.2f}"
        even = "—" if x["breakEven"] is None else f"{x['breakEven']:.1f} bps"
        return (f"- {name}：试过 {x['tried']}、有信号 {x['signal']}、扣成本还赚 {x['net']}、过 G9 {x['g9']}、"
                f"换手中位 {turnover}、盈亏平衡中位 {even}")

    classes = "\n".join(line(name, x) for name, x in table["byClass"].items())
    families = "\n".join(line(fid, x) for fid, x in table["byFamily"].items())
    return (f"\n已试过的候选按数据类的成败（搜索段数字；有信号 = |IC| ≥ {t.ic_min:g} 且 t ≥ {t.t_min:g}）：\n{classes}\n"
            f"按机制 ID：\n{families}\n")


# ========== 轮后复盘 ==========

# 复盘时的结局说法；passed 是 commit 之前的状态，commit 会把它标成观察中
_OUTCOMES = {"passed": "过门、过评审、过同批 BH，进观察区等人入库", "rejected_gates": "硬门拒",
             "rejected_review": "评审拒", "rejected_fdr": "同批 BH 拒", "failed": "失败"}
# 一条经验最长：中文按字数、英文按词数；超了代码直接丢
LESSON_MAX = {"zh": 80, "en": 50}


def lesson_fits(text: str, lang: str) -> bool:
    """经验得是一句规律：不带 $ 列名和括号（那是在抄表达式），长度不超过 LESSON_MAX"""
    if "$" in text or "(" in text or ")" in text:
        return False
    return (len(text.split()) if lang == "en" else len(text)) <= LESSON_MAX[lang]


def system_reflect(lang: str) -> str:
    limit = f"英文不超过 {LESSON_MAX['en']} 词" if lang == "en" else f"中文不超过 {LESSON_MAX['zh']} 字"
    return f"""你在给一套加密永续合约的因子挖掘系统做轮后复盘。每一轮生成角色提几条假设、写成表达式，代码硬门按截面口径判（截面 IC、截尾后顶组减底组、四段、三档、多空腿的排列检验、冗余、复杂度、加进实盘交易方案后净 Sharpe 的增量、IC 方向与假设一致），门没过的同一假设最多重写两版，过门的再由评审看一遍。
你看这一轮全部候选的假设、家族、结局和几个关键数字，写下几条经验。它们会原样放进之后几轮的出题提示词里，给出题的人看。
出题的人另外能看到之前每条假设的最终表达式、结局和没过的门，所以经验不写某一条候选的复盘，要写换一条假设也用得上的规律：哪类数据、哪种构造、多空两头的资金费怎么付，一再出问题或一再有效，为什么。
数字全部来自 holdout 之前的搜索段。经验用{_LANG_NAME[lang]}写，每条一句话、{limit}，不抄表达式，不出现 $ 列名和括号，数字最多带一两个关键的；超长的、带表达式的代码会直接丢掉。"""


def _reflect_outcome(c: Mapping[str, Any]) -> str:
    """结局一句：硬门拒的列没过的门，评审拒的带评语，失败的带原因；都不带数字"""
    outcome = _OUTCOMES.get(c["status"], c["status"])
    if c["status"] == "rejected_gates" and c.get("gates"):
        failed = "、".join(f"{g['id']} {g.get('name') or ''}".strip() for g in c["gates"] if not g["passed"])
        return f"{outcome}（没过的门：{failed}）"
    if c["status"] in ("rejected_review", "failed") and c.get("reason"):
        return f"{outcome}（{c['reason']}）"
    return outcome


def _reflect_numbers(ev: Mapping[str, Any]) -> str:
    """主档四个数：IC、换手、盈亏平衡、资金费"""
    h = {int(k): v for k, v in ev["horizons"].items()}[primary_of(ev)]
    leg = h["leg"]
    return (f"IC {fmt(h['ic']['mean'])}、换手 {fmt(leg.get('turnover'), 2)}、盈亏平衡 {fmt(leg.get('breakEvenBps'), 1)} bps、"
            f"多空腿资金费 {fmt(leg.get('fundingBps'), 1)} bps")


def reflect_message(candidates: Sequence[Mapping[str, Any]], lessons: Sequence[Mapping[str, Any]], lang: str) -> str:
    """lessons 是已有的最近几条，最新在前。不给表达式：给了模型就写成"某条不要写成某表达式"的单条复盘"""
    blocks = []
    for c in candidates:
        lines = [f"{c['seq']}. [{c['name']}]" + (f"（{c['family']}）" if c.get("family") else "") + f" 假设：{c['hypothesis']}",
                 f"   结局：{_reflect_outcome(c)}；评估了 {c.get('evaluations', 0)} 版"]
        if c.get("eval"):
            lines.append(f"   主档：{_reflect_numbers(c['eval'])}")
        blocks.append("\n".join(lines))
    candidates_text = "\n".join(blocks)
    return f"""这一轮的候选与结局：
{candidates_text}

已有的经验（最新在前）：
{_lessons(lessons)}

写 1~5 条之后出题、写公式用得上的经验，别和已有的重复，别复述某一条候选。kind：avoid = 以后别这么做，try = 值得接着试，note = 别的观察。
只输出一个 JSON 数组，不要代码块、不要任何别的字：
[{{"kind": "avoid|try|note", "text": "一句话（用{_LANG_NAME[lang]}）"}}]"""


# ========== 评审角色 ==========


def system_review(lang: str) -> str:
    return f"""你是加密永续合约因子研究的评审。数字已经由代码算过并过了硬门，你不对数字投票。你只判三件事：
1. 表达式和假设对不对得上：公式算的是不是假设说的那个东西，窗口、归一化有没有跑偏；声明的方向是不是假设说的那个方向（代码只核对声明的方向和 IC 符号一不一样，声明本身对不对由你看）。
2. 经济上讲不讲得通：这条假设在 15 分钟 K 线、6~24 小时持有期的永续合约上有没有一个说得出口的机制。
3. 硬门看不见的问题：常量凑数、把一个已知因子换皮、依赖历史很短的列、结果实际上由某个退化项主导。

裁决：pass = 可以进观察区等人决定入库；revise = 假设对但公式要改，给出具体改法，改法要能在下面的复杂度上限内写出来，给公式前先数一遍；reject = 假设本身不成立或就是换皮。
评语用{_LANG_NAME[lang]}写。
公式算的是什么以下面的说明为准，别按算子名字猜：

{_dsl_docs()}"""


def review_message(cand: Mapping[str, Any], brief: Mapping[str, Any]) -> str:
    said = "因子值越大，未来 24 小时相对越强" if cand["direction"] > 0 else "因子值越大，未来 24 小时相对越弱"
    return f"""假设 [{cand['name']}]：{cand['hypothesis']}
表达式：{cand['expr']}
公式说明：{cand.get('note') or '（无）'}
声明的方向：{said}

代码算出的数字（供参考，你不对它们投票）：
{json.dumps(brief, ensure_ascii=False, indent=1)}

只输出一个 JSON 对象，不要代码块、不要任何别的字：
{{"verdict": "pass|revise|reject", "comment": "一两句话", "suggestion": "revise 时给具体改法，否则留空"}}"""


def json_retry() -> str:
    return "刚才那段不是合法 JSON。重来一次，只输出 JSON 本身，不要代码块、不要任何别的字。"


# ========== 解析与摘要 ==========


def parse_object(text: str) -> dict[str, Any] | None:
    return _first(text, r"\{.*\}", dict)


def parse_list(text: str) -> list[Any] | None:
    return _first(text, r"\[.*\]", list)


def _first(text: str, pattern: str, kind: type) -> Any:
    """模型爱裹代码块或多说两句，取里头的 JSON；贪婪匹配不行再试非贪婪"""
    for candidate in (pattern, pattern.replace(".*", ".*?")):
        found = re.search(candidate, text or "", re.S)
        if not found:
            continue
        try:
            obj = json.loads(found.group())
        except json.JSONDecodeError:
            continue
        return obj if isinstance(obj, kind) else None
    return None


def eval_brief(ev: Mapping[str, Any], gates: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    """给评审和页面看的精简数字：判门用的主档、三档截面 IC、单调性与多空腿、每门一句；算过预检、增量、holdout、时序报告的再各带一段"""
    primary = primary_of(ev)
    red = ev["redundancy"]
    out = {
        "primary": hours(primary),
        "horizons": horizons_brief(ev["horizons"]),
        "redundancy": {"maxAbs": _r(red["maxAbs"]), "with": red["with"], "ready": red["ready"],
                       "top": [{"name": x["name"], "abs": _r(x["abs"]), "expr": x["expr"]}
                               for x in red.get("top") or []]},
        "complexity": ev["complexity"],
        "gates": [{"id": g["id"], "passed": g["passed"], "note": g["note"]} for g in gates],
    }
    if ev.get("neutral"):
        out["neutral"] = neutral_brief(ev)
    # 挖掘时评估前算的预检；手填评估、复评没有
    if ev.get("precheck"):
        pre = ev["precheck"]
        out["precheck"] = {"rankAutocorr": _r(pre["rankAutocorr"]), "coverage": _r(pre["coverage"]),
                           "points": pre["points"], "fundingHigh": _r(pre["fundingHigh"]),
                           "fundingLow": _r(pre["fundingLow"])}
    if ev.get("gain"):
        # 老结果的 G9 是加进库组合后三档 IC 的变化，现在的是加进交易方案后的净 Sharpe
        if ev["gain"].get("horizons"):
            out["gain"] = {hours(bars): _r(h["gain"]) for bars, h in ev["gain"]["horizons"].items()}
        if ev["gain"].get("sharpe"):
            out["gainSharpe"] = {k: _r(v) for k, v in ev["gain"]["sharpe"].items()}
    if ev.get("holdout"):
        out["holdout"] = holdout_brief(ev["holdout"])
    # 复评时发现它的那一轮切点比现在早才有：从那个切点起它都没见过
    if ev.get("unseen"):
        out["unseen"] = holdout_brief(ev["unseen"])
    if ev.get("timeseries"):
        out["timeseries"] = timeseries_brief(ev["timeseries"], primary)
    return out


def horizons_brief(horizons: Mapping[Any, Mapping[str, Any]]) -> dict[str, Any]:
    """三档的截面 IC、单调性与多空腿，单因子和组合共用；顶减底的 t 和三组形状是后来加的，之前落库的结果里没有"""
    out = {}
    for bars, h in horizons.items():
        ic, leg, mono = h["ic"], h["leg"], h["ic"]["monotonic"]
        out[hours(bars)] = {
            "meanIc": _r(ic["mean"]), "icir": _r(ic["icir"]), "months": ic["months"],
            "monotonic": _r(mono["rho"]), "spread": _r(mono["spread"]), "spreadT": _r(mono.get("spreadT")),
            "shape": shape(mono, ic["mean"]),
            "segments": [_r(v) for v in ic["segments"]],
            "netReturn": _r(leg.get("netReturn")), "permPct": _r(leg.get("permPct")),
            "hitRate": _r(leg.get("hitRate")), "exposure": _r(leg.get("exposure")), "turnover": _r(leg.get("turnover")),
            "fundingBps": _r(leg.get("fundingBps")), "breakEvenBps": _r(leg.get("breakEvenBps")),
        }
    return out


def holdout_brief(held: Mapping[str, Any]) -> dict[str, Any]:
    """se、t 是后来加的，之前算的 holdout 里没有"""
    return {"since": held["since"], "horizons": {
        hours(bars): {"meanIc": _r(h["mean"]), "icir": _r(h["icir"]), "se": _r(h.get("se")), "t": _r(h.get("t")),
                      "months": h["months"],
                       "monotonic": _r(h["monotonic"]), "samples": h["samples"]}
        for bars, h in held["horizons"].items()}}


def timeseries_brief(ts: Mapping[str, Any], primary: int) -> dict[str, Any]:
    """产品币时序报告：三档 IC、同号币数、方向腿净收益，主档再带每币 IC。落过库的键是字符串，先归一"""
    horizons = {int(k): v for k, v in ts["horizons"].items()}
    return {
        "horizons": {hours(bars): {"meanIc": _r(h["mean"]), "icir": _r(h["icir"]),
                                    "sameSignSymbols": h["sameSignSymbols"], "netReturn": _r(h["netReturn"])}
                     for bars, h in horizons.items()},
        "bySymbol": {s: _r(v) for s, v in horizons[primary]["bySymbol"].items()},
    }


def hours(bars: Any) -> str:
    return f"{int(bars) // 4}h"


def _f(v: Any) -> float:
    """落过库的 None 当 NaN"""
    return math.nan if v is None else float(v)


def _r(v: Any) -> float | None:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return None if f != f or f in (float("inf"), float("-inf")) else round(f, 4)
