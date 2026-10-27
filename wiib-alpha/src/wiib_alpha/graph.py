"""挖掘图：提假设 → 每条候选各自实现（带声明的方向）/评估/判门（没过带着数字重写）/评审 → 同批 BH
→ 同批幸存者互相查重 → 留下的算 holdout → 复盘写经验 → 进观察区。

评估、门、评审、BH 全部只看搜索段（cut 之前）；holdout 只给同批 BH 之后的幸存者算一次 IC，
结果进 eval 和观察区卡片，不进评审、重写、复盘的提示词，也不写进 tried 回灌给模型。
币池、参照网格、模型、store 都在 Context 里，不进 state。入库不在图里：观察区里人点了才入。
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Annotated, Any, TypedDict

import numpy as np
import pandas as pd
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from . import prompts
from .data.binance import day_name
from .data.panel import FEATURE_COLUMNS
from .data.pool import Pool
from .dsl import DslError, Expr, parse
from .eval.combo import Combo
from .eval.evaluate import cross_corr, evaluate
from .eval.gates import apply_fdr, fmt, gate_row, judge, origin_gate, passed
from .eval.params import EvalParams, Thresholds
from .eval.precheck import precheck
from .eval.recheck import recheck
from .reference import Grid
from .store import Store, clean
from .trade.scheme import Base, gain

logger = logging.getLogger(__name__)

MAX_PARSE_RETRIES = 2
MAX_REVISES = 2
# 门没过同一假设最多再写这么多版
MAX_REWRITES = 2
MAX_LESSONS = 5
LESSON_KINDS = ("avoid", "try", "note")


@dataclass
class Context:
    gen: BaseChatModel
    review: BaseChatModel
    # 评估和门只看 pool.search（cut 之前）；holdout 用 pool.rows
    pool: Pool
    # 全量骨架上的 Std($ret, 672)，多空腿按它分仓
    vol: np.ndarray
    # 全量骨架上的 $funding_rate，预检看因子值两头的当期资金费
    funding: np.ndarray
    # 产品币搜索段面板，只给时序报告
    products: dict[str, pd.DataFrame]
    # 参照网格没算好是 None，G6 不过
    reference_grid: Grid | None
    # G9 的基线：库里因子 + 基差 carry 的交易方案
    base: Base
    # 观察区候选在搜索段上的值，和库里因子一起进 G6 的对比集
    observing: Combo
    # 库里因子、观察区候选的表达式：lib:名字、obs:名字 → 表达式，冗余报最像的几个时带上
    peer_exprs: dict[str, str]
    known: set[str]
    library: list[dict[str, Any]]
    facts: dict[str, Any]
    # 研究方针的当前版：机制清单、出题和写公式的建议、评审标准
    policy: prompts.Policy
    store: Store
    params: EvalParams = field(default_factory=EvalParams)
    thresholds: Thresholds = field(default_factory=Thresholds)
    # 全池求值的并发闸，和手填评估共用：一次评估要把全池几百个币读一遍求值，扇出全开会把内存吃满
    evaluating: asyncio.Semaphore = field(default_factory=lambda: asyncio.Semaphore(2))
    # 候选 id → 最后一版在全量骨架上的值：评估时算的，同批筛查和 holdout 接着用，不再读一遍全池；轮末清掉
    full: dict[str, np.ndarray] = field(default_factory=dict)


def merge_by_id(left: list[dict[str, Any]], right: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """候选按 id 覆盖合并，Send 扇出回来的和 summarize 改过的都走这里"""
    merged = {c["id"]: c for c in left}
    for c in right:
        merged[c["id"]] = c
    return sorted(merged.values(), key=lambda c: c["seq"])


class RunState(TypedDict, total=False):
    run_id: str
    # 这一轮属于哪个 campaign，经验按它归档
    campaign_id: str | None
    lang: str
    k: int
    note: str
    hypotheses: list[dict[str, Any]]
    candidates: Annotated[list[dict[str, Any]], merge_by_id]


def build_graph(ctx: Context):
    evaluating = ctx.evaluating

    async def propose(state: RunState) -> dict[str, Any]:
        await emit(ctx.store, state["run_id"], "status", {"stage": "proposing"})
        tried = await ctx.store.tried()
        lessons = await ctx.store.lessons()
        outcomes = prompts.outcomes(await ctx.store.evaluated_candidates(), ctx.policy.families)
        system = prompts.system_gen(ctx.facts, state["lang"], ctx.policy)
        message = prompts.propose_message(state["k"], ctx.library, tried, lessons, outcomes, state.get("note", ""),
                                          state["lang"], ctx.policy)
        items = await ask_json(ctx.gen, system, message, prompts.parse_list)
        hypotheses = []
        for item in items or []:
            if isinstance(item, dict) and item.get("name") and item.get("hypothesis"):
                seq = len(hypotheses) + 1
                family = str(item.get("family") or "").strip()
                hypotheses.append({
                    "id": f"{state['run_id']}-{seq}", "run_id": state["run_id"], "seq": seq,
                    "name": str(item["name"]).strip(), "hypothesis": str(item["hypothesis"]).strip(),
                    "family": family if family in ctx.policy.families else "other", "status": "proposed", "attempts": 0,
                })
            if len(hypotheses) >= state["k"]:
                break
        for h in hypotheses:
            await ctx.store.upsert_candidate(h)
            await emit(ctx.store, state["run_id"], "candidate", brief(h))
        await emit(ctx.store, state["run_id"], "log", {"text": f"提出 {len(hypotheses)} 条假设"})
        return {"hypotheses": hypotheses}

    def fan_out(state: RunState):
        if not state.get("hypotheses"):
            return "summarize"
        return [Send("candidate", {"run_id": state["run_id"], "lang": state["lang"], "cand": h})
                for h in state["hypotheses"]]

    async def candidate(payload: dict[str, Any]) -> dict[str, Any]:
        cand = dict(payload["cand"])
        run_id, lang = payload["run_id"], payload["lang"]
        system = prompts.system_gen(ctx.facts, lang, ctx.policy)
        # 上一版的门或评审意见，下一版写的时候带上
        feedback: str | None = None
        # 上一版的结局：重写时没写出新的表达式，这条假设就停在它上面
        settled: tuple[str, str] | None = None
        # 这条假设评估过的表达式 → 第几版，重写写回一模一样的不再评估
        written: dict[str, int] = {}
        # 门没过重写时沿用的方向：重写的提示里有 IC，方向不能跟着改；评审打回的可以改
        locked: int | None = None
        revises = rewrites = 0
        while True:
            expr, problem = await _implement(cand, system, feedback, written, locked)
            if expr is None:
                if settled is None:
                    cand["status"], cand["reason"] = "failed", f"连试三次都没写出能用的表达式：{problem}"
                else:
                    cand["status"], cand["reason"] = settled[0], f"{settled[1]}；{prompts.STUCK}：{problem}"
                break
            written[str(expr)] = len(written) + 1
            cand["expr"], cand["canonical"] = str(expr), expr.canonical()
            cand["status"], cand["reason"] = "evaluating", None
            await _save(ctx.store, run_id, cand)
            async with evaluating:
                ev = await asyncio.to_thread(_evaluate, cand["id"], expr)
            gates = judge(ev, ctx.thresholds, ctx.known, cand["direction"])
            cand["eval"], cand["gates"] = clean(ev), clean(gates)
            # 每评估一次就是一次试验，门没过重写的、被评审打回的版本都算
            cand["evaluations"] = cand.get("evaluations", 0) + 1
            if not passed(gates):
                cand["reason"] = "；".join(f"{g['id']} {g['note']}" for g in gates if not g["passed"])
                if rewrites < MAX_REWRITES:
                    rewrites += 1
                    cand["status"] = "rewrite"
                    settled = ("rejected_gates", cand["reason"])
                    await _save(ctx.store, run_id, cand)
                    feedback = prompts.gate_feedback(cand)
                    locked = cand["direction"]
                    continue
                cand["status"] = "rejected_gates"
                break
            cand["status"] = "reviewing"
            await _save(ctx.store, run_id, cand)
            review = await _review(cand, lang)
            cand["review"] = review
            verdict = review.get("verdict")
            if verdict == "pass":
                cand["status"] = "passed"
                break
            if verdict == "revise" and revises < MAX_REVISES:
                revises += 1
                feedback = prompts.revise_feedback(cand, review)
                locked = None
                cand["status"] = "revise"
                settled = ("rejected_review", review.get("comment") or verdict)
                await _save(ctx.store, run_id, cand)
                continue
            cand["status"] = "rejected_review"
            cand["reason"] = review.get("comment") or verdict
            break
        await _save(ctx.store, run_id, cand)
        return {"candidates": [cand]}

    def _evaluate(cand_id: str, expr: Expr) -> dict[str, Any]:
        """全池求值一次，值留在 ctx.full 给同批筛查和 holdout 用；先算预检，再正式评估，预检的数记进 eval"""
        values = ctx.pool.values(expr)
        ctx.full[cand_id] = values.f
        pick = ctx.pool.in_search
        pre = precheck(ctx.pool.search, values.f[pick], ctx.funding[pick], ctx.params)
        ev = evaluate(expr, ctx.pool, ctx.vol, ctx.params, ctx.reference_grid, ctx.base, ctx.products,
                      observing=ctx.observing, values=values, peer_exprs=ctx.peer_exprs)
        ev["precheck"] = pre
        return ev

    async def _implement(cand: dict[str, Any], system: str, feedback: str | None, written: dict[str, int],
                         locked: int | None) -> tuple[Expr | None, str | None]:
        """输出本身有毛病（不是 JSON、表达式不合法、和这条假设评估过的某一版一模一样）就带着毛病再要，feedback 一直带着。
        locked 给了就用它当方向，不看模型这次填的。写不出来返回 (None, 最后一次的毛病)"""
        problem = None
        for _ in range(MAX_PARSE_RETRIES + 1):
            cand["attempts"] = cand.get("attempts", 0) + 1
            obj = await ask_json(ctx.gen, system, prompts.implement_message(cand, feedback, ctx.policy, problem),
                                  prompts.parse_object)
            text = (obj or {}).get("expr")
            if not text:
                problem = "没有给出 JSON 里的 expr 字段"
                continue
            try:
                expr = parse(str(text), FEATURE_COLUMNS)
            except DslError as exc:
                problem = f"表达式不合法：{exc}"
                continue
            if str(expr) in written:
                problem = f"{expr} 和这条假设的第 {written[str(expr)]} 版一模一样，已经评估过了，换一种写法"
                continue
            direction = locked if locked is not None else parse_direction(obj.get("direction"))
            if direction is None:
                problem = "direction 只能是 1 或 -1"
                continue
            cand["note"] = str(obj.get("note") or "")
            cand["direction"] = direction
            return expr, None
        return None, problem

    async def _review(cand: dict[str, Any], lang: str) -> dict[str, Any]:
        summary = prompts.eval_brief(cand["eval"], cand["gates"])
        obj = await ask_json(ctx.review, prompts.system_review(lang, ctx.policy), prompts.review_message(cand, summary),
                              prompts.parse_object)
        verdict = str((obj or {}).get("verdict") or "").strip().lower()
        if verdict not in ("pass", "revise", "reject"):
            return {"verdict": "reject", "comment": "评审没有给出合法裁决", "suggestion": ""}
        return {"verdict": verdict, "comment": str(obj.get("comment") or ""), "suggestion": str(obj.get("suggestion") or "")}

    async def summarize(state: RunState) -> dict[str, Any]:
        run_id = state["run_id"]
        await emit(ctx.store, run_id, "status", {"stage": "summarizing"})
        candidates = [dict(c) for c in state.get("candidates", [])]
        evaluated = [c for c in candidates if c.get("gates")]
        wrappers = [{"gates": c["gates"]} for c in evaluated]
        # 门没过重写掉的、被评审打回改掉的旧版本也各算一次检验：进 BH 的 m，也进试验数
        superseded = sum(c["evaluations"] - 1 for c in evaluated)
        apply_fdr(wrappers, ctx.thresholds.fdr_q, ctx.params.iterations, superseded)
        for c, w in zip(evaluated, wrappers):
            c["gates"] = clean(w["gates"])
            if c["status"] == "passed" and not w["passed"]:
                c["status"] = "rejected_fdr"
                c["reason"] = "同批 BH-FDR 没扛住"
            await _save(ctx.store, run_id, c)
        total = await ctx.store.bump_trials(len(evaluated) + superseded)
        survivors = [c for c in candidates if c["status"] == "passed"]
        full = {c["id"]: ctx.full[c["id"]] for c in survivors}
        kept = await asyncio.to_thread(screen_batch, survivors, full, ctx.pool, ctx.base, ctx.params, ctx.thresholds)
        for c in survivors:
            if c["status"] != "passed":
                await _save(ctx.store, run_id, c)
                await emit(ctx.store, run_id, "log", {"text": f"{c['name']}：{c['reason'][3:]}，拒掉"})
                continue
            # holdout 只给留下的算，拒掉的碰都不碰
            held = await asyncio.to_thread(recheck, ctx.pool.rows, full[c["id"]], ctx.pool.cut_ms, ctx.params)
            c["eval"] = {**c["eval"], "holdout": clean(held)}
            await _save(ctx.store, run_id, c)
            main = held["horizons"][ctx.params.primary]
            await emit(ctx.store, run_id, "log", {
                "text": f"{c['name']}：holdout 主档截面 IC {fmt(main['mean'])}，单调性 {fmt(main['monotonic'])}，"
                        f"{main['samples']} 个时刻"})
        await emit(ctx.store, run_id, "log", {
            "text": f"本轮评估 {len(evaluated)} 条，过门 {len(kept)} 条；累计试验 {total} 次"})
        return {"candidates": candidates}

    async def reflect(state: RunState) -> dict[str, Any]:
        """生成模型看这一轮全部候选写几条经验，存表给之后几轮出题用；没有候选就不复盘"""
        run_id = state["run_id"]
        candidates = state.get("candidates", [])
        if not candidates:
            return {}
        await emit(ctx.store, run_id, "status", {"stage": "reflecting"})
        message = prompts.reflect_message(candidates, await ctx.store.lessons(), state["lang"])
        try:
            items = await ask_json(ctx.gen, prompts.system_reflect(state["lang"]), message, prompts.parse_list)
        except Exception as exc:
            # 复盘只给之后几轮出题用，模型出错不能连累这一轮的幸存者
            logger.exception("run %s 复盘失败", run_id)
            await emit(ctx.store, run_id, "log", {"text": f"复盘时模型出错，这一轮不写经验：{str(exc)[:200]}"})
            return {}
        lessons, dropped = [], 0
        for item in items or []:
            if not isinstance(item, dict):
                continue
            kind, text = str(item.get("kind") or "").strip().lower(), str(item.get("text") or "").strip()
            if kind not in LESSON_KINDS or not text:
                continue
            if prompts.lesson_fits(text, state["lang"]):
                lessons.append({"kind": kind, "text": text})
            else:
                dropped += 1
        if dropped:
            await emit(ctx.store, run_id, "log", {"text": f"复盘有 {dropped} 条超长或带着表达式，丢掉"})
        if not lessons:
            await emit(ctx.store, run_id, "log", {"text": "复盘没给出合法的经验，这一轮不存"})
            return {}
        lessons = lessons[:MAX_LESSONS]
        await ctx.store.add_lessons(run_id, state.get("campaign_id"), lessons)
        await emit(ctx.store, run_id, "lessons", {"items": lessons})
        return {}

    async def commit(state: RunState) -> dict[str, Any]:
        """过门、过评审、过同批 BH 与 G8/G9 复核的进观察区等人入库；每条都记进 tried"""
        run_id = state["run_id"]
        await emit(ctx.store, run_id, "status", {"stage": "committing"})
        candidates = [dict(c) for c in state.get("candidates", [])]
        observing = 0
        for c in candidates:
            ctx.full.pop(c["id"], None)
            if c["status"] == "passed":
                c["status"] = "observing"
                observing += 1
            await ctx.store.add_tried(run_id, c["hypothesis"], c.get("expr"), c["status"], c.get("reason"))
            await _save(ctx.store, run_id, c)
        await ctx.store.set_run_status(run_id, "done")
        await emit(ctx.store, run_id, "done", {"observing": observing})
        return {"candidates": candidates}

    g = StateGraph(RunState)
    g.add_node("propose", propose)
    g.add_node("candidate", candidate)
    g.add_node("summarize", summarize)
    g.add_node("reflect", reflect)
    g.add_node("commit", commit)
    g.add_edge(START, "propose")
    g.add_conditional_edges("propose", fan_out, ["candidate", "summarize"])
    g.add_edge("candidate", "summarize")
    g.add_edge("summarize", "reflect")
    g.add_edge("reflect", "commit")
    g.add_edge("commit", END)
    return g.compile()


# ========== 辅助 ==========


def screen_batch(cands: list[dict[str, Any]], full: dict[str, np.ndarray], pool: Pool, base: Base,
                 params: EvalParams, thresholds: Thresholds) -> list[dict[str, Any]]:
    """同批过了门的按 G9 判的那份增量（交易方案 recent_from 起的净 Sharpe）从大到小排队，就地改：规范形和前面留下的重合按 G8 拒，
    和前面留下的截面相关到了 G6 阈值按 G6 拒；留下的再对 base 加前面留下的算一次增量，写进 G9 备注给人看（不是门）。
    full 是每条在全量骨架上的值，按 id。返回留下的"""
    cands.sort(key=lambda c: -c["eval"]["gain"]["recentSharpe"]["gain"])
    kept, batch, canon, out = base, Combo.empty(pool.search, pool.symbols), {}, []
    for c in cands:
        if c["canonical"] in canon:
            c["gates"] = [clean(origin_gate(c["canonical"], canon)) if g["id"] == "G8" else g for g in c["gates"]]
            c["status"], c["reason"] = "rejected_gates", f"G8 规范形与同批 {canon[c['canonical']]} 重合"
            continue
        f = full[c["id"]][pool.in_search]
        twin, corr = max(cross_corr(pool.search, f, batch, params), key=lambda x: x[1], default=(None, 0.0))
        if corr >= thresholds.max_redundancy:
            note = f"与同批 {twin} 的截面 |ρ| = {fmt(corr)}"
            c["gates"] = [{**g, "passed": False, "note": f"{g['note']}；{note}"} if g["id"] == "G6" else g
                          for g in c["gates"]]
            c["status"], c["reason"] = "rejected_gates", f"G6 {note}"
            continue
        if out:
            again = gain(kept, c["name"], f, params)
            note = (f"；排在同批 {'、'.join(x['name'] for x in out)} 之后 {day_name(again['recentFrom'])} 起净 Sharpe 增量 "
                    f"{fmt(again['recentSharpe']['gain'], 2)}")
            c["gates"] = [{**g, "note": g["note"] + note} if g["id"] == "G9" else g for g in c["gates"]]
        kept, batch = kept.plus(c["name"], f, params), batch.plus(c["name"], f)
        canon[c["canonical"]] = c["name"]
        out.append(c)
    return out


def parse_direction(value: Any) -> int | None:
    """模型声明的方向：1 是因子值越大未来越强，-1 是越弱；字符串、浮点也认"""
    try:
        d = int(float(value))
    except (TypeError, ValueError):
        return None
    return d if d in (1, -1) else None


def brief(c: dict[str, Any]) -> dict[str, Any]:
    """页面用的候选摘要：不带整份 eval"""
    out = {k: c.get(k) for k in ("id", "seq", "name", "hypothesis", "family", "expr", "note", "direction", "status",
                                  "attempts", "reason", "review")}
    if c.get("gates"):
        out["gates"] = [gate_row(g) for g in c["gates"]]
    if c.get("eval"):
        out["brief"] = prompts.eval_brief(c["eval"], c["gates"])
    return out


async def emit(store: Store, run_id: str, event: str, data: Any) -> None:
    """先落库拿到 id 再推流，订阅端按 id 去重；不在流式上下文里时只落库"""
    data = clean(data)
    event_id = await store.add_event(run_id, event, data)
    try:
        get_stream_writer()({"id": event_id, "event": event, "data": data})
    except RuntimeError:
        pass


async def _save(store: Store, run_id: str, cand: dict[str, Any]) -> None:
    await store.upsert_candidate(cand)
    await emit(store, run_id, "candidate", brief(cand))


async def ask_json(model: BaseChatModel, system: str, message: str, parser):
    """问一次；不是合法 JSON 就带着原话再要一次"""
    messages = [SystemMessage(system), HumanMessage(message)]
    reply = await model.ainvoke(messages)
    obj = parser(reply.text)
    if obj is not None:
        return obj
    retry = messages + [reply, HumanMessage(prompts.json_retry())]
    return parser((await model.ainvoke(retry)).text)
