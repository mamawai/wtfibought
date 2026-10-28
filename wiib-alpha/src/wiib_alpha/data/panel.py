"""一币一张表：K 线 + 资金费率 + BTC 基准 + 强平桶 + 基差 + metrics + 现货三列 + 标准答案 + 池子掩码。

index 是 bar 收盘时刻（ms），全部特征列在那一刻都已知；标准答案 fwd_ret_* 看未来，DSL 不许引用。
in_pool 是评估用的掩码，不是 DSL 列。raw/ 按数据集分目录放原始数据，都是 parquet；live/ 是交易员补的归档之后的尾巴，刷新不碰。
装配好的面板一次刷新一版目录（panel-时间戳/，最早那版就叫 panel/），panel.current 记着当前是哪版：
刷新写新的一版，全部写完才换过去；装载好的数据只读自己那版，刷新写到一半、失败了都碰不到它。
"""

from __future__ import annotations

import json
import os
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

import numpy as np
import pandas as pd

from .binance import BAR_MS, METRICS_COLUMNS

BENCHMARK = "BTCUSDT"
DAY_MS = 86_400_000
# 标准答案三档：h 小时 → 根 15m bar
HORIZON_BARS = {6: 24, 12: 48, 24: 96}
# 晚几根成交：信号那根收盘后再过这么多根才建仓，挖掘、交易方案、交易员都按它
DELAY = 1
LIQ_COLUMNS = ["liq_long", "liq_short", "liq_count"]
METRIC_FEATURES = METRICS_COLUMNS[1:]
# 结算间隔最长 8 小时，一个半周期没新结算就当断了
FUNDING_STALE_MS = 12 * 3_600_000
# $funding_rate 折成"每 8 小时的等价费率"：各币结算间隔不同（1、4、8 小时），不折算截面上一起排就乱了
FUNDING_PERIOD_MS = 8 * 3_600_000
# 下架进结算期的币归档还在出零成交的平 K 线（TROY 停牌后一年半天天有），连续一整天零成交的段整段扔
DEAD_BARS = 96
# metrics 约 5 分钟一条，一小时没新的当断了
METRICS_STALE_MS = 3_600_000
POOL_COLUMN = "in_pool"
# 现货三列 → 现货 K 线里的哪一列
_SPOT_SOURCE = {"spot_quote_volume": "quote_volume", "spot_taker_buy_quote": "taker_buy_quote", "spot_trades": "trades"}
SPOT_COLUMNS = list(_SPOT_SOURCE)
# DSL 里能用 $ 引用的列
FEATURE_COLUMNS = ["open", "high", "low", "close", "volume", "quote_volume", "trades",
                   "taker_buy_base", "taker_buy_quote", "ret", "funding_rate", "btc_close", "btc_ret",
                   *LIQ_COLUMNS, "premium", *METRIC_FEATURES, *SPOT_COLUMNS, "hour"]
TARGET_COLUMNS = [f"fwd_ret_{bars}" for bars in HORIZON_BARS.values()]
# describe 里报覆盖率的列：价格类永远是满的，只看会缺的
COVERAGE_COLUMNS = ["funding_rate", "premium", "oi", "ls_acct", "spot_quote_volume", "liq_count"]
# raw/ 下的数据集与各自的时间列
RAW_DATASETS = {"klines_1d": "open_time", "klines_15m": "close_time", "funding": "funding_time",
                "premium_15m": "close_time", "metrics": "time", "spot_15m": "close_time"}
# 参照因子在主档网格上的截面秩，刷新收尾时算
REFERENCE_GRID = "reference_grid.parquet"
# 记当前面板版本目录名的文件；没有它就是最早那版 panel/
CURRENT = "panel.current"
FIRST_VERSION = "panel"
# 版本目录的名字：只认自己建的，别的目录（手动备份之类）不删
_VERSION = re.compile(r"panel(-\d{8}T\d{12})?")


