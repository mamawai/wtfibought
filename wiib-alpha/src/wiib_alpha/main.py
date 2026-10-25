"""FastAPI：鉴权、数据、campaign 与轮次、SSE、观察区、因子库、参照集、手填评估、因子交易员。

图跑在服务端后台任务里，不绑定 HTTP 连接：关页面不会杀掉一轮。一轮跑完由驱动器查 campaign 的停止条件，没到就起下一轮。
事件先落库再推给在线订阅者，刷新页从库里回放。
公开的只有数据状态、参照集和概览数字；候选、事件流、因子库的内容和全部 POST 只有管理员。返回体与 Java Result 同形，401 走 HTTP 200 + code 401。
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from collections import Counter
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from collections.abc import Mapping
from typing import Any, AsyncIterator

import numpy as np
import pandas as pd
from fastapi import APIRouter, Depends, FastAPI, HTTPException, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from starlette.types import Scope

from . import prompts, reference
from .auth import Auth, AuthError, Authenticator, Forbidden, UpstreamError
from .config import Config, ModelSpec, config
from .data import universe as universe_table
from .data.binance import Binance
from .data.panel import FEATURE_COLUMNS, PanelStore, describe, holdout_cut, truncate
from .data.pool import Pool
from .data.refresh import RefreshResult, refresh
from .dsl import DslError, Expr, parse
from .eval import combo
from .eval.evaluate import cross_corr, evaluate
from .eval.gates import fmt, judge, passed
from .eval.params import EvalParams, Thresholds
from .eval.recheck import recheck
from .graph import Context, ask_json, brief, build_graph, parse_direction, screen_batch
from .llm import LlmError, build_chat_model
from .prompts import eval_brief, holdout_brief, horizons_brief
from .reference import Grid
from .store import Store, clean, now_ms
from .trade import attrib, executor, live, realtime, scheduler, scheme

logger = logging.getLogger(__name__)
# 包里的 info 日志（刷新进度、fapi 已用权重、调仓）打到容器日志；根 logger 没有 handler
_handler = logging.StreamHandler()
_handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
logging.getLogger("wiib_alpha").addHandler(_handler)
logging.getLogger("wiib_alpha").setLevel(logging.INFO)

MAX_K = 10
MAX_ROUNDS = 50
QUEUE_LIMIT = 5000
_SSE_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}
_PING_SECONDS = 15.0
# 管理前端的构建产物（镜像里是 /app/web/dist），挂在 /alpha
WEB_DIST = Path(__file__).resolve().parents[2] / "web" / "dist"


# ========== 数据上下文 ==========


@dataclass
class Data:
    # 截面评估的币池：池子里的币留在磁盘上按列读；评估、门只看 pool.search（cut 之前）
    pool: Pool
    # 产品币搜索段面板，只给时序报告
    products: dict[str, pd.DataFrame]
    cut_ms: int
    # 参照网格对到 pool.search 上；没算好（缺文件或比面板旧，后台在补）是 None，G6 不过
    reference_grid: Grid | None
    reference_canonicals: set[str]
    facts: dict[str, Any]
    # 产品币面板的 describe，装载时算一次；数据状态接口每 3 秒被轮询，不能每次现算
    status: dict[str, dict[str, Any]]
    loaded_at: int
    # 全量骨架上的 Std($ret, 672) 和 basis_7d：多空腿按波动分仓、交易方案的 carry 腿用
    vol: np.ndarray
    basis: np.ndarray
    # 全量骨架上的 $funding_rate：挖掘的预检看因子值两头的当期资金费
    funding: np.ndarray


def load_data(cfg: Config, params: EvalParams, universe: list[dict[str, Any]]) -> Data | None:
    """读产品币面板切出搜索段、建币池骨架、读参照网格；产品币面板或池子面板没有就是 None（先刷新数据）。
    universe 是 SQLite 里的池子表，主循环读好传进来（线程里不碰 aiosqlite）"""
    store = PanelStore(cfg.data_dir)
    panels = {s: store.load(s) for s in cfg.symbols if store.has(s)}
    table = pd.DataFrame(universe, columns=universe_table.COLUMNS)
    if not panels or not any(store.has(s) for s in set(table["symbol"])):
        return None
    cut_ms = holdout_cut(panels, params.holdout_days)
    described = {s: describe(p) for s, p in panels.items()}
    pool = Pool.load(store, table, cut_ms)
    grid = reference.load_grid(store)
    return Data(
        pool=pool,
        products={s: truncate(p, cut_ms) for s, p in panels.items()},
        cut_ms=cut_ms,
        reference_grid=reference.align(grid, pool) if grid is not None else None,
        reference_canonicals={e.canonical() for e in reference.parsed().values()},
        facts=_facts(described, cut_ms, params, len(pool.symbols), cfg.universe_size),
        status=described,
        loaded_at=now_ms(),
        vol=pool.values(scheme.VOL).f,
        basis=scheme.carry_signal(pool),
        funding=pool.values(parse("$funding_rate", FEATURE_COLUMNS)).f,
    )


@dataclass
class Library:
    """库里在役因子在当前数据上的派生物：已知规范形、G9 的基线（在役因子 + 基差 carry 的交易方案，
    组合也是 G6 的库因子对比集）、组合报告；退役的不在任何一项里"""
    factors: list[dict[str, Any]]
    known: set[str]
    base: scheme.Base
    # 组合在搜索段、holdout、最后一个网格时刻上的表现；库空时 None
    report: dict[str, Any] | None
    # 全量骨架上的值，列与 factors 同序：交易员学组合权重、因子对比用
    full: combo.Combo
    # lib:名字 → 表达式，冗余报最像的几个时带上
    exprs: dict[str, str]


def build_library(data: Data, factors: list[dict[str, Any]], params: EvalParams, carry_share: float) -> Library:
    """在役的每条因子全池求值一遍（全量骨架），搜索段的组合从里面切；carry_share 是 G9 基线里 carry 腿的份额"""
    pool = data.pool
    full = combo.Combo.empty(pool.rows, pool.symbols)
    for f in factors:
        full = full.plus(f["name"], pool.values(parse(f["expr"], FEATURE_COLUMNS)).f)
    search = combo.Combo(pool.search, pool.symbols, full.names, full.values[pool.in_search])
    vol = data.vol[pool.in_search]
    report = None
    if factors:
        report = {
            "brief": {"primary": prompts.hours(params.primary), "recentFrom": _day(params.recent_from),
                      "horizons": horizons_brief(combo.evaluate(search, vol, params)),
                      "holdout": holdout_brief(combo.holdout(full, data.vol, data.cut_ms, params))},
            **combo.latest(full, data.vol, params),
        }
    known = data.reference_canonicals | {f["canonical"] for f in factors}
    base = scheme.Base.of(search, data.basis[pool.in_search], vol, params, carry_share)
    return Library(factors, known, base, report, full, {f"lib:{f['name']}": f["expr"] for f in factors})


def _facts(described: dict[str, dict[str, Any]], cut_ms: int, params: EvalParams, pool_symbols: int,
           pool_size: int) -> dict[str, Any]:
    """给提示词和页面的数据事实；symbols 是产品币"""
    liq = [d["liqFrom"] for d in described.values() if d["liqFrom"]]
    return {
        "symbols": list(described),
        "from": _day(min(d["from"] for d in described.values())),
        "to": _day(max(d["to"] for d in described.values())),
        "rows": min(d["rows"] for d in described.values()),
        "liqFrom": _day(min(liq)) if liq else None,
        "holdoutFrom": _day(cut_ms),
        "costTiers": list(params.cost_tiers_bps),
        "poolSize": pool_size,
        "poolSymbols": pool_symbols,
    }


def _day(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d")


# 两个角色：gen 提假设写表达式，review 评审；协议分叉见 llm.py
ROLES = ("gen", "review")
PROTOCOLS = ("openai", "responses", "anthropic", "gemini")


def build_models(specs: Mapping[str, ModelSpec]) -> tuple[Any, Any]:
    """两个角色都配了才建"""
    if any(role not in specs for role in ROLES):
        raise LlmError("先在挖掘页配好生成和评审两个角色的模型")
    return build_chat_model(specs["gen"], "gen"), build_chat_model(specs["review"], "review")


async def _model_specs(app: FastAPI) -> dict[str, ModelSpec]:
    return {role: ModelSpec(protocol=r["protocol"], base_url=r["base_url"], model=r["model"], api_key=r["api_key"],
                            effort=r["effort"]) for role, r in (await app.state.store.models()).items()}


# ========== 后台任务与订阅 ==========


class Runs:
    """在跑的任务与在线订阅者"""

    def __init__(self) -> None:
        self.tasks: dict[str, asyncio.Task] = {}
        self.subscribers: dict[str, set[asyncio.Queue]] = {}
        # grid 是启动时补算参照网格（刷新收尾那次算在 refresh 的进度里）；reeval 是观察区复评，libraryReeval 是库里因子复评；
        # trader 是交易员调仓，compare 是交易员的因子对比，eval 是手填评估
        self.jobs: dict[str, dict[str, Any]] = {"refresh": {"running": False}, "reference": {"running": False},
                                                "grid": {"running": False}, "reeval": {"running": False},
                                                "libraryReeval": {"running": False},
                                                "trader": {"running": False}, "compare": {"running": False},
                                                "eval": {"running": False}}

    def subscribe(self, run_id: str) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=QUEUE_LIMIT)
        self.subscribers.setdefault(run_id, set()).add(queue)
        return queue

    def unsubscribe(self, run_id: str, queue: asyncio.Queue) -> None:
        queues = self.subscribers.get(run_id)
        if queues is None:
            return
        queues.discard(queue)
        if not queues:
            del self.subscribers[run_id]

    def publish(self, run_id: str, event: dict[str, Any]) -> None:
        """订阅端读得太慢队列满了就丢这条；事件都在库里，重连按 id 能补回来"""
        for queue in self.subscribers.get(run_id, ()):
            try:
                queue.put_nowait(event)
            except asyncio.QueueFull:
                pass


async def _publish(app: FastAPI, run_id: str, event: str, data: Any) -> None:
    """main 自己发的事件也先落库再推"""
    data = clean(data)
    event_id = await app.state.store.add_event(run_id, event, data)
    app.state.runs.publish(run_id, {"id": event_id, "event": event, "data": data})


async def _drive(app: FastAPI, run_id: str, payload: dict[str, Any]) -> None:
    """跑一轮；跑完不管成没成，交给 campaign 决定停还是起下一轮"""
    store: Store = app.state.store
    try:
        graph = await _graph(app)
        async for chunk in graph.astream(payload, stream_mode="custom"):
            app.state.runs.publish(run_id, chunk)
    except Exception as exc:
        logger.exception("run %s 失败", run_id)
        message = str(exc) if isinstance(exc, (LlmError, DslError)) else "运行失败，看服务日志"
        await store.set_run_status(run_id, "failed", str(exc)[:500])
        for c in await store.fail_pending(run_id, "这一轮没跑完"):
            await _publish(app, run_id, "candidate", brief(c))
        await _publish(app, run_id, "error", {"message": message})
    finally:
        app.state.runs.tasks.pop(run_id, None)
    try:
        await _next_round(app, payload["campaign_id"], run_id)
    except Exception as exc:
        # 后台任务没人接异常：不收尾的话 campaign 一直挂在 running，新的开不了，直到重启
        logger.exception("campaign %s 起下一轮失败", payload["campaign_id"])
        await store.update_campaign(payload["campaign_id"], status="failed", stop_reason="起下一轮时出错",
                                    error=str(exc)[:500])


async def _next_round(app: FastAPI, campaign_id: str, run_id: str) -> None:
    """一轮跑完：轮数、幸存者记上，到了停止条件就收尾，没到就起下一轮（同 k、方向、语言）"""
    store: Store = app.state.store
    campaign = await store.campaign(campaign_id)
    runs = await store.campaign_runs(campaign_id)
    done_rounds = campaign["done_rounds"] + 1
    survivors = campaign["survivors"] + (await store.candidate_counts()).get(run_id, {}).get("observing", 0)
    stop = _stop_reason(campaign, runs, done_rounds, survivors)
    if stop is None:
        await store.update_campaign(campaign_id, done_rounds=done_rounds, survivors=survivors)
        await _start_round(app, campaign)
        return
    status, reason = stop
    await store.update_campaign(campaign_id, done_rounds=done_rounds, survivors=survivors, status=status,
                                stop_reason=reason, error=runs[0]["error"] if status == "failed" else None)


def _stop_reason(campaign: dict[str, Any], runs: list[dict[str, Any]], done_rounds: int,
                 survivors: int) -> tuple[str, str] | None:
    """该停了给 (campaign 状态, 原因)，接着跑是 None；runs 最新在前，runs[0] 是刚跑完的这一轮"""
    if len(runs) >= 2 and runs[0]["status"] == runs[1]["status"] == "failed":
        return "failed", "连续两轮出错"
    if done_rounds >= campaign["rounds"]:
        return "done", f"跑满 {campaign['rounds']} 轮"
    if campaign["max_survivors"] is not None and survivors >= campaign["max_survivors"]:
        return "done", f"幸存者到了 {survivors} 条"
    if campaign["deadline_ms"] is not None and now_ms() >= campaign["deadline_ms"]:
        return "done", "到时间了"
    if campaign["status"] == "stopping":
        return "stopped", "手动停止"
    return None


async def _start_round(app: FastAPI, campaign: dict[str, Any]) -> str:
    run_id = uuid.uuid4().hex[:12]
    await app.state.store.create_run(run_id, campaign["k"], campaign["note"], campaign["lang"], campaign["id"],
                                     _data(app).cut_ms)
    payload = {"run_id": run_id, "campaign_id": campaign["id"], "lang": campaign["lang"], "k": campaign["k"],
               "note": campaign["note"]}
    app.state.runs.tasks[run_id] = asyncio.create_task(_drive(app, run_id, payload))
    return run_id


async def _graph(app: FastAPI):
    """每次现建 Context：库摘要取当下的，模型第一次建好后复用"""
    data: Data = _data(app)
    if app.state.models is None:
        app.state.models = build_models(await _model_specs(app))
    gen, review = app.state.models
    lib = await _library(app)
    library = [{"name": f["name"], "expr": f["expr"], "hypothesis": f["hypothesis"], "canonical": f["canonical"]}
               for f in lib.factors]
    # 观察区里还没入库的也算已知：前几轮留下的，下一轮换个窗口再交一遍按 G8 拒，换个写法高度相关按 G6 拒
    observing = {c["canonical"] for c in await app.state.store.observing()}
    obs, obs_exprs = await _observing(app)
    ctx = Context(gen=gen, review=review, pool=data.pool, vol=data.vol, funding=data.funding, products=data.products,
                  reference_grid=data.reference_grid, base=lib.base, observing=obs,
                  peer_exprs={**lib.exprs, **obs_exprs}, known=lib.known | observing, library=library,
                  facts={**data.facts, "carryShare": lib.base.carry_share}, store=app.state.store,
                  params=app.state.params, thresholds=app.state.thresholds, evaluating=app.state.evaluating)
    return build_graph(ctx)


def _library_key(data: Data, factors: list[dict[str, Any]], carry_share: float) -> tuple:
    """库缓存的键：数据、在役因子、carry 份额都没变才算同一份"""
    return data.loaded_at, tuple(f["id"] for f in factors), carry_share


async def _library(app: FastAPI) -> Library:
    """库、数据或 carry 份额变了才重算；同时来几个请求只算一次"""
    data: Data = _data(app)
    async with app.state.library_lock:
        factors = await app.state.store.factors()
        share = (await app.state.store.carry())["share"]
        key = _library_key(data, factors, share)
        cache = app.state.library
        if cache is None or cache[0] != key:
            cache = (key, await asyncio.to_thread(build_library, data, factors, app.state.params, share))
            app.state.library = cache
    return cache[1]


def _library_if_ready(app: FastAPI, factors: list[dict[str, Any]], carry_share: float) -> Library | None:
    """现成的库；库变了还没算完就起后台算、先给 None：列表接口不能卡着等几分钟"""
    cache = app.state.library
    if cache is not None and cache[0] == _library_key(_data(app), factors, carry_share):
        return cache[1]
    task = app.state.library_task
    if task is None or task.done():
        app.state.library_task = asyncio.create_task(_warm(app))
    return None


async def _observing(app: FastAPI) -> tuple[combo.Combo, dict[str, str]]:
    """观察区候选在搜索段上的值（G6 的对比集）和 obs:名字 → 表达式；值按候选 id 缓存，一条第一次用到要全池求值一遍，
    数据换了整个作废"""
    data: Data = _data(app)
    rows = await app.state.store.observing()
    async with app.state.observing_lock:
        loaded_at, cache = app.state.observing
        if loaded_at != data.loaded_at:
            cache = {}
            app.state.observing = (data.loaded_at, cache)
        for c in rows:
            if c["id"] not in cache:
                values = await asyncio.to_thread(data.pool.values, parse(c["expr"], FEATURE_COLUMNS))
                cache[c["id"]] = values.f[data.pool.in_search]
    out = combo.Combo.empty(data.pool.search, data.pool.symbols)
    for c in rows:
        out = out.plus(c["name"], cache[c["id"]])
    return out, {f"obs:{c['name']}": c["expr"] for c in rows}


async def _warm(app: FastAPI) -> None:
    """装载完、入库后先把观察区的值和库算好：手填评估、campaign 第一次用到时不用再现算；
    交易员 00:05 调仓也不该现算整个库"""
    try:
        await _observing(app)
        await _library(app)
    except Exception:
        logger.exception("预热失败")


def _data(app: FastAPI) -> Data:
    if app.state.data is None:
        raise HTTPException(409, "还没有行情数据，先刷新数据")
    return app.state.data


async def _load(app: FastAPI, cfg: Config) -> None:
    """装载数据；参照网格缺了或比面板旧就后台补算，算好挂到这份 data 上（中途又刷新换了 data 也不串）"""
    universe = await app.state.store.universe()
    app.state.data = data = await asyncio.to_thread(load_data, cfg, app.state.params, universe)
    if data is not None:
        asyncio.create_task(_warm(app))
    job = app.state.runs.jobs["grid"]
    if data is None or data.reference_grid is not None or job.get("running"):
        return
    job.clear()
    job.update(running=True, startedAt=now_ms(), total=len(data.pool.symbols), done=0, error=None, finishedAt=None)

    async def work() -> None:
        try:
            data.reference_grid = await asyncio.to_thread(_build_grid, data.pool, job)
            job.update(finishedAt=now_ms())
        except Exception as exc:
            logger.exception("参照网格计算失败")
            job.update(error=str(exc)[:300])
        finally:
            job["running"] = False

    asyncio.create_task(work())


def _build_grid(pool: Pool, job: dict[str, Any]) -> Grid:
    table = reference.grid(pool.store, pool.symbols, job)
    pool.store.save_reference_grid(table)
    return reference.align(table, pool)


# ========== 鉴权 ==========


async def auth(request: Request) -> Auth:
    return await request.app.state.authenticator(request.headers.get("satoken"), request.headers.get("X-Lang"))


async def is_admin(request: Request) -> bool:
    """公开接口里个别字段只给管理员：手里的 token 是管理员才算"""
    try:
        await auth(request)
    except (AuthError, Forbidden):
        return False
    return True


# ========== 接口 ==========

# 公开的只有数据状态、参照集、概览数字和交易员的账户、每日盈亏、实时数；候选、事件流、因子库的内容、
# 交易员用的因子与调仓明细、全部 POST 只有管理员
public = APIRouter(prefix="/api/alpha")
router = APIRouter(prefix="/api/alpha", dependencies=[Depends(auth)])


def _ok(data: Any) -> dict[str, Any]:
    return {"code": 0, "msg": "", "data": clean(data)}


class CreateCampaign(BaseModel):
    rounds: int = Field(default=10, ge=1, le=MAX_ROUNDS)
    k: int = Field(default=5, ge=1, le=MAX_K)
    note: str = ""
    # 两个都可空：幸存者累计到这么多条、跑了这么多分钟，这一轮跑完就停
    maxSurvivors: int | None = Field(default=None, ge=1)
    deadlineMinutes: int | None = Field(default=None, ge=1)


class EvalBody(BaseModel):
    expr: str


class RetireBody(BaseModel):
    reason: str = Field(max_length=500)


class ReferenceRun(BaseModel):
    windows: list[int] | None = None


class ModelSave(BaseModel):
    protocol: str
    baseUrl: str = ""
    model: str
    effort: str = ""
    # 留空 = 沿用已存的 key
    apiKey: str = ""


@router.get("/me")
async def me(who: Auth = Depends(auth)):
    """页面用它判断手里的 token 是不是管理员：不是就 401/403"""
    return _ok({"admin": True, "lang": who.lang})


@public.get("/overview")
async def overview(request: Request):
    """公开概览：每轮的状态与各状态候选数、累计试验数、库里因子数、观察区条数。只有数字，没有假设、公式和过程"""
    store: Store = request.app.state.store
    counts = await store.candidate_counts()
    runs = [{"id": r["id"], "created_at": r["created_at"], "status": r["status"], "k": r["k"],
             "counts": counts.get(r["id"], {})} for r in await store.runs()]
    return _ok({"runs": runs, "trials": await store.trials(), "factors": len(await store.factors()),
                "observing": sum(c.get("observing", 0) for c in counts.values())})


@public.get("/data/status")
async def data_status(request: Request):
    app = request.app
    data: Data | None = app.state.data
    universe = await app.state.store.universe()
    sizes = Counter(row["month"] for row in universe)
    return _ok({
        "loaded": data is not None,
        "loadedAt": data.loaded_at if data else None,
        "facts": data.facts if data else None,
        "symbols": data.status if data else {},
        # 池子：每月几个币、历史上进过池的币数、每个面板的 describe（刷新时落的 json）；
        # loaded 是装进评估器的骨架（币数、月数、6h 网格点数），grid 是参照网格就绪没有与补算进度
        "pool": {"months": dict(sorted(sizes.items())), "symbols": len({row["symbol"] for row in universe}),
                 "status": PanelStore(config().data_dir).read_status(),
                 "loaded": data.pool.describe() if data else None,
                 "grid": {**app.state.runs.jobs["grid"], "ready": data is not None and data.reference_grid is not None}},
        "referenceCount": len(reference.expressions()),
        "refresh": app.state.runs.jobs["refresh"],
    })


@router.post("/data/refresh")
async def data_refresh(request: Request):
    await _start_refresh(request.app)
    return _ok({"started": True})


async def _start_refresh(app: FastAPI) -> None:
    """起刷新的后台任务：页面上点的和交易员调度月初自动起的都走这里"""
    job = app.state.runs.jobs["refresh"]
    if job.get("running"):
        raise HTTPException(409, "已经在刷新了")
    # campaign 各轮共用装载时的数据和 holdout 切点，跑着的时候不换
    if await app.state.store.active_campaign() is not None:
        raise HTTPException(409, "有 campaign 在跑，停了再刷新")
    # 后台任务可能还在读上一版面板，刷新开头要把上一版删掉
    for name, label in (("grid", "参照网格还在补算"), ("reeval", "观察区还在复评"), ("libraryReeval", "库里因子还在复评"),
                        ("reference", "参照集还在跑"), ("trader", "交易员在调仓"), ("compare", "交易员的因子对比还在算")):
        if app.state.runs.jobs[name].get("running"):
            raise HTTPException(409, f"{label}，等它算完再刷新")
    # 上一轮留下的阶段、进度、币数全清掉，页面不会先看到旧值
    job.clear()
    job.update(running=True, startedAt=now_ms(), error=None, finishedAt=None)

    async def work() -> None:
        cfg = config()
        try:
            result = await asyncio.to_thread(_refresh_panels, cfg, job)
            await app.state.store.replace_universe(
                [(r.month, r.symbol, int(r.rank), float(r.avg_quote_volume)) for r in result.universe.itertuples()])
            # 先放掉旧数据和库再装新的：两份同时在内存会顶到容器上限被 OOM 杀
            app.state.data = None
            app.state.library = None
            await _load(app, cfg)
            job.update(finishedAt=now_ms())
            if app.state.data is not None and app.state.data.reference_grid is not None:
                await _reeval_after_refresh(app, app.state.data)
        except Exception as exc:
            logger.exception("刷新数据失败")
            job.update(error=str(exc)[:300])
        finally:
            job["running"] = False

    asyncio.create_task(work())


def _not_refreshing(app: FastAPI) -> None:
    """刷新一完成就换数据和 holdout 切点：campaign 和会存结果的评估在刷新期间不起，不然存下的是旧数据算的"""
    if app.state.runs.jobs["refresh"].get("running"):
        raise HTTPException(409, "正在刷新数据，等它刷完")


def _refresh_panels(cfg: Config, progress: dict[str, Any]) -> RefreshResult:
    binance = Binance(cfg.binance_vision_url, fapi_url=cfg.binance_fapi_url, list_url=cfg.binance_vision_list_url,
                      concurrency=cfg.fetch_concurrency)
    return refresh(PanelStore(cfg.data_dir), binance, history_from=cfg.history_from, product=cfg.symbols,
                   universe_size=cfg.universe_size, min_volume=cfg.min_daily_quote_volume, exclude=cfg.exclude_symbols,
                   progress=progress, conninfo=cfg.pg_conninfo if cfg.pg_user else None, tz=cfg.force_order_tz)


@router.post("/campaigns")
async def create_campaign(body: CreateCampaign, request: Request, who: Auth = Depends(auth)):
    """建 campaign 并起第一轮"""
    app = request.app
    store: Store = app.state.store
    data = _data(app)
    # 同时只有一个 campaign、一轮：一轮已经是 K 条候选并发评估，再叠一轮内存和模型额度都扛不住
    if await store.active_campaign() is not None or app.state.runs.tasks:
        raise HTTPException(409, "有 campaign 还在跑，等它结束")
    _not_refreshing(app)
    # 参照网格没算好 G6 一律不过，白烧重写次数和试验数
    if data.reference_grid is None:
        raise HTTPException(409, "参照网格还没算好，等它算完")
    jobs = app.state.runs.jobs
    if jobs["reeval"].get("running"):
        raise HTTPException(409, "观察区还在复评，等它算完")
    if jobs["libraryReeval"].get("running"):
        raise HTTPException(409, "库里因子还在复评，等它算完")
    # 参照集和 campaign 一起跑会抢内存和 CPU，超了容器上限会被杀掉
    if jobs["reference"].get("running"):
        raise HTTPException(409, "参照集还在跑，等它算完")
    specs = await _model_specs(app)
    if app.state.models is None:
        app.state.models = build_models(specs)
    campaign_id = uuid.uuid4().hex[:12]
    deadline = now_ms() + body.deadlineMinutes * 60_000 if body.deadlineMinutes else None
    await store.create_campaign(campaign_id, body.rounds, body.k, body.note.strip(), who.lang, body.maxSurvivors,
                                deadline, specs["gen"].model, specs["review"].model)
    run_id = await _start_round(app, await store.campaign(campaign_id))
    return _ok({"id": campaign_id, "runId": run_id})


@router.get("/campaigns")
async def list_campaigns(request: Request):
    return _ok({"campaigns": await request.app.state.store.campaigns()})


@router.get("/campaigns/{campaign_id}")
async def campaign_state(campaign_id: str, request: Request):
    """campaign、它的各轮（最新在前，带各状态候选数）、它写下的经验（最新在前）"""
    store: Store = request.app.state.store
    campaign = await store.campaign(campaign_id)
    if campaign is None:
        raise HTTPException(404, "没有这个 campaign")
    counts = await store.candidate_counts()
    runs = [{**r, "counts": counts.get(r["id"], {})} for r in await store.campaign_runs(campaign_id)]
    return _ok({"campaign": campaign, "runs": runs, "lessons": await store.lessons(200, campaign_id)})


@router.post("/campaigns/{campaign_id}/stop")
async def stop_campaign(campaign_id: str, request: Request):
    """当前这一轮跑完就停"""
    store: Store = request.app.state.store
    if await store.campaign(campaign_id) is None:
        raise HTTPException(404, "没有这个 campaign")
    if not await store.claim_campaign(campaign_id, "running", "stopping"):
        raise HTTPException(409, "这个 campaign 不在跑")
    return _ok({"stopping": True})


@router.get("/runs")
async def list_runs(request: Request):
    """各轮带各状态候选数，页面按 campaign 分组"""
    store: Store = request.app.state.store
    counts = await store.candidate_counts()
    runs = [{**r, "counts": counts.get(r["id"], {})} for r in await store.runs(200)]
    return _ok({"runs": runs, "trials": await store.trials()})


@router.get("/runs/{run_id}")
async def run_state(run_id: str, request: Request):
    store: Store = request.app.state.store
    run = await store.run(run_id)
    if run is None:
        raise HTTPException(404, "没有这一轮")
    candidates = [brief(c) for c in await store.candidates(run_id)]
    return _ok({"run": run, "candidates": candidates, "trials": await store.trials()})


@router.get("/observation")
async def observation(request: Request):
    """观察区：过了全部门、评审、同批 BH 的候选，等人入库或丢弃；created_at 是它那一轮开始的时间。
    复评过的带 reeval（当前评估器的结果），admission 是现在能不能入库和原因，reeval 任务的进度一起回"""
    app = request.app
    rows = await app.state.store.observing()
    cut = app.state.data.cut_ms if app.state.data is not None else None
    share = (await app.state.store.carry())["share"]
    items = []
    for c in rows:
        ok, why = admission(c, cut, share, app.state.thresholds)
        items.append({**brief(c), "run_id": c["run_id"], "created_at": c["run_created_at"], "updated_at": c["updated_at"],
                      "reeval": _reeval_view(c["reeval"]), "admission": {"ok": ok, "reason": why}})
    return _ok({"items": items, "reeval": app.state.runs.jobs["reeval"]})


def admission(c: dict[str, Any], cut_ms: int | None, carry_share: float, t: Thresholds) -> tuple[bool, str]:
    """入库条件，不留人工 override：在当前这版数据、当前 carry 份额上复评过且全过门，它没见过那段（发现那轮的切点起，复评时算的）
    主档截面 IC 和搜索段同号、至少 admit_min_points 个时刻、逐点 IC 的 |均值 / 标准误| ≥ admit_t_min。
    数据刷新过切点就变了、份额改过 G9 基线就变了，都得重新复评；没到门槛的留在观察区，之后每次刷新自动再复评一遍，攒够了自然过"""
    r = c.get("reeval")
    if cut_ms is None:
        return False, "还没装载数据"
    if r is None or r["eval"]["holdout"]["since"] != cut_ms:
        return False, "还没在当前这版数据上复评"
    if (r["eval"].get("gain") or {}).get("carryShare") != carry_share:
        return False, "份额改过，还没按现在的份额复评"
    if not r["passed"]:
        return False, f"复评没过门：{r['reason']}"
    ev = r["eval"]
    primary = prompts.primary_of(ev)
    search = {int(k): v for k, v in ev["horizons"].items()}[primary]["ic"]["mean"]
    fresh = ev.get("unseen") or ev["holdout"]
    h = {int(k): v for k, v in fresh["horizons"].items()}[primary]
    samples, stat = h["samples"], h.get("t")
    if samples < t.admit_min_points:
        return False, f"没见过那段只有 {samples} 个时刻，要 {t.admit_min_points} 个"
    if stat is None or search is None:
        return False, "没见过那段算不出主档 IC 的 t（复评是老版本算的，再复评一次）"
    if (stat > 0) != (search > 0):
        return False, f"没见过那段主档 IC 和搜索段的 {fmt(search)} 反号（t {fmt(stat, 1)}）"
    if abs(stat) < t.admit_t_min:
        return False, f"没见过那段主档 IC 的 t {fmt(abs(stat), 1)} 不到 {t.admit_t_min:g}"
    return True, f"没见过那段 {samples} 个时刻，主档 IC 同号，t {fmt(abs(stat), 1)}"


def _reeval_view(r: dict[str, Any] | None) -> dict[str, Any] | None:
    """观察区和库里因子的复评共用；库里因子没有同批（peers 空）"""
    if r is None:
        return None
    return {"at": r["at"], "direction": r["direction"], "asked": r.get("asked", False), "passed": r["passed"],
            "reason": r["reason"], "peers": r.get("peers", []),
            "gates": [{"id": g["id"], "name": g["name"], "passed": g["passed"], "note": g["note"]} for g in r["gates"]],
            "brief": eval_brief(r["eval"], r["gates"])}


@router.post("/observation/reeval")
async def observation_reeval(request: Request):
    """观察区全部候选用当前评估器重算一遍，当成一批互相查重；结果写进 reeval，原来的 eval、门、状态都不动，不计试验数。
    写表达式时还没声明方向的老候选，先只拿假设和表达式问生成模型补一个。数据刷新完也会自动跑一遍"""
    app = request.app
    data = _data(app)
    if app.state.runs.jobs["reeval"].get("running"):
        raise HTTPException(409, "已经在复评了")
    if await app.state.store.active_campaign() is not None or app.state.runs.tasks:
        raise HTTPException(409, "有 campaign 在跑，等它结束再复评")
    _not_refreshing(app)
    if data.reference_grid is None:
        raise HTTPException(409, "参照网格还没算好，等它算完再复评")
    if app.state.runs.jobs["libraryReeval"].get("running"):
        raise HTTPException(409, "库里因子还在复评，等它算完")
    rows = await app.state.store.observing()
    if not rows:
        raise HTTPException(409, "观察区是空的")
    return _ok({"started": True, "total": await _launch_reeval(app, data, rows)})


async def _launch_reeval(app: FastAPI, data: Data, rows: list[dict[str, Any]]) -> int:
    """起观察区复评的后台任务，返回条数；老候选要补方向时先把模型建好"""
    if any(c["direction"] is None for c in rows) and app.state.models is None:
        app.state.models = build_models(await _model_specs(app))
    job = app.state.runs.jobs["reeval"]
    job.clear()
    job.update(running=True, startedAt=now_ms(), total=len(rows), done=0, current=None, error=None, finishedAt=None)

    async def work() -> None:
        try:
            await _reeval(app, data, rows, job)
            job.update(finishedAt=now_ms())
        except Exception as exc:
            logger.exception("观察区复评失败")
            job.update(error=str(exc)[:300])
        finally:
            job["running"] = False

    asyncio.create_task(work())
    return len(rows)


async def _reeval_after_refresh(app: FastAPI, data: Data) -> None:
    """刷新完数据切点变了，观察区的复评全部过期：自动再跑一遍，入库条件里"没见过那段"也跟着变长"""
    rows = await app.state.store.observing()
    if not rows:
        return
    try:
        await _launch_reeval(app, data, rows)
    except LlmError as exc:
        # 老候选要补方向但模型没配：留给人配好后手动点
        logger.warning("刷新后观察区没能自动复评：%s", exc)


async def _reeval(app: FastAPI, data: Data, rows: list[dict[str, Any]], job: dict[str, Any]) -> None:
    """逐条评估判门（G6 比参照与库，G10 用声明的方向），过门的按同批规则互相查重；再算两两的截面相关给人看。
    G5 只按单条的排列分位判：同批 BH 是发现那一轮做过的，这里换一批人再做没有意义"""
    params, thresholds = app.state.params, app.state.thresholds
    lib = await _library(app)
    items, full = [], {}
    for c in rows:
        job["current"] = c["name"]
        asked = c["direction"] is None
        direction = await _declare(app, data, c, c["run_lang"]) if asked else c["direction"]
        expr = parse(c["expr"], FEATURE_COLUMNS)
        values = await asyncio.to_thread(data.pool.values, expr)
        ev = await asyncio.to_thread(evaluate, expr, data.pool, data.vol, params, data.reference_grid, lib.base,
                                     data.products, True, values=values, peer_exprs=lib.exprs)
        gates = judge(ev, thresholds, lib.known, direction)
        full[c["id"]] = values.f
        # 发现它的那一轮切点比现在早：从那个切点到现在都是它没见过的，比当前 holdout 长
        seen = c["run_cut_ms"]
        if seen is not None and seen < data.cut_ms:
            ev["unseen"] = await asyncio.to_thread(recheck, data.pool.rows, full[c["id"]], seen, params)
        failed = [g for g in gates if not g["passed"]]
        items.append({"id": c["id"], "name": c["name"], "canonical": c["canonical"], "direction": direction,
                      "asked": asked, "eval": clean(ev), "gates": clean(gates),
                      "status": "rejected_gates" if failed else "passed",
                      "reason": "；".join(f"{g['id']} {g['note']}" for g in failed) or None})
        job["done"] += 1
    job["current"] = None
    await asyncio.to_thread(screen_batch, [x for x in items if x["status"] == "passed"], full, data.pool, lib.base,
                            params, thresholds)
    everyone = combo.Combo.empty(data.pool.search, data.pool.symbols)
    for x in items:
        everyone = everyone.plus(x["name"], full[x["id"]][data.pool.in_search])
    for j, x in enumerate(items):
        others = combo.Combo(everyone.rows, everyone.symbols, everyone.names[:j] + everyone.names[j + 1:],
                             np.delete(everyone.values, j, axis=1))
        x["peers"] = await asyncio.to_thread(cross_corr, data.pool.search, full[x["id"]][data.pool.in_search],
                                             others, params)
        await app.state.store.set_reeval(x["id"], {
            "at": now_ms(), "direction": x["direction"], "asked": x["asked"], "passed": x["status"] == "passed",
            "reason": x["reason"], "eval": x["eval"], "gates": x["gates"], "peers": x["peers"]})


async def _declare(app: FastAPI, data: Data, c: dict[str, Any], lang: str) -> int:
    """老候选补声明方向：只给假设、表达式和公式说明，不给任何数字"""
    gen = app.state.models[0]
    facts = {**data.facts, "carryShare": (await app.state.store.carry())["share"]}
    obj = await ask_json(gen, prompts.system_gen(facts, lang), prompts.direction_message(c), prompts.parse_object)
    direction = parse_direction((obj or {}).get("direction"))
    if direction is None:
        raise LlmError(f"{c['name']}：生成模型没给出合法的方向")
    return direction


@router.post("/candidates/{candidate_id}/approve")
async def approve(candidate_id: str, request: Request):
    """观察区入库，得满足 admission。名字和库里的撞了就加序号；库的组合按因子 id 做缓存键，入库后下次用到自己重算。
    库里存的是复评那份数字和门（当前评估器算的）"""
    app = request.app
    store: Store = app.state.store
    c = await store.candidate(candidate_id)
    if c is None:
        raise HTTPException(404, "没有这个候选")
    ok, why = admission(c, _data(app).cut_ms, (await store.carry())["share"], app.state.thresholds)
    if not ok:
        raise HTTPException(409, f"还不能入库：{why}")
    # 检查与置位一条 UPDATE：入库、丢弃同时点，只有一个能抢到
    if not await store.claim_candidate(candidate_id, "observing", "approved"):
        raise HTTPException(409, "这条不在观察区")
    name = _unique_name(c["name"], {f["name"] for f in [*await store.factors(), *await store.retired_factors()]})
    latest = c["reeval"]
    await store.add_factor({
        "id": c["id"], "name": name, "hypothesis": c["hypothesis"], "expr": c["expr"], "canonical": c["canonical"],
        "columns": latest["eval"]["columns"], "eval": latest["eval"], "gates": latest["gates"], "review": c.get("review"),
        "approved_at": now_ms(), "run_id": c["run_id"],
    })
    await store.add_tried(c["run_id"], c["hypothesis"], c["expr"], "approved", None)
    asyncio.create_task(_warm(app))
    return _ok({"id": c["id"], "name": name})


@router.post("/candidates/{candidate_id}/decline")
async def decline(candidate_id: str, request: Request):
    store: Store = request.app.state.store
    if await store.candidate(candidate_id) is None:
        raise HTTPException(404, "没有这个候选")
    if not await store.claim_candidate(candidate_id, "observing", "declined"):
        raise HTTPException(409, "这条不在观察区")
    return _ok({"id": candidate_id})


def _unique_name(name: str, taken: set[str]) -> str:
    """库里的因子名不重复：撞了依次试 _2、_3……"""
    out, n = name, 1
    while out in taken:
        n += 1
        out = f"{name}_{n}"
    return out


@router.get("/runs/{run_id}/events")
async def run_events(run_id: str, request: Request, after: int = 0):
    if await request.app.state.store.run(run_id) is None:
        raise HTTPException(404, "没有这一轮")
    return StreamingResponse(_events(request.app, run_id, after), media_type="text/event-stream",
                             headers=_SSE_HEADERS)


async def _events(app: FastAPI, run_id: str, after: int) -> AsyncIterator[str]:
    """先回放库里 id > after 的，再收在线的；运行不在 running 状态时补齐收尾并结束"""
    store: Store = app.state.store
    runs: Runs = app.state.runs
    queue = runs.subscribe(run_id)
    last = after
    try:
        for e in await store.events(run_id, after):
            yield _frame(e)
            last = e["id"]
        while True:
            run = await store.run(run_id)
            if run["status"] != "running" and queue.empty():
                # 等后台任务退出再读一次状态
                task = runs.tasks.get(run_id)
                if task is not None:
                    await asyncio.wait({task})
                    run = await store.run(run_id)
                for e in await store.events(run_id, last):
                    yield _frame(e)
                    last = e["id"]
                # lastId 给前端：重连时带着它，只收后面的
                yield _frame({"id": last, "event": "end", "data": {"status": run["status"], "lastId": last}})
                return
            try:
                e = await asyncio.wait_for(queue.get(), timeout=_PING_SECONDS)
            except asyncio.TimeoutError:
                yield ": ping\n\n"
                continue
            if e["id"] > last:
                yield _frame(e)
                last = e["id"]
    finally:
        runs.unsubscribe(run_id, queue)


def _frame(e: dict[str, Any]) -> str:
    return f"id: {e['id']}\nevent: {e['event']}\ndata: {json.dumps(e['data'], ensure_ascii=False)}\n\n"


@router.get("/library")
async def library(request: Request):
    """在役因子列表加组合报告，另带退役的列表；没装载数据时没有组合报告，库刚变过报告在后台算时 computing 为 true、combo 先是 null。
    reeval 是库里因子复评任务的进度"""
    app = request.app
    store: Store = app.state.store
    factors = await store.factors()
    lib = _library_if_ready(app, factors, (await store.carry())["share"]) if app.state.data is not None else None
    return _ok({"factors": [_factor_view(f, full=False) for f in factors], "combo": lib.report if lib else None,
                "computing": app.state.data is not None and lib is None,
                "retired": [_factor_view(f, full=False) for f in await store.retired_factors()],
                "reeval": app.state.runs.jobs["libraryReeval"]})


@router.get("/library/{factor_id}")
async def library_item(factor_id: str, request: Request):
    f = await request.app.state.store.factor(factor_id)
    if f is None:
        raise HTTPException(404, "没有这个因子")
    return _ok(_factor_view(f, full=True))


def _factor_view(f: dict[str, Any], full: bool) -> dict[str, Any]:
    out = {k: f.get(k) for k in ("id", "name", "hypothesis", "expr", "canonical", "columns", "approved_at",
                                  "run_id", "review", "recheck", "retired_at", "retire_reason")}
    out["brief"] = eval_brief(f["eval"], f["gates"])
    out["reeval"] = _reeval_view(f.get("reeval"))
    if full:
        out["eval"] = f["eval"]
        out["gates"] = f["gates"]
    return out


@router.post("/library/{factor_id}/retire")
async def library_retire(factor_id: str, body: RetireBody, request: Request):
    """退役：从库里移出，不再进组合和交易员，不再当 G6 的库对比、G8 的已知和 G9 的基线，出题的"库里已有"里也没有它；
    tried 里记一条"retired + 原因"给出题看。记录不删，退役列表里还看得到，能恢复"""
    app = request.app
    store: Store = app.state.store
    f = await store.factor(factor_id)
    if f is None:
        raise HTTPException(404, "没有这个因子")
    reason = body.reason.strip()
    if not reason:
        raise HTTPException(400, "退役要写原因")
    if not await store.retire_factor(factor_id, reason):
        raise HTTPException(409, "已经退役了")
    await store.add_tried(f["run_id"], f["hypothesis"], f["expr"], "retired", reason)
    if app.state.data is not None:
        asyncio.create_task(_warm(app))
    return _ok({"id": factor_id})


@router.post("/library/{factor_id}/restore")
async def library_restore(factor_id: str, request: Request):
    """恢复在役；交易员的勾选不跟着恢复，tried 里那条改回入库"""
    app = request.app
    store: Store = app.state.store
    f = await store.factor(factor_id)
    if f is None:
        raise HTTPException(404, "没有这个因子")
    if not await store.restore_factor(factor_id):
        raise HTTPException(409, "这条没有退役")
    await store.add_tried(f["run_id"], f["hypothesis"], f["expr"], "approved", None)
    if app.state.data is not None:
        asyncio.create_task(_warm(app))
    return _ok({"id": factor_id})


@router.post("/library/{factor_id}/recheck")
async def library_recheck(factor_id: str, request: Request):
    app = request.app
    f = await app.state.store.factor(factor_id)
    if f is None:
        raise HTTPException(404, "没有这个因子")
    data = _data(app)
    _not_refreshing(app)
    values = await asyncio.to_thread(data.pool.values, parse(f["expr"], FEATURE_COLUMNS))
    result = await asyncio.to_thread(recheck, data.pool.rows, values.f, int(f["approved_at"]), app.state.params)
    await app.state.store.set_recheck(factor_id, result)
    return _ok(result)


@router.post("/library/reeval")
async def library_reeval(request: Request, who: Auth = Depends(auth)):
    """库里全部因子用当前评估器重算一遍：G9 的基线是拿掉它的库 + 基差 carry，G6 比参照集、库里别的因子和观察区，
    G8 的已知结构去掉它自己，G10 用它当初（候选）声明的方向，G5 只判单条的排列分位。结果写进 reeval，入库时那份和入库状态都不动。
    当初没声明过方向的（加 direction 列之前的老候选，观察区复评也没补过）先只拿假设和表达式问生成模型补一个"""
    app = request.app
    data = _data(app)
    store: Store = app.state.store
    job = app.state.runs.jobs["libraryReeval"]
    if job.get("running"):
        raise HTTPException(409, "已经在复评了")
    if await store.active_campaign() is not None or app.state.runs.tasks:
        raise HTTPException(409, "有 campaign 在跑，等它结束再复评")
    _not_refreshing(app)
    if data.reference_grid is None:
        raise HTTPException(409, "参照网格还没算好，等它算完再复评")
    if app.state.runs.jobs["reeval"].get("running"):
        raise HTTPException(409, "观察区还在复评，等它算完")
    rows = await store.factors()
    if not rows:
        raise HTTPException(409, "库是空的")
    if any([await _declared(store, f) is None for f in rows]) and app.state.models is None:
        app.state.models = build_models(await _model_specs(app))
    job.clear()
    job.update(running=True, startedAt=now_ms(), total=len(rows), done=0, current=None, error=None, finishedAt=None)

    async def work() -> None:
        try:
            # 库刚变过要先算库（几分钟），放在任务里：期间刷新照样被挡
            await _library_reeval(app, data, await _library(app), job, who.lang)
            job.update(finishedAt=now_ms())
        except Exception as exc:
            logger.exception("库里因子复评失败")
            job.update(error=str(exc)[:300])
        finally:
            job["running"] = False

    asyncio.create_task(work())
    return _ok({"started": True, "total": len(rows)})


async def _declared(store: Store, f: Mapping[str, Any]) -> int | None:
    """因子当初（候选）声明的方向：写表达式时声明的，或观察区复评时问模型补的；都没有是 None"""
    c = await store.candidate(f["id"])
    if c is None:
        return None
    return c["direction"] if c["direction"] is not None else (c["reeval"] or {}).get("direction")


async def _library_reeval(app: FastAPI, data: Data, lib: Library, job: dict[str, Any], lang: str) -> None:
    """逐条评估判门；lib.full 的列与 lib.factors 同序，拿掉第 j 列就是没有它的库"""
    params, thresholds = app.state.params, app.state.thresholds
    store: Store = app.state.store
    pool = data.pool
    observing, obs_exprs = await _observing(app)
    search = combo.Combo(pool.search, pool.symbols, lib.full.names, lib.full.values[pool.in_search])
    basis, vol = data.basis[pool.in_search], data.vol[pool.in_search]
    for j, f in enumerate(lib.factors):
        job["current"] = f["name"]
        direction = await _declared(store, f)
        asked = direction is None
        if asked:
            direction = await _declare(app, data, f, lang)
        others = combo.Combo(search.rows, search.symbols, search.names[:j] + search.names[j + 1:],
                             np.delete(search.values, j, axis=1))
        base = await asyncio.to_thread(scheme.Base.of, others, basis, vol, params, lib.base.carry_share)
        exprs = {**{k: v for k, v in lib.exprs.items() if k != f"lib:{f['name']}"}, **obs_exprs}
        ev = await asyncio.to_thread(evaluate, parse(f["expr"], FEATURE_COLUMNS), pool, data.vol, params,
                                     data.reference_grid, base, data.products, True, observing, peer_exprs=exprs)
        gates = judge(ev, thresholds, lib.known - {f["canonical"]}, direction)
        # 发现它那一轮的切点之后都是它没见过的，比当前 holdout 长
        run = await store.run(f["run_id"])
        seen = run["cut_ms"] if run else None
        if seen is not None and seen < data.cut_ms:
            ev["unseen"] = await asyncio.to_thread(recheck, pool.rows, lib.full.values[:, j], seen, params)
        failed = [g for g in gates if not g["passed"]]
        await store.set_factor_reeval(f["id"], {
            "at": now_ms(), "direction": direction, "asked": asked, "passed": not failed,
            "reason": "；".join(f"{g['id']} {g['note']}" for g in failed) or None,
            "eval": clean(ev), "gates": clean(gates)})
        job["done"] += 1
    job["current"] = None


@router.post("/eval")
async def eval_expression(body: EvalBody, request: Request):
    """手填一条表达式后台评估，进度与结果看 GET /eval；不进循环不计试验数。
    门看搜索段，全过了才算 holdout，和 campaign 一样：没过门的看不到 holdout"""
    app = request.app
    data = _data(app)
    expr = parse(body.expr, FEATURE_COLUMNS)
    job = app.state.runs.jobs["eval"]
    if job.get("running"):
        raise HTTPException(409, "上一条还在评估")
    job.clear()
    job.update(running=True, startedAt=now_ms(), expr=str(expr), stage="queued", error=None, finishedAt=None,
               result=None)

    async def work() -> None:
        try:
            # campaign 正在评估的两条占着就排队
            async with app.state.evaluating:
                job["stage"] = "evaluating"
                job["result"] = clean(await _eval_expression(app, data, expr))
            job.update(finishedAt=now_ms())
        except Exception as exc:
            logger.exception("手填评估失败")
            job.update(error=str(exc)[:300])
        finally:
            job.update(running=False, stage=None)

    asyncio.create_task(work())
    return _ok({"started": True})


@router.get("/eval")
async def eval_job(request: Request):
    """手填评估的进度与最近一次的结果"""
    return _ok(request.app.state.runs.jobs["eval"])


async def _eval_expression(app: FastAPI, data: Data, expr: Expr) -> dict[str, Any]:
    params = app.state.params
    lib = await _library(app)
    values = await asyncio.to_thread(data.pool.values, expr)
    observing, obs_exprs = await _observing(app)
    ev = await asyncio.to_thread(evaluate, expr, data.pool, data.vol, params, data.reference_grid, lib.base,
                                 data.products, False, observing, values, peer_exprs={**lib.exprs, **obs_exprs})
    gates = judge(ev, app.state.thresholds, lib.known)
    if passed(gates):
        ev["holdout"] = await asyncio.to_thread(recheck, data.pool.rows, values.f, data.pool.cut_ms, params)
    return {"expr": str(expr), "canonical": expr.canonical(), "passed": passed(gates),
            "brief": eval_brief(ev, gates), "gates": gates, "eval": ev}


@public.get("/reference")
async def reference_list(request: Request):
    rows = await request.app.state.store.reference_evals()
    return _ok({
        "job": request.app.state.runs.jobs["reference"],
        "items": [{"name": r["name"], "expr": r["expr"], "passed": bool(r["passed"]), "updatedAt": r["updated_at"],
                   "brief": eval_brief(r["eval"], r["gates"])} for r in rows],
    })


@router.get("/models")
async def models(request: Request):
    """两个角色的模型，key 只回尾 4 位"""
    rows = await request.app.state.store.models()
    return _ok({role: _model_view(rows.get(role)) for role in ROLES})


@router.put("/models/{role}")
async def save_model(role: str, body: ModelSave, request: Request):
    """存完把建好的模型丢掉，下一轮按新配置重建"""
    app = request.app
    await app.state.store.set_model(role, await _spec_of(app, role, body))
    app.state.models = None
    return _ok(_model_view((await app.state.store.models())[role]))


@router.post("/models/{role}/test")
async def test_model(role: str, body: ModelSave, request: Request):
    """真发一句话试连通；上游的报错原样带回去给页面看"""
    chat = build_chat_model(await _spec_of(request.app, role, body), role)
    started = now_ms()
    try:
        reply = await chat.ainvoke("只回一个词：ok")
    except Exception as exc:
        raise HTTPException(400, f"{type(exc).__name__}: {str(exc)[:300]}")
    # 带思考的模型 content 是块列表，text 只取正文
    return _ok({"reply": reply.text[:200], "ms": now_ms() - started})


async def _spec_of(app: FastAPI, role: str, body: ModelSave) -> ModelSpec:
    if role not in ROLES:
        raise HTTPException(404, f"没有 {role} 这个角色")
    protocol = body.protocol.strip().lower()
    if protocol not in PROTOCOLS:
        raise HTTPException(400, f"协议 {body.protocol} 不支持，只认 {' / '.join(PROTOCOLS)}")
    if not body.model.strip():
        raise HTTPException(400, "模型名不能空")
    key = body.apiKey.strip()
    if not key:
        row = (await app.state.store.models()).get(role)
        if row is None:
            raise HTTPException(400, "第一次配要填 API key")
        key = row["api_key"]
    return ModelSpec(protocol=protocol, base_url=body.baseUrl.strip(), model=body.model.strip(), api_key=key,
                     effort=body.effort.strip())


def _model_view(row: dict[str, Any] | None) -> dict[str, Any] | None:
    if row is None:
        return None
    return {"protocol": row["protocol"], "baseUrl": row["base_url"], "model": row["model"], "effort": row["effort"],
            "apiKeyTail": row["api_key"][-4:], "updatedAt": row["updated_at"]}


@router.post("/reference/run")
async def reference_run(body: ReferenceRun, request: Request):
    """基线 + Alpha158 过一遍全部门；参照集自己不查冗余、不做 BH"""
    app = request.app
    data = _data(app)
    job = app.state.runs.jobs["reference"]
    if job.get("running"):
        raise HTTPException(409, "参照集已经在跑了")
    if await app.state.store.active_campaign() is not None or app.state.runs.tasks:
        raise HTTPException(409, "有 campaign 在跑，等它结束再跑参照集")
    _not_refreshing(app)
    exprs = reference.parsed()
    if body.windows:
        keep = {str(w) for w in body.windows}
        exprs = {k: v for k, v in exprs.items() if k in reference.BASELINES or _window_of(k) in keep | {""}}
    job.update(running=True, startedAt=now_ms(), total=len(exprs), done=0, error=None, finishedAt=None)

    async def work() -> None:
        try:
            for name, expr in exprs.items():
                ev = await asyncio.to_thread(evaluate, expr, data.pool, data.vol, app.state.params, Grid.empty(), None,
                                             data.products)
                gates = judge(ev, app.state.thresholds, set())
                await app.state.store.set_reference_eval(name, str(expr), ev, gates, passed(gates))
                job["done"] += 1
            job.update(finishedAt=now_ms())
        except Exception as exc:
            logger.exception("参照集评估失败")
            job.update(error=str(exc)[:300])
        finally:
            job["running"] = False

    asyncio.create_task(work())
    return _ok({"started": True, "total": len(exprs)})


def _window_of(name: str) -> str:
    return name[len(name.rstrip("0123456789")):]


# ========== 交易员 ==========


class TraderFactor(BaseModel):
    selected: bool


class TraderCarry(BaseModel):
    """没给的那项不改"""
    enabled: bool | None = None
    # carry 腿占资金的比例，0 到 1
    share: float | None = Field(default=None, ge=0, le=1)


@public.get("/trader/account")
async def trader_account(request: Request):
    """账户：userId（还没调过仓是 null）、初始资金；job 是调仓任务的进度。净值、持仓这些实时数在 /trader/live"""
    app = request.app
    job = app.state.runs.jobs["trader"]
    # 出错原文可能带着因子表达式，访客拿到的不带 error
    if not await is_admin(request):
        job = {k: v for k, v in job.items() if k != "error"}
    return _ok({"userId": await app.state.store.trade_user(), "initialBalance": executor.INITIAL_BALANCE, "job": job})


@public.get("/trader/daily")
async def trader_daily(request: Request):
    """每天的盈亏，旧的在前：一天是从这天调仓前的净值到下一次调仓前的净值，含调仓手续费和期间的资金费；
    最近一次调仓之后的还没完，在 /trader/live 的 dayPnl 里。最大回撤按每小时快照算。
    attrib 是这一天按腿归因（trade/attrib.py，每币明细在调仓详情里），还没算是 null；legs 是两条腿前向累计对止损线"""
    store: Store = request.app.state.store
    starts = await store.trade_daily()
    days = [{"day": a["day"], "start": a["equity_before"], "end": b["equity_before"],
             "pnl": b["equity_before"] - a["equity_before"], "pct": b["equity_before"] / a["equity_before"] - 1,
             "attrib": {k: v for k, v in a["attrib"].items() if k != "coins"} if a["attrib"] else None}
            for a, b in zip(starts, starts[1:])]
    peak, drawdown = float(executor.INITIAL_BALANCE), 0.0
    for r in await store.trade_equity():
        peak = max(peak, r["equity"])
        drawdown = max(drawdown, 1 - r["equity"] / peak)
    return _ok({"initialBalance": executor.INITIAL_BALANCE, "maxDrawdown": drawdown, "days": days,
                "legs": attrib.summary([a["attrib"] for a in starts if a["attrib"]])})


@public.get("/trader/live")
async def trader_live(request: Request):
    """交易员页的实时数（trade/realtime.py），每 3 秒一帧"""
    return StreamingResponse(_live(request.app), media_type="text/event-stream", headers=_SSE_HEADERS)


async def _live(app: FastAPI) -> AsyncIterator[str]:
    rt: realtime.Realtime = app.state.realtime
    queue = rt.subscribe()
    try:
        while True:
            try:
                frame = await asyncio.wait_for(queue.get(), timeout=_PING_SECONDS)
            except asyncio.TimeoutError:
                yield ": ping\n\n"
                continue
            yield f"data: {json.dumps(clean(frame), ensure_ascii=False)}\n\n"
    finally:
        rt.unsubscribe(queue)


@public.get("/board")
async def factor_board(request: Request):
    """因子看板：最近一次调仓那一刻库里在役因子对产品币的看法；还没算过是 null。
    访客只看到编号、进没进组合、算没算得了和看法，名字、表达式、方向、权重只给管理员"""
    row = await request.app.state.store.latest_board()
    if row is None:
        return _ok(None)
    board = row["board"]
    if not await is_admin(request):
        board = {**board, "factors": [{"no": f["no"], "live": f["live"], "inCombo": bool(f["weight"]),
                                       "stances": f["stances"]} for f in board["factors"]]}
    else:
        board = {**board, "factors": [{**f, "inCombo": bool(f["weight"])} for f in board["factors"]]}
    return _ok({"day": row["day"], "signalMs": row["signal_ms"], **board})


@router.get("/trader/runs")
async def trader_runs(request: Request):
    """调仓记录，新的在前，只带概要；job 是调仓任务的进度"""
    app = request.app
    return _ok({"items": [_trade_brief(r) for r in await app.state.store.trade_runs()],
                "job": app.state.runs.jobs["trader"]})


@router.get("/trader/runs/{day}")
async def trader_run(day: str, request: Request):
    """一天的详情：用的因子与权重、每条腿的多空、每币目标与现有、下单与结果、按腿归因（带每币明细，还没算是 null）"""
    r = await request.app.state.store.trade_run(day)
    if r is None:
        raise HTTPException(404, "这天没有调仓记录")
    return _ok({**_trade_brief(r), "userId": r["user_id"], "factors": r["factors"] or [], "legs": r["legs"],
                "targets": r["targets"], "orders": r["orders"] or [], "attrib": r["attrib"]})


def _trade_brief(r: dict[str, Any]) -> dict[str, Any]:
    orders = r["orders"] or []
    return {"day": r["day"], "status": r["status"], "signalMs": r["signal_ms"], "startedAt": r["started_at"],
            "finishedAt": r["finished_at"], "equityBefore": r["equity_before"], "equityAfter": r["equity_after"],
            "error": r["error"], "factorNames": [f["name"] for f in r["factors"] or []], "orderCount": len(orders),
            "failedCount": sum(1 for o in orders if not o["ok"])}


@router.get("/trader/factors")
async def trader_factors(request: Request):
    """库里的因子与交易员的勾选，调仓用"勾选且还在库里"的；missing 是 live 尾巴补不出来的列，有就不能勾。
    carry 是 carry 腿的开关；compare 是因子对比任务的进度与结果"""
    app = request.app
    store: Store = app.state.store
    chosen = await store.trade_factor_ids()
    items = [{"id": f["id"], "name": f["name"], "expr": f["expr"], "selected": f["id"] in chosen,
              "missing": sorted(set(f["columns"]) - live.COLUMNS)} for f in await store.factors()]
    return _ok({"items": items, "carry": await store.carry(), "compare": app.state.runs.jobs["compare"]})


@router.put("/trader/carry")
async def trader_carry(body: TraderCarry, request: Request):
    """开或停 carry 腿、改它占资金的比例，从下一次调仓起生效；停了那份资金闲着，不挪给组合腿。
    份额也是回测和 G9 基线的份额：campaign、复评、对比跑着时不改，免得基线中途变；改完库和观察区要复评。
    改了份额库缓存整个作废，先在后台算好，别留给 00:05 的调仓现算"""
    app = request.app
    store: Store = app.state.store
    if body.share is not None:
        if await store.active_campaign() is not None or app.state.runs.tasks:
            raise HTTPException(409, "有 campaign 在跑，等它结束再改份额")
        for name, text in (("libraryReeval", "库里因子还在复评"), ("reeval", "观察区还在复评"), ("compare", "因子对比还在跑")):
            if app.state.runs.jobs[name].get("running"):
                raise HTTPException(409, f"{text}，等它算完再改份额")
    await store.set_carry(body.enabled, body.share)
    if body.share is not None and app.state.data is not None:
        asyncio.create_task(_warm(app))
    return _ok(await store.carry())


@router.put("/trader/factors/{factor_id}")
async def trader_select(factor_id: str, body: TraderFactor, request: Request):
    """勾上或取消，从下一次调仓起生效"""
    store: Store = request.app.state.store
    f = await store.factor(factor_id)
    if f is None:
        raise HTTPException(404, "没有这个因子")
    if body.selected and f["retired_at"] is not None:
        raise HTTPException(409, "已退役的因子不能勾")
    missing = sorted(set(f["columns"]) - live.COLUMNS)
    if body.selected and missing:
        raise HTTPException(409, f"用到 live 尾巴补不出来的列：{'、'.join('$' + c for c in missing)}")
    await store.set_trade_factor(factor_id, body.selected)
    return _ok({"id": factor_id, "selected": body.selected})


@router.post("/trader/compare/{factor_id}")
async def trader_compare(factor_id: str, request: Request):
    """按交易方案在搜索段比"当前勾选"和"加上 / 去掉这一条"；后者搜索段 Sharpe 更高才算两组的 holdout，免得按 holdout 挑"""
    app = request.app
    _data(app)
    job = app.state.runs.jobs["compare"]
    if job.get("running"):
        raise HTTPException(409, "已经在对比了")
    _not_refreshing(app)
    factor = await app.state.store.factor(factor_id)
    if factor is None:
        raise HTTPException(404, "没有这个因子")
    if factor["retired_at"] is not None:
        raise HTTPException(409, "已退役的因子不能拿来对比")
    job.clear()
    job.update(running=True, startedAt=now_ms(), factorId=factor_id, name=factor["name"], stage="library", error=None,
               finishedAt=None, result=None)

    async def work() -> None:
        try:
            job["result"] = clean(await _compare(app, factor_id, job))
            job.update(finishedAt=now_ms())
        except Exception as exc:
            logger.exception("因子对比失败")
            job.update(error=str(exc)[:300])
        finally:
            job.update(running=False, stage=None)

    asyncio.create_task(work())
    return _ok({"started": True})


async def _compare(app: FastAPI, factor_id: str, job: dict[str, Any]) -> dict[str, Any]:
    params: EvalParams = app.state.params
    lib = await _library(app)
    job["stage"] = "inputs"
    data = app.state.data
    pool, basis, vol = data.pool, data.basis, data.vol
    chosen = await app.state.store.trade_factor_ids()
    index = {f["id"]: j for j, f in enumerate(lib.factors)}
    base = [f["id"] for f in lib.factors if f["id"] in chosen]
    adding = factor_id not in base
    alt = [*base, factor_id] if adding else [i for i in base if i != factor_id]

    async def run(ids: list[str], holdout: bool) -> dict[str, Any]:
        values = [lib.full.values[:, index[i]] for i in ids]
        return await asyncio.to_thread(scheme.backtest, pool, values, basis, vol, params, holdout, lib.base.carry_share)

    job["stage"] = "search"
    out: dict[str, Any] = {"action": "add" if adding else "remove",
                           "base": {"factors": [lib.factors[index[i]]["name"] for i in base], "search": await run(base, False)},
                           "alt": {"factors": [lib.factors[index[i]]["name"] for i in alt], "search": await run(alt, False)}}
    sharpe = lambda side: float(np.nan_to_num(out[side]["search"]["sharpe"], nan=-np.inf))
    out["better"] = sharpe("alt") > sharpe("base")
    if out["better"]:
        job["stage"] = "holdout"
        out["base"]["holdout"] = await run(base, True)
        out["alt"]["holdout"] = await run(alt, True)
    return out


# ========== 应用 ==========


@asynccontextmanager
async def lifespan(app: FastAPI):
    cfg = config()
    Path(cfg.db_path).parent.mkdir(parents=True, exist_ok=True)
    app.state.params = getattr(app.state, "params", None) or EvalParams(cost_tiers_bps=cfg.cost_tiers_bps)
    app.state.thresholds = getattr(app.state, "thresholds", None) or Thresholds()
    app.state.models = None
    app.state.library = None
    app.state.library_lock = asyncio.Lock()
    # 库列表接口起的后台算库任务，没算完不重复起
    app.state.library_task = None
    # 观察区候选在搜索段上的值：(装载时刻, 候选 id → 值)
    app.state.observing = (None, {})
    app.state.observing_lock = asyncio.Lock()
    # campaign 各条候选和手填评估共用：同时最多两条在全池求值
    app.state.evaluating = asyncio.Semaphore(2)
    app.state.runs = Runs()
    app.state.authenticator = getattr(app.state, "authenticator", None) or Authenticator(cfg.sim_base_url)
    # 交易员用的 fapi 实时接口和 sim 内部接口
    app.state.fapi = getattr(app.state, "fapi", None) or Binance(
        cfg.binance_vision_url, fapi_url=cfg.binance_fapi_url, list_url=cfg.binance_vision_list_url)
    app.state.sim = getattr(app.state, "sim", None) or executor.Sim(cfg.sim_base_url, cfg.internal_api_token)
    # 交易员的缓存：组合权重，数据重新装载就作废
    app.state.trade_cache = {}
    app.state.realtime = realtime.Realtime(app)
    store = app.state.store = await Store.open(cfg.db_path)
    trader: asyncio.Task | None = None
    # 启动中途出错也要把 store 关掉，aiosqlite 的线程不是 daemon，不关进程退不出去
    try:
        # 上次进程死在半路的轮次没法续，明说；它所在的 campaign 也停在那
        for run in await store.running():
            await store.set_run_status(run["id"], "failed", "服务重启时中断")
            await store.fail_pending(run["id"], "服务重启时中断")
        active = await store.active_campaign()
        if active is not None:
            await store.update_campaign(active["id"], status="stopped", stop_reason="服务重启")
        await _load(app, cfg)
        # 交易员的后台循环：整点记净值，每天 UTC 00:05 起补尾巴、00:15 下单，每月 1 日 UTC 14 点起自动刷新数据
        trader = asyncio.create_task(scheduler.loop(app, _library, _start_refresh))
        yield
    finally:
        if trader is not None:
            trader.cancel()
        for task in list(app.state.runs.tasks.values()):
            task.cancel()
        await store.close()


def _error(code: int, msg: str) -> JSONResponse:
    """前端 consumeSse 见 application/json 就按接口错误处理；401 与 Java 同口径走 HTTP 200"""
    return JSONResponse({"code": code, "msg": msg}, status_code=200 if code == 401 else code)


def _unhandled(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("未处理的异常 %s %s", request.method, request.url.path)
    return _error(500, "服务内部错误")


app = FastAPI(lifespan=lifespan, title="wiib-alpha")
app.include_router(public)
app.include_router(router)
app.add_exception_handler(AuthError, lambda request, exc: _error(401, str(exc)))
app.add_exception_handler(Forbidden, lambda request, exc: _error(403, str(exc)))
app.add_exception_handler(UpstreamError, lambda request, exc: _error(502, str(exc)))
app.add_exception_handler(LlmError, lambda request, exc: _error(400, str(exc)))
app.add_exception_handler(DslError, lambda request, exc: _error(400, str(exc)))
app.add_exception_handler(HTTPException, lambda request, exc: _error(exc.status_code, exc.detail))
app.add_exception_handler(Exception, _unhandled)


class _Web(StaticFiles):
    """前端静态文件；html 带 no-cache，每次回源验证"""

    async def get_response(self, path: str, scope: Scope) -> Response:
        response = await super().get_response(path, scope)
        if response.media_type == "text/html":
            response.headers["Cache-Control"] = "no-cache"
        return response


# 没构建前端（测试、只跑后端的开发环境）就不挂
if WEB_DIST.is_dir():
    app.mount("/alpha", _Web(directory=WEB_DIST, html=True), name="web")
