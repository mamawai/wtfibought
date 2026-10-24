"""币池长表：截面评估的数据入口。

装载时按 universe 表与每币面板的在池掩码，建好基础网格（6h）上每个在池 (时刻, 币) 一行的骨架 rows，
每行带三档答案（持有 h 根的涨跌，持有期里下架的按下架前最后一根结算）和持有期内要结算的资金费（从 raw/funding 的结算记录算，多空腿扣它）；
挖掘、交易方案都按晚 DELAY 根成交（信号那根收盘后再过一根建仓，资金费从建仓起算）；
一次评估按表达式用到的列逐币读 parquet、求值、只留骨架那几行，拼成与 rows 对齐的因子值，不把全池面板同时留在内存。
截面算子要同一时刻全部在池币的值：先逐币算出它的输入、只留在池的根拼成长表按时刻算好，再逐币算外层。
"""

from __future__ import annotations

import ctypes
import sys
from collections.abc import Iterable
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from .. import ops
from ..dsl import Call, Cross, Expr
from .binance import BAR_MS, DAY_MS
from .panel import DELAY, HORIZON_BARS, POOL_COLUMN, PanelStore

# 基础网格 6h 一个点；12h、24h 的网格是它的子集
BASE_BARS = min(HORIZON_BARS.values())
# 流动性档：当月排名 1~20 一档、21~50 二档、其余三档
TIER_RANKS = (20, 50)
_EMPTY = pd.Series(dtype="float64")
# 只有 Linux 的 glibc 有 malloc_trim；本机 Windows 跑测试时不用
_LIBC = ctypes.CDLL("libc.so.6") if sys.platform == "linux" else None


def release_memory() -> None:
    """把 glibc 内存池里空着的页还给系统：全池求值在线程里反复分配大数组，不还的话空闲时常驻内存也越攒越多"""
    if _LIBC is not None:
        _LIBC.malloc_trim(0)