def assemble(klines: pd.DataFrame, funding: pd.DataFrame, btc_klines: pd.DataFrame | None,
             liq: pd.DataFrame | None = None, liq_start: int | None = None,
             outage: frozenset[int] = frozenset(), premium: pd.DataFrame | None = None,
             metrics: pd.DataFrame | None = None, pool_months: frozenset[str] = frozenset(),
             spot: pd.DataFrame | None = None) -> pd.DataFrame:
    """btc_klines 是基准，BTC 自己传 None 两列留空；liq 是这个币的桶；liq_start 是全部币里最早的一桶，之前没数据不是没强平；
    outage 是断档的桶 key；pool_months 是这个币在池里的月份（YYYY-MM）；spot 是对应现货对的 K 线，没有现货对传 None"""
    df = klines.drop_duplicates("close_time").set_index("close_time").sort_index()
    df = df[~_dead(df["trades"].to_numpy())]
    df["ret"] = np.log(df["close"] / df["close"].shift(1))
    # 收盘时刻最近一次已结算的费率（折成每 8 小时等价）；离上次结算太久还没新的是数据断了（月度文件只到上个整月），记 NaN 不硬填
    df["funding_rate"] = _asof(df.index, _funding_per_period(funding), "funding_time", "funding_rate")
    settled = _asof(df.index, funding, "funding_time", "funding_time")
    df.loc[df.index.to_numpy() - settled > FUNDING_STALE_MS, "funding_rate"] = np.nan
    # BTC 按收盘时刻对齐：取 ≤ 该时刻最近的一根，与 Java benchmarkReturns 同口径
    df["btc_close"] = _asof(df.index, btc_klines, "close_time", "close")
    df["btc_ret"] = np.log(df["btc_close"] / df["btc_close"].shift(1))
    # 上一根不是正好 15 分钟前（停牌、下架再上）的地方，跨缺口的涨跌幅不算
    df.loc[np.concatenate(([True], ~_spaced(df.index, 1)[:-1])), ["ret", "btc_ret"]] = np.nan
    _liq_columns(df, liq, liq_start, outage)
    # 基差 K 线与 K 线同一套收盘时刻，精确对
    df["premium"] = _exact(df.index, premium, "close_time", "premium")
    _metrics_columns(df, metrics)
    # 现货 K 线和永续同一套收盘时刻，精确对；没有现货对、现货还没上市或已下架的那段是 NaN
    for col, src in _SPOT_SOURCE.items():
        df[col] = _exact(df.index, spot, "close_time", src)
    # bar 开始时刻的 UTC 小时：00:45~01:00 那根算 0 点的（closeTime 是边界减 1ms，加回去再退一根）
    df["hour"] = ((df.index.to_numpy() + 1 - BAR_MS) // 3_600_000) % 24
    df[POOL_COLUMN] = np.isin(_months(df.index), list(pool_months)).astype("float64")
    add_targets(df)
    return df


def add_targets(df: pd.DataFrame) -> None:
    """标准答案三档：t 之后第 DELAY 根收盘建仓、持有 h 根的对数涨跌幅，末尾不够或中间跨了缺口的是 NaN"""
    for bars in HORIZON_BARS.values():
        df[f"fwd_ret_{bars}"] = (np.log(df["close"].shift(-(DELAY + bars)) / df["close"].shift(-DELAY))
                                 .where(_spaced(df.index, DELAY + bars)))


def _funding_per_period(funding: pd.DataFrame | None) -> pd.DataFrame | None:
    """费率 × 8 小时 ÷ 这一期的间隔。间隔按这次结算离上次多久推；第一条没有上一次、断档（超过 8 小时）都按 8 小时"""
    if funding is None or funding.empty:
        return funding
    f = funding.drop_duplicates("funding_time").sort_values("funding_time")
    interval = f["funding_time"].diff().fillna(FUNDING_PERIOD_MS).clip(upper=FUNDING_PERIOD_MS)
    return f.assign(funding_rate=f["funding_rate"] * FUNDING_PERIOD_MS / interval)


def _dead(trades: np.ndarray) -> np.ndarray:
    """落在连续 ≥ DEAD_BARS 根零成交段里的根"""
    zero = np.concatenate(([0], (trades == 0).astype(np.int8), [0]))
    edges = np.diff(zero)
    out = np.zeros(len(trades), dtype=bool)
    for start, end in zip(np.flatnonzero(edges == 1), np.flatnonzero(edges == -1)):
        if end - start >= DEAD_BARS:
            out[start:end] = True
    return out


def _spaced(index: pd.Index, bars: int) -> np.ndarray:
    """第 i 根和它后面第 bars 根是不是正好隔 bars 个 15m；末尾不够 bars 根的 False"""
    t = index.to_numpy(dtype="int64")
    out = np.zeros(len(t), dtype=bool)
    out[: len(t) - bars] = (t[bars:] - t[:-bars]) == bars * BAR_MS
    return out


def _asof(index: pd.Index, source: pd.DataFrame | None, time_col: str, value_col: str) -> np.ndarray:
    """每个收盘时刻取 source 里时间 ≤ 它的最近一条，没有就是 NaN"""
    if source is None or source.empty:
        return np.full(len(index), np.nan)
    src = pd.DataFrame({"t": source[time_col].to_numpy(dtype="int64"),
                        "v": source[value_col].to_numpy(dtype="float64")}).sort_values("t")
    left = pd.DataFrame({"t": index.to_numpy(dtype="int64")})
    return pd.merge_asof(left, src, on="t", direction="backward")["v"].to_numpy(dtype="float64")


def _exact(index: pd.Index, source: pd.DataFrame | None, time_col: str, value_col: str) -> np.ndarray:
    """按时刻精确对上，没有就是 NaN"""
    if source is None or source.empty:
        return np.full(len(index), np.nan)
    series = source.drop_duplicates(time_col).set_index(time_col)[value_col].astype("float64")
    return series.reindex(index.to_numpy(dtype="int64")).to_numpy(dtype="float64")


def _metrics_columns(df: pd.DataFrame, metrics: pd.DataFrame | None) -> None:
    """每列取收盘时刻之前最近一条有效值，一小时没新的记 NaN；≤ 0 当没有：持仓量、多空比不会是 0，归档里成片的 0 是坏数"""
    if metrics is None or metrics.empty:
        for c in METRIC_FEATURES:
            df[c] = np.nan
        return
    times = df.index.to_numpy(dtype="int64")
    for c in METRIC_FEATURES:
        valid = metrics[metrics[c] > 0]
        seen = _asof(df.index, valid, "time", "time")
        df[c] = np.where(times - seen > METRICS_STALE_MS, np.nan, _asof(df.index, valid, "time", c))


def _liq_columns(df: pd.DataFrame, liq: pd.DataFrame | None, liq_start: int | None,
                 outage: frozenset[int]) -> None:
    """有数据的时候没强平就是 0；数据开始前和采集断掉的桶是 NaN"""
    if liq is None or liq_start is None:
        for c in LIQ_COLUMNS:
            df[c] = np.nan
        return
    joined = df[[]].join(liq.drop_duplicates("close_time").set_index("close_time")[LIQ_COLUMNS], how="left")
    times = df.index.to_numpy(dtype="int64")
    known = (times >= liq_start) & ~np.isin(times, list(outage))
    for c in LIQ_COLUMNS:
        values = joined[c].to_numpy(dtype="float64", copy=True)
        values[known & np.isnan(values)] = 0.0
        values[~known] = np.nan
        df[c] = values


def _months(index: pd.Index) -> np.ndarray:
    """每根 bar 收盘时刻所在的 UTC 月份 YYYY-MM"""
    return index.to_numpy(dtype="int64").astype("datetime64[ms]").astype("datetime64[M]").astype(str)


def holdout_cut(panels: dict[str, pd.DataFrame], holdout_days: int) -> int:
    """全部币最后一根的收盘时刻往前数 holdout_days 天；之后的是 holdout"""
    return max(int(p.index.max()) for p in panels.values()) - holdout_days * DAY_MS


def truncate(panel: pd.DataFrame, cut_ms: int) -> pd.DataFrame:
    """只留 cut 及之前的行，标准答案在截断处重算"""
    df = panel[panel.index <= cut_ms].copy()
    add_targets(df)
    return df


def describe(panel: pd.DataFrame) -> dict[str, Any]:
    liq_known = panel.index[panel["liq_count"].notna()]
    # 现货列进面板之前装配的老版面板没有这列
    spot_known = panel.index[panel["spot_quote_volume"].notna()] if "spot_quote_volume" in panel else panel.index[:0]
    times = panel.index.to_numpy(dtype="int64")
    out = {
        "rows": int(len(panel)),
        "from": int(panel.index.min()) if len(panel) else None,
        "to": int(panel.index.max()) if len(panel) else None,
        "liqFrom": int(liq_known.min()) if len(liq_known) else None,
        "spotFrom": int(spot_known.min()) if len(spot_known) else None,
        # 相邻两根不是正好 15m 的地方：交易所维护缺 bar 时切窗按行号会漂，先把数字亮出来
        "gaps": int((np.diff(times) != BAR_MS).sum()) if len(times) > 1 else 0,
        "coverage": {c: round(float(panel[c].notna().mean()), 4) for c in COVERAGE_COLUMNS if c in panel},
    }
    if POOL_COLUMN in panel:
        out["poolMonths"] = int(len(set(_months(panel.index[panel[POOL_COLUMN] == 1.0]))))
    return out


class PanelStore:
    def __init__(self, data_dir: str | Path, version: str | None = None):
        """version 不给就是当前那版；raw/ 各版共用"""
        self.root = Path(data_dir)
        self.raw = self.root / "raw"
        self.live = self.root / "live"
        current = self.root / CURRENT
        if version is None:
            version = current.read_text(encoding="utf-8").strip() if current.exists() else FIRST_VERSION
        self.panels = self.root / version
        for dataset in RAW_DATASETS:
            (self.raw / dataset).mkdir(parents=True, exist_ok=True)
        self.panels.mkdir(parents=True, exist_ok=True)

    def next_version(self) -> "PanelStore":
        """刷新要写的新一版；当前这版以外的（上一版、刷新失败留下的半版）先删掉"""
        for d in self.root.iterdir():
            if d.is_dir() and _VERSION.fullmatch(d.name) and d != self.panels:
                shutil.rmtree(d)
        return PanelStore(self.root, f"{FIRST_VERSION}-{datetime.now(timezone.utc):%Y%m%dT%H%M%S%f}")

    def publish(self) -> None:
        """这一版写完了，换成当前版；之后新装载的数据都读它"""
        _replace_text(self.root / CURRENT, self.panels.name)

    def grow(self, dataset: str, symbol: str, fetch: Callable[[str, int, int], pd.DataFrame],
             earliest: int, now_ms: int) -> pd.DataFrame:
        """有缓存就从最后一条之后接着拉，没有就从 earliest 拉；想拉更早的删掉这个文件重来"""
        time_col = RAW_DATASETS[dataset]
        old = self.load_raw(dataset, symbol)
        start = earliest if old is None or old.empty else int(old[time_col].max()) + 1
        new = fetch(symbol, start, now_ms)
        if old is None:
            df = new
        elif new.empty:
            df = old
        else:
            df = pd.concat([old, new], ignore_index=True)
        df = df.drop_duplicates(time_col).sort_values(time_col).reset_index(drop=True)
        self.save_raw(dataset, symbol, df)
        return df

    def load_raw(self, dataset: str, symbol: str, since: int | None = None) -> pd.DataFrame | None:
        """原始数据，按时间排好；since 给了只要这之后的（含）；没拉过是 None"""
        path = self.raw / dataset / f"{symbol}.parquet"
        if not path.exists():
            return None
        return pd.read_parquet(path, filters=None if since is None else [(RAW_DATASETS[dataset], ">=", since)])

    def save_raw(self, dataset: str, symbol: str, df: pd.DataFrame) -> None:
        _write(df, self.raw / dataset / f"{symbol}.parquet")

    def load_live(self, dataset: str, symbol: str) -> pd.DataFrame | None:
        """live 尾巴，时间列同 raw；没补过是 None"""
        path = self.live / dataset / f"{symbol}.parquet"
        return pd.read_parquet(path) if path.exists() else None

    def save_live(self, dataset: str, symbol: str, df: pd.DataFrame) -> None:
        (self.live / dataset).mkdir(parents=True, exist_ok=True)
        _write(df, self.live / dataset / f"{symbol}.parquet")

    def save_panel(self, symbol: str, panel: pd.DataFrame) -> None:
        _write(panel, self.panels / f"{symbol}.parquet")

    def load(self, symbol: str, columns: list[str] | None = None) -> pd.DataFrame:
        """columns 给了只读这几列，index 照样带着"""
        return pd.read_parquet(self.panels / f"{symbol}.parquet", columns=columns)

    def has(self, symbol: str) -> bool:
        return (self.panels / f"{symbol}.parquet").exists()

    def write_status(self, status: dict[str, dict[str, Any]]) -> None:
        """每币的 describe 落成 json，页面读这个不读 parquet"""
        _replace_text(self.panels / "status.json", json.dumps(status, ensure_ascii=False))

    def read_status(self) -> dict[str, dict[str, Any]]:
        path = self.panels / "status.json"
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}

    def save_reference_grid(self, grid: pd.DataFrame) -> None:
        _write(grid, self.panels / REFERENCE_GRID)

    def load_reference_grid(self) -> pd.DataFrame | None:
        """参照网格比 status.json 旧（之后又刷新过面板）就当没有"""
        path, status = self.panels / REFERENCE_GRID, self.panels / "status.json"
        if not path.exists() or (status.exists() and path.stat().st_mtime < status.stat().st_mtime):
            return None
        return pd.read_parquet(path)


def _write(df: pd.DataFrame, path: Path) -> None:
    """先写临时文件再改名，中途死掉不留半个 parquet"""
    tmp = path.with_name(path.name + ".tmp")
    df.to_parquet(tmp)
    os.replace(tmp, path)


def _replace_text(path: Path, text: str) -> None:
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)
