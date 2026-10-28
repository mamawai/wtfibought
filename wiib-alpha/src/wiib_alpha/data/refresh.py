"""刷新：列符号 → 日线排池子 → 每个币依次拉 15m、费率、基差、metrics、现货 15m 并装配面板 → 参照网格。

跑在线程里，不碰 SQLite：池子表交回主循环写，进度写进传进来的 dict 给页面看。
每个数据集按币一个 parquet 断点续传，中途死掉重跑只补缺的；一个币装配完就释放，不把全池装进内存。
面板、status.json、参照网格写进新的一版目录，全部写完才换成当前版；中途失败当前那版原封不动。
"""

from __future__ import annotations

import logging
import time
from collections import deque
from collections.abc import Callable, Collection, Iterator, Sequence
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Any

import pandas as pd

from .. import reference
from . import liquidation, universe
from .binance import DAY_MS, Binance, empty_klines
from .panel import BENCHMARK, PanelStore, assemble, describe
from .spot import spot_symbol

logger = logging.getLogger(__name__)

# 费率或基差覆盖不到九成的币（归档缺文件、中文名币没基差）报给页面看；照常在池，缺的那几段是空值
MIN_COVERAGE = 0.9
# 同时拉几个币的数据：归档是静态文件，等网络的时间和装配面板叠起来；fapi 那边 Binance 自己排队限速
PULL_WORKERS = 4


@dataclass
class RefreshResult:
    universe: pd.DataFrame
    status: dict[str, dict[str, Any]]


def refresh(store: PanelStore, binance: Binance, *, history_from: int, product: tuple[str, ...], universe_size: int,
            min_volume: float, exclude: Collection[str], progress: dict[str, Any],
            conninfo: dict[str, Any] | None = None, tz: str = "UTC", now_ms: int | None = None) -> RefreshResult:
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    _stage(progress, "symbols", 0)
    info = binance.exchange_info()
    candidates = [s for s in binance.symbols() if not universe.excluded(s, info.get(s), exclude)]
    # 写进进度给页面看：underlyingType 的取值用来核对排除规则有没有漏
    progress["underlyingTypes"] = sorted({str(v.get("underlyingType")) for v in info.values()})
    progress["candidates"] = len(candidates)
    # 现货归档里出现过的对，含已下架的
    spot_archive = set(binance.spot_symbols())

    # 排池子要上市满 90 天，日线从起点再往前拉 90 天
    _stage(progress, "daily", len(candidates))
    daily = {}
    pull_daily = lambda s: store.grow("klines_1d", s, lambda sym, a, b: binance.klines(sym, a, b, "1d"),
                                      history_from - universe.MIN_LISTED_DAYS * DAY_MS, now_ms)
    for s, df in _prefetched(pull_daily, candidates):
        progress["symbol"] = s
        daily[s] = df
        progress["done"] += 1
    table = universe.rank(daily, universe.month_starts(history_from, now_ms), universe_size, min_volume)
    pool = universe.months_by_symbol(table)
    members = sorted(set(pool) | set(product) | {BENCHMARK})
    progress["members"] = len(members)
    # 每个币对应的现货对；对不上的三列留空，名单给页面看
    spot = {s: spot_symbol(s, spot_archive) for s in members}
    progress["spotMissing"] = sorted(s for s in members if spot[s] is None)

    liq = liquidation.load(conninfo, product, tz) if conninfo else None
    liq_start = None if liq is None or liq.empty else int(liq["close_time"].min())
    outage = liquidation.outage_buckets(liq) if liq is not None else frozenset()

    # BTC 先拉，别的币对齐基准要用；还在 exchangeInfo 里的币费率尾巴才问 fapi
    _stage(progress, "panel", len(members))
    out = store.next_version()
    progress["symbol"] = BENCHMARK
    btc = _pull(store, binance, BENCHMARK, history_from, now_ms, BENCHMARK in info, spot[BENCHMARK])
    status: dict[str, dict[str, Any]] = {}
    thin: dict[str, str] = {}
    progress["thin"] = thin
    pull = lambda s: btc if s == BENCHMARK else _pull(store, binance, s, history_from, now_ms, s in info, spot[s])
    for s, raw in _prefetched(pull, members):
        progress["symbol"] = s
        sym_liq = liq[liq["symbol"] == s] if liq is not None and s in product else None
        panel = assemble(raw["klines"], raw["funding"], None if s == BENCHMARK else btc["klines"], sym_liq, liq_start,
                         outage, raw["premium"], raw["metrics"], pool.get(s, frozenset()), raw["spot"])
        desc = describe(panel)
        desc["spotSymbol"] = spot[s]
        # 覆盖低也不剔：数据后来断掉常常正是这个币出事的时候，剔掉等于拿将来的事删当年的样本
        low = incomplete(desc)
        if low:
            thin[s] = low
        out.save_panel(s, panel)
        status[s] = desc
        logger.info("panel %s：%s", s, desc)
        progress["done"] += 1
    out.write_status(status)
    # 参照网格：池子里的币各读一遍，参照因子在主档网格上的截面秩，写在 status.json 之后（比它新才算数）
    pooled = sorted(set(table["symbol"]))
    _stage(progress, "reference", len(pooled))
    out.save_reference_grid(reference.grid(out, pooled, progress))
    out.publish()
    progress["symbol"] = None
    return RefreshResult(table, status)


def incomplete(desc: dict[str, Any]) -> str | None:
    """费率或基差覆盖不到 MIN_COVERAGE 的说明哪列多少，够的 None"""
    low = {c: v for c, v in desc["coverage"].items() if c in ("funding_rate", "premium") and v < MIN_COVERAGE}
    return "、".join(f"{c} {v:.2f}" for c, v in low.items()) or None


def _prefetched(fn: Callable[[str], Any], symbols: Sequence[str]) -> Iterator[tuple[str, Any]]:
    """按 symbols 的顺序逐个给出 (币, fn(币))；后面最多 PULL_WORKERS 个在线程里先拉着，拉完没取走的不会越攒越多"""
    with ThreadPoolExecutor(PULL_WORKERS) as pool:
        queue: deque = deque()
        for s in symbols:
            queue.append((s, pool.submit(fn, s)))
            if len(queue) > PULL_WORKERS:
                head, future = queue.popleft()
                yield head, future.result()
        while queue:
            head, future = queue.popleft()
            yield head, future.result()


def _pull(store: PanelStore, binance: Binance, symbol: str, earliest: int, now_ms: int, live: bool,
          spot: str | None) -> dict[str, pd.DataFrame]:
    """spot 是这个币对应的现货对，没有就不拉、不建 raw 文件"""
    return {
        "klines": store.grow("klines_15m", symbol, binance.klines, earliest, now_ms),
        "funding": store.grow("funding", symbol, lambda s, a, b: binance.funding(s, a, b, live), earliest, now_ms),
        "premium": store.grow("premium_15m", symbol, binance.premium, earliest, now_ms),
        "metrics": store.grow("metrics", symbol, binance.metrics, earliest, now_ms),
        "spot": (store.grow("spot_15m", symbol, lambda _, a, b: binance.spot_klines(spot, a, b), earliest, now_ms)
                 if spot else empty_klines()),
    }


def _stage(progress: dict[str, Any], stage: str, total: int) -> None:
    progress.update(stage=stage, total=total, done=0, symbol=None)