def on_grid(t: np.ndarray, bars: int) -> np.ndarray:
    """close_time 是边界减 1 ms；边界落在 bars 根的整数倍上就是这一档的网格点（UTC 0 点起数）"""
    return ((np.asarray(t, dtype="int64") + 1) // BAR_MS) % bars == 0


def month_of(t: np.ndarray) -> np.ndarray:
    """收盘时刻所在的 UTC 月，1970-01 起数的整数，与面板 in_pool 的月份同口径"""
    return np.asarray(t, dtype="int64").astype("datetime64[ms]").astype("datetime64[M]").astype("int64")


def month_label(m: int) -> str:
    return str(np.datetime64(int(m), "M"))


def tier_of(rank: np.ndarray) -> np.ndarray:
    return np.where(rank <= TIER_RANKS[0], 1, np.where(rank <= TIER_RANKS[1], 2, 3)).astype("int8")


def forward_funding(t: np.ndarray, funding: pd.DataFrame | None, bars: int) -> np.ndarray:
    """t 收盘建仓、持有 bars 根，期间结算的费率之和：结算时刻落在 (t, t + bars 根]。
    正数是多头付、空头收；结算间隔 8h、4h、1h 都按实际记录数；没有记录的币是 0"""
    if funding is None or funding.empty:
        return np.zeros(len(t))
    times = funding["funding_time"].to_numpy(dtype="int64")
    total = np.concatenate(([0.0], np.cumsum(funding["funding_rate"].to_numpy(dtype="float64"))))
    lo = np.searchsorted(times, t, side="right")
    hi = np.searchsorted(times, t + bars * BAR_MS, side="right")
    return total[hi] - total[lo]


def forward_return(t: np.ndarray, close: np.ndarray, at: np.ndarray, bars: int, delay: int = 0) -> np.ndarray:
    """at 选中的那几根出信号、再过 delay 根收盘建仓、持有 bars 根的对数涨跌，建仓、平仓都取那一刻及之前最后一根的收盘价。
    平常就是第 delay 根、第 delay + bars 根；持有期里断了的（下架，或下架后隔几个月重新上）按断之前最后一根结算，不把这一期扔掉"""
    entry = np.searchsorted(t, t[at] + delay * BAR_MS, side="right") - 1
    last = np.searchsorted(t, t[at] + (delay + bars) * BAR_MS, side="right") - 1
    return np.log(close[last] / close[entry])


@dataclass(frozen=True)
class Values:
    """一条表达式在骨架上的值：f 与 rows 对齐；cross 是截面节点在每币在池时刻的值（前视自检和时序报告要）"""
    f: np.ndarray
    cross: dict[Call, dict[str, pd.Series]] = field(default_factory=dict)

    def at(self, symbol: str) -> Cross:
        return cross_at(self.cross, symbol)


@dataclass
class Pool:
    store: PanelStore
    # universe 里出现过、面板还在的币；rows 里的 sym 是这里的序号
    symbols: tuple[str, ...]
    # 全量骨架：t、sym、month、tier、y24/48/96、fund24/48/96（持有期内结算的费率之和），按币再按时间排；
    # 持有期够到全部数据最后一根之后的（还没发生）两样都是 NaN
    rows: pd.DataFrame
    cut_ms: int
    # rows 里属于搜索段（cut 及之前）的行
    in_search: np.ndarray
    # 搜索段骨架：标准答案和资金费够到 cut 之后的记 NaN，与 truncate 同口径
    search: pd.DataFrame
    # 每个币第一根 K 线的收盘时刻（按 symbols 的序号）；数据起点就在的币记 0。重新上市的币按第一次算
    listed: np.ndarray

    @classmethod
    def load(cls, store: PanelStore, universe: pd.DataFrame, cut_ms: int, delay: int = DELAY) -> "Pool":
        """universe 列 month（YYYY-MM）、symbol、rank；delay 是晚几根成交：信号那根收盘后再过 delay 根才建仓，资金费从建仓起算"""
        ranks = universe.assign(m=np.array(universe["month"], dtype="datetime64[M]").astype("int64"))
        by_symbol = {s: dict(zip(g["m"], tier_of(g["rank"].to_numpy()))) for s, g in ranks.groupby("symbol")}
        symbols = tuple(s for s in sorted(by_symbol) if store.has(s))
        parts = []
        first = []
        # 全部数据的最后一根：还在交易的币都停在这里，比它早断的是下架了
        end = 0
        for code, s in enumerate(symbols):
            df = store.load(s, [POOL_COLUMN, "close"])
            keep = _keep(df)
            every = df.index.to_numpy(dtype="int64")
            end = max(end, int(every.max(initial=0)))
            first.append(int(every.min()))
            close = df["close"].to_numpy(dtype="float64")
            t = every[keep]
            m = month_of(t)
            part = {"t": t, "sym": np.full(len(t), code, dtype="int32"), "month": m,
                    "tier": pd.Series(m).map(by_symbol[s]).to_numpy(dtype="int8")}
            funding = store.load_raw("funding", s)
            for bars in HORIZON_BARS.values():
                part[f"y{bars}"] = forward_return(every, close, keep, bars, delay)
                part[f"fund{bars}"] = forward_funding(t + delay * BAR_MS, funding, bars)
            parts.append(pd.DataFrame(part))
        rows = pd.concat(parts, ignore_index=True)
        _blank_after(rows, end, delay)
        in_search = rows["t"].to_numpy() <= cut_ms
        search = rows[in_search].reset_index(drop=True)
        _blank_after(search, cut_ms, delay)
        # 数据起点（最早那根）后两天内就有数的币算起点就在，不知道它什么时候上的
        first_arr = np.array(first, dtype="int64")
        listed = np.where(first_arr <= first_arr.min() + 2 * DAY_MS, 0, first_arr)
        return cls(store, symbols, rows, cut_ms, in_search, search, listed)

    def age_days(self, rows: pd.DataFrame) -> np.ndarray:
        """每行那一刻这个币上市多少天"""
        return (rows["t"].to_numpy(dtype="int64") - self.listed[rows["sym"].to_numpy(dtype="int64")]) / DAY_MS

    def frame(self, symbol: str, columns: list[str]) -> pd.DataFrame:
        return self.store.load(symbol, [*columns, POOL_COLUMN])

    def values(self, expr: Expr) -> Values:
        """全量数据上求值，按 rows 的顺序出因子值；搜索段取 f[in_search]"""
        columns = sorted(expr.columns())
        cross: dict[Call, dict[str, pd.Series]] = {}
        for node in expr.cross_nodes():
            cross[node] = cross_values(node, ((s, self.frame(s, columns)) for s in self.symbols), cross)
        parts = []
        for s in self.symbols:
            df = self.frame(s, columns)
            parts.append(expr.evaluate(df, cross_at(cross, s)).to_numpy(dtype="float64")[_keep(df)])
        out = Values(np.concatenate(parts), cross)
        release_memory()
        return out

    def describe(self) -> dict[str, int]:
        t = self.rows["t"].to_numpy()
        return {"symbols": len(self.symbols), "months": int(self.rows["month"].nunique()),
                "gridPoints": int(len(np.unique(t))), "rows": int(len(self.rows))}


def cross_values(node: Call, frames: Iterable[tuple[str, pd.DataFrame]],
                 inner: dict[Call, dict[str, pd.Series]]) -> dict[str, pd.Series]:
    """截面节点：每币（面板带在池掩码）算出输入、只留在池的根，拼成长表按时刻算，再拆回每币；inner 是里层已算好的截面节点"""
    child = Expr(node.args[0])
    symbols, times, values = [], [], []
    for s, df in frames:
        inside = df[POOL_COLUMN].to_numpy() == 1.0
        symbols.append(s)
        times.append(df.index.to_numpy(dtype="int64")[inside])
        values.append(child.evaluate(df, cross_at(inner, s)).to_numpy(dtype="float64")[inside])
    out = ops.OPERATORS[node.name].fn(pd.Series(np.concatenate(values)), np.concatenate(times)).to_numpy()
    ends = np.cumsum([len(t) for t in times])
    return {s: pd.Series(out[end - len(t):end], index=t) for s, t, end in zip(symbols, times, ends)}


def cross_at(cross: dict[Call, dict[str, pd.Series]], symbol: str) -> Cross:
    """一个币的截面节点值；没进过池的币是空的，求值出来全 NaN"""
    return {node: by.get(symbol, _EMPTY) for node, by in cross.items()}


def _blank_after(rows: pd.DataFrame, until: int, delay: int) -> None:
    """持有期（晚 delay 根建仓）够到 until 之后的答案和资金费记 NaN：那时候还不知道"""
    for bars in HORIZON_BARS.values():
        late = rows["t"].to_numpy() + (delay + bars) * BAR_MS > until
        rows.loc[late, [f"y{bars}", f"fund{bars}"]] = np.nan


def _keep(df: pd.DataFrame) -> np.ndarray:
    """骨架的行：在池且落在基础网格上"""
    return (df[POOL_COLUMN].to_numpy() == 1.0) & on_grid(df.index.to_numpy(dtype="int64"), BASE_BARS)
