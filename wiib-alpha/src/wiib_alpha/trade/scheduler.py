"""交易员的调度：alpha 进程里的后台循环，约每分钟醒一次。

整点记一次账户净值；每天 UTC 00:05 ~ 01:00 之间，今天还没调过、数据已装载、没在刷新，就调一次仓：
00:05 起补尾巴、算目标，等到 00:15（晚一根）再下单。01:00 之后还没调成就跳过这一天，不拿 0 点的信号隔几小时再下。
一次调仓：ensure-account → 当月币池登记给 sim（feed 按交易员集合订行情）→ 补 live 尾巴 → 拼面板出目标 → 等到 00:15 → 读账户和标记价 → 下单 → 补拉 00:15 那根收盘价 → 再读账户
→ 用同一批面板算因子看板（库里全部在役因子对产品币的看法，算不出来不影响调仓）；
跑完不管成败都落一条 trade_run；一笔单都没成的（Binance、sim 一时不通，或者下了单全失败），01:00 之前下一分钟再来，成了一笔的当天不再跑；
进程在半路退出就不落，重启后同一天重跑（下单 ID 不变，sim 去重）。
刷新期间不跑；调仓、因子对比跑着的时候刷新接口拒绝（main 里那串互斥）。
月初自动刷新数据：每月 1 日 UTC 14 点起，当月币池还没排出来（这个月还没刷成过）就起一次，换上当月币池；
有 campaign 或别的后台任务占着就下一分钟再看，刷失败了隔一小时再起，不分白天晚上。
按腿归因（attrib）：调仓时段之外，出过目标、下一次调仓已经发生、还没归因的调仓日，拉价格和资金费算一遍存进 trade_run。
组合权重和方向（全部历史、24h 档、晚一根成交的答案学的）按装载时刻缓存，骨架就是挖掘装载的那份：库因子的值取自 main 的库缓存，不每天全池重算。
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable, Mapping, Sequence
from typing import Any

import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException

from ..data import universe as universe_table
from ..data.binance import BAR_MS, DAY_MS, Binance, day_name, ms, utc
from ..data.panel import BENCHMARK, FEATURE_COLUMNS, PanelStore
from ..data.pool import month_label, month_of
from ..dsl import Expr, parse
from ..eval.params import EvalParams
from ..store import Store, now_ms
from . import attrib, executor, live, scheme, sizing, targets

logger = logging.getLogger(__name__)

TICK_S = 60
HOUR_MS = 3_600_000
# UTC 0 点那根收盘出信号：00:05 起补尾巴、算目标，00:15（晚一根）下单，01:00 之后还没调成就跳过这一天
PREPARE_AFTER_MS = 5 * 60_000
TRADE_AT_MS = 15 * 60_000
GIVE_UP_AFTER_MS = 60 * 60_000
# 月初自动刷新从 1 日 UTC 14 点起：上个月最后一天的日度归档 8~12 点才出齐，再早排当月币池的 30 天日线缺一天
REFRESH_AFTER_MS = 14 * HOUR_MS

# main._library：库因子在当前数据上的派生物（带全量骨架上的值）
Library = Callable[[FastAPI], Awaitable[Any]]
# main._start_refresh：起数据刷新，在刷新或有别的任务占着抛 409
Refresh = Callable[[FastAPI], Awaitable[None]]


async def loop(app: FastAPI, library: Library, refresh: Refresh) -> None:
    """lifespan 里起，进程退出时取消"""
    while True:
        try:
            await tick(app, library, refresh, now_ms())
        except Exception:
            logger.exception("交易员调度出错")
        await asyncio.sleep(TICK_S)


async def tick(app: FastAPI, library: Library, refresh: Refresh, now: int) -> None:
    store: Store = app.state.store
    if (await store.last_equity_ts() or 0) // HOUR_MS < now // HOUR_MS:
        await snapshot(app, now)
    trade_window = PREPARE_AFTER_MS <= now % DAY_MS < GIVE_UP_AFTER_MS
    if (trade_window and app.state.data is not None and not app.state.runs.jobs["refresh"].get("running")
            and _due(await store.trade_run(day_name(now)))):
        await rebalance(app, library, now)
    start = utc(now).replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    month = f"{start:%Y-%m}"
    # 当月币池还没排出来就起刷新；上一次（点的或自动的）起了不到一小时不再起，失败的隔一小时再试
    if (now - ms(start) >= REFRESH_AFTER_MS and now - app.state.runs.jobs["refresh"].get("startedAt", 0) >= HOUR_MS
            and not await store.has_universe(month)):
        try:
            await refresh(app)
            logger.info("%s 的币池还没排出来，自动刷新数据", month)
        except HTTPException:
            # 还在刷、有 campaign 或别的后台任务占着：下一分钟再看
            pass
    if not trade_window:
        # 和补 live 尾巴错开：两边都走 fapi、排着队发
        await attribute(app)


def _due(run: Mapping[str, Any] | None) -> bool:
    """今天还没调过，或者调了但一笔单都没成"""
    return run is None or (run["status"] != "done" and not _filled(run["orders"]))


def _filled(orders: Sequence[Mapping[str, Any]]) -> bool:
    """有没有成交的单：调杠杆那几行不算"""
    return any(o["ok"] for o in orders if o["action"] != "leverage")


def account(sim: executor.Sim, user_id: int) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    return sim.balance(user_id), sim.positions(user_id)


async def snapshot(app: FastAPI, now: int) -> None:
    """记一次账户净值和多空名义；还没调过仓（没建账户）不记，sim 不通下一分钟再试"""
    store: Store = app.state.store
    user_id = await store.trade_user()
    if user_id is None:
        return
    try:
        balance, positions = await asyncio.to_thread(account, app.state.sim, user_id)
    except executor.SimError as exc:
        logger.warning("记交易员净值失败：%s", exc)
        return
    side = lambda s: sum(float(p["positionValue"]) for p in positions if p["side"] == s)
    await store.add_trade_equity(now, executor.equity(balance, positions), float(balance["balance"]), side("LONG"),
                                 side("SHORT"))


async def rebalance(app: FastAPI, library: Library, now: int) -> None:
    """调一次仓，结果落 trade_run；出错也落，记下错误"""
    store: Store = app.state.store
    job = app.state.runs.jobs["trader"]
    day = day_name(now)
    job.clear()
    job.update(running=True, startedAt=now_ms(), day=day, stage="prepare", total=0, done=0, symbol=None, error=None,
               finishedAt=None)
    # 出信号那根：UTC 0 点收盘的那根 15m
    record: dict[str, Any] = {"day": day, "signal_ms": now - now % DAY_MS - 1, "started_at": job["startedAt"],
                              "orders": []}
    try:
        data = app.state.data
        table = pd.DataFrame(await store.universe(), columns=universe_table.COLUMNS)
        # 币池按信号那根收盘时刻所在的月，与回测的在池掩码同口径
        month = month_label(month_of(np.array([record["signal_ms"]]))[0])
        pool = sorted(table.loc[table["month"] == month, "symbol"])
        if not pool:
            raise targets.TradeError(f"币池表还没有 {month} 的榜单，先刷新数据")
        lib = await library(app)
        chosen = await store.trade_factor_ids()
        cols = [j for j, f in enumerate(lib.factors) if f["id"] in chosen]
        weights = await combo_weights(app, lib, cols)
        record["factors"] = [{"id": lib.factors[j]["id"], "name": lib.factors[j]["name"], "expr": lib.factors[j]["expr"],
                              "weight": float(w)} for j, w in zip(cols, weights)]
        board = await board_factors(app, lib)
        held = await store.last_trade_legs() or {}
        setting = await store.carry()
        scale = await store.sizing()
        listed = {s: int(v) for s, v in zip(data.pool.symbols, data.pool.listed)}
        exprs = [parse(lib.factors[j]["expr"], FEATURE_COLUMNS) for j in cols]
        await asyncio.to_thread(_rebalance, app.state.fapi, app.state.sim, data.pool.store, pool,
                                universe_table.months_by_symbol(table), exprs, weights, setting["enabled"], setting["share"],
                                scale, listed, held, app.state.standard.params, now, record, job, board,
                                list(data.products))
    except Exception as exc:
        logger.exception("调仓失败")
        record["error"] = str(exc)[:500]
    record["finished_at"] = now_ms()
    record["status"] = _status(record)
    await store.add_trade_run(record)
    job.update(running=False, stage=None, symbol=None, finishedAt=record["finished_at"], error=record.get("error"))


def _rebalance(binance: Binance, sim: executor.Sim, store: PanelStore, pool: Sequence[str],
               months: Mapping[str, frozenset[str]], factors: Sequence[Expr], weights: np.ndarray, carry: bool,
               carry_share: float, scale: Mapping[str, Any], listed: Mapping[str, int], held: Mapping[str, Any],
               params: EvalParams, now: int,
               record: dict[str, Any], job: dict[str, Any], board: Sequence[Mapping[str, Any]],
               products: Sequence[str]) -> None:
    """在线程里跑；做到哪一步就往 record 里记到哪一步，中途出错外面照样落库。下完单再用同一批面板算看板"""
    signal_ms = record["signal_ms"]
    job["stage"] = "account"
    user_id = record["user_id"] = sim.ensure_account()
    info = binance.exchange_info()
    # 下架、停牌的币 fapi 拉不到，不出目标
    trading = [s for s in pool if info.get(s, {}).get("status") == "TRADING"]
    # 可能下单的币先登记给 sim，feed 据此订上行情：补 live 尾巴要七八分钟，下单时都订上了（重试也一样）；要平掉的币 sim 按仓位自己算进集合
    sim.watch(trading)
    job["stage"] = "live"
    live.update(store, binance, sorted({*trading, BENCHMARK}), now, job)
    job["stage"] = "targets"
    panels = live.window(live.loader(store, signal_ms), trading, months, signal_ms)
    out = targets.compute(panels, factors, weights, held, signal_ms, params, carry, listed, carry_share)
    job["stage"] = "waiting"
    time.sleep(max(0.0, (signal_ms + 1 + TRADE_AT_MS - now_ms()) / 1000))
    # 01:00 前开始的重试，补数据、算目标拖过了 01:00 的，这一天也跳过
    if now_ms() - (signal_ms + 1) >= GIVE_UP_AFTER_MS:
        raise targets.TradeError("过了 01:00 还没开始下单，这一天跳过")
    job["stage"] = "orders"
    balance, positions = account(sim, user_id)
    equity = record["equity_before"] = executor.equity(balance, positions)
    prices = binance.mark_prices()
    net = {s: c["weight"] for s, c in out["coins"].items()}
    size = sizing.size(net, sim.brackets(sorted(net)) if net else {}, scale["leverage"], scale["margin"], equity)
    orders, coins = executor.plan(net, positions, prices, executor.rules(info), equity, size.multiple, record["day"])
    for row in coins:
        row.update({k: out["coins"].get(row["symbol"], {}).get(k, 0.0) for k in targets.SIGNS})
    # sizing 是当天实际下的规模，setting 是页面上的设定
    record["targets"] = {"coins": coins, "signals": {name: leg["coins"] for name, leg in out["legs"].items()},
                         "notTrading": [s for s in pool if s not in trading], "carry": carry, "carryShare": carry_share,
                         "sizing": {**size.view(), "setting": {"leverage": scale["leverage"], "margin": scale["margin"]}}}
    # 开始下单才记这一期的多空集合：下一期的缓冲按它算
    record["legs"] = {name: {"long": leg["long"], "short": leg["short"]} for name, leg in out["legs"].items()}
    executor.execute(sim, user_id, orders, positions, size.leverage, record["orders"])
    # 00:15 那根的收盘价：回测按它成交，下单时它刚收盘，下完单再拉来对成交价
    refs = live.closes(binance, [row["symbol"] for row in coins], signal_ms + BAR_MS)
    for row in coins:
        row["refPrice"] = refs.get(row["symbol"])
    balance, positions = account(sim, user_id)
    record["equity_after"] = executor.equity(balance, positions)
    job["stage"] = "board"
    try:
        record["board"] = targets.board(panels, board, products, signal_ms, params)
    except Exception:
        # 看板只是展示：算不出来不算这次调仓出错
        logger.exception("算因子看板失败")


async def attribute(app: FastAPI) -> None:
    """出过目标、下一次调仓已经发生、还没归因的调仓日，按腿归因存进 trade_run；一天一条，算过不再算"""
    store: Store = app.state.store
    days = await store.trade_days()
    pending = [(a, b) for a, b in zip(days, days[1:]) if a["has_targets"] and not a["has_attrib"]]
    if not pending:
        return
    universe = await store.universe()
    for a, b in pending:
        run = await store.trade_run(a["day"])
        prev = await store.prev_targets(a["day"])
        closes, funding = await asyncio.to_thread(attrib.inputs, app.state.fapi, run, b)
        out = attrib.attribute(run, b, prev, closes, funding, attrib.tiers(universe, run["signal_ms"]),
                               app.state.standard.params)
        await store.set_trade_attrib(a["day"], out)
        logger.info("%s 按腿归因：实际 %+.2f、回测应得 %+.2f（组合腿 %+.2f、carry 腿 %+.2f）", a["day"], out["actual"],
                    out["model"], out["legs"]["combo"]["net"], out["legs"]["carry"]["net"])


def _status(record: Mapping[str, Any]) -> str:
    """出错停在半路的：有成交的算 partial，没有的 failed；跑完的有币失败算 partial"""
    orders = record["orders"]
    if record.get("error"):
        return "partial" if _filled(orders) else "failed"
    coins = (record.get("targets") or {}).get("coins", [])
    return "partial" if any(not o["ok"] for o in orders) or any(c["failed"] for c in coins) else "done"


# ========== 缓存：组合权重，数据重新装载整个作废 ==========


def _cache(app: FastAPI) -> dict[str, Any]:
    cache = app.state.trade_cache
    if cache.get("loadedAt") != app.state.data.loaded_at:
        cache.clear()
        cache.update(loadedAt=app.state.data.loaded_at, weights={}, signs={})
    return cache


async def _per_factor(app: FastAPI, lib: Any, cols: Sequence[int], kind: str, learn: Callable[[list[np.ndarray]], np.ndarray]
                      ) -> np.ndarray:
    """lib.full 里这几列因子各自学的一个数：只看它自己，按因子 id 缓存在 kind 下；一条都没有是空的"""
    if not cols:
        return np.empty(0)
    cache = _cache(app)[kind]
    for j in cols:
        fid = lib.factors[j]["id"]
        if fid not in cache:
            cache[fid] = float((await asyncio.to_thread(learn, [lib.full.values[:, j]]))[0])
    return np.array([cache[lib.factors[j]["id"]] for j in cols])


async def combo_weights(app: FastAPI, lib: Any, cols: Sequence[int]) -> np.ndarray:
    """这几列因子的组合权重（scheme.learned_weights）"""
    data = app.state.data
    return await _per_factor(app, lib, cols, "weights",
                             lambda v: scheme.learned_weights(data.pool, v, data.vol, app.state.standard.params))


async def combo_signs(app: FastAPI, lib: Any, cols: Sequence[int]) -> np.ndarray:
    """这几列因子的方向（scheme.learned_signs），看板用"""
    data = app.state.data
    return await _per_factor(app, lib, cols, "signs",
                             lambda v: scheme.learned_signs(data.pool, v, app.state.standard.params))


async def board_factors(app: FastAPI, lib: Any) -> list[dict[str, Any]]:
    """看板用：库里全部在役因子按入库先后编号，带方向、组合权重，和 live 尾巴补不补得出它用到的列"""
    cols = list(range(len(lib.factors)))[::-1]
    signs, weights = await combo_signs(app, lib, cols), await combo_weights(app, lib, cols)
    return [{"no": no, "id": lib.factors[j]["id"], "name": lib.factors[j]["name"], "expr": lib.factors[j]["expr"],
             "sign": float(s), "weight": float(w), "live": set(lib.factors[j]["columns"]) <= live.COLUMNS}
            for no, (j, s, w) in enumerate(zip(cols, signs, weights), 1)]
