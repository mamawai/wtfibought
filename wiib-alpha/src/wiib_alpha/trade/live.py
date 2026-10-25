"""live：交易员出信号用的最近 28 天面板，K 线、溢价 K 线、metrics（持仓量、三种多空比、taker 买卖量比）、资金费全走 fapi，不读归档
（归档晚一天出，metrics 还出过成片乱跳的非零坏数，剔不干净）。

存 live/{数据集}/{币}.parquet，滚动留 30 天；不写 raw/，不然下次刷新的增量起点被顶掉、两种口径混进历史。
和 raw 重叠的那段给一致性核对（check.py）用。
强平三列没补（只有产品币有），用到的因子交易员不能勾（COLUMNS）。拼好按 panel.assemble 同一套规则装配。
第一次要补满 29 天，约一小时：部署后先跑 python -m wiib_alpha.trade.live 预热，别留到 00:05 调仓时现补。
给 live 加列时要删掉 live/ 下对应数据集的目录再预热，增量补不会回头填老行。
"""

from __future__ import annotations

import argparse
import logging
from collections.abc import Callable, Iterable, Mapping
from typing import Any

import pandas as pd

from ..config import config
from ..data.binance import BAR_MS, DAY_MS, Binance, BinanceError
from ..data.panel import BENCHMARK, RAW_DATASETS, PanelStore, assemble
from ..store import now_ms
from . import scheme

logger = logging.getLogger(__name__)

KEEP_DAYS = 30
# 出信号给多少天的面板：rally_basis_chase 是 2016 套 96（22 天），ls_pos_stable 是 672 套 672（14 天）；
# 回看更长的因子勾了也出不了值（targets 会报错不调仓）。持仓量、多空比接口只留 30 天，再长补不到
WINDOW_DAYS = 28
DATASETS = ("klines_15m", "premium_15m", "metrics", "funding")
# live 尾巴补得出来的 DSL 列
COLUMNS = frozenset({"open", "high", "low", "close", "volume", "quote_volume", "trades", "taker_buy_base",
                     "taker_buy_quote", "ret", "btc_close", "btc_ret", "premium", "oi", "oi_value", "ls_top_pos",
                     "ls_top_acct", "ls_acct", "taker_ls_vol", "funding_rate", "hour"})

# (数据集, 币) → 这个币拼好的原始数据，只含窗口起点之后的
Load = Callable[[str, str], pd.DataFrame | None]


def update(store: PanelStore, binance: Binance, symbols: Iterable[str], now: int, progress: dict[str, Any]) -> None:
    """每个币每个数据集从 live 最后一条之后补到 now；没补过或断太久的从 WINDOW_DAYS + 1 天前补起，再早的出信号用不上"""
    fetch = {"klines_15m": binance.recent_klines, "premium_15m": binance.recent_premium,
             "metrics": binance.recent_metrics, "funding": binance.recent_funding}
    floor = now - (WINDOW_DAYS + 1) * DAY_MS
    symbols = list(symbols)
    progress.update(total=len(symbols), done=0)
    for s in symbols:
        progress["symbol"] = s
        for dataset in DATASETS:
            col = RAW_DATASETS[dataset]
            old = store.load_live(dataset, s)
            start = floor if old is None or old.empty else max(floor, int(old[col].max()) + 1)
            new = fetch[dataset](s, start, now)
            if old is None:
                df = new
            elif new.empty:
                df = old
            else:
                df = pd.concat([old, new], ignore_index=True)
            df = df[df[col] >= now - KEEP_DAYS * DAY_MS]
            store.save_live(dataset, s, df.drop_duplicates(col).sort_values(col).reset_index(drop=True))
        progress["done"] += 1
    progress["symbol"] = None


def window_start(t_ms: int) -> int:
    """出 t 的信号要从哪一刻起读原始数据：窗口再往前留一天，第一根的涨跌和对齐用"""
    return t_ms - (WINDOW_DAYS + 1) * DAY_MS


def loader(store: PanelStore, t_ms: int) -> Load:
    """交易员出 t 的信号用的取数：DATASETS 只读 live/，别的读 raw"""
    since = window_start(t_ms)

    def load(dataset: str, symbol: str) -> pd.DataFrame | None:
        if dataset not in DATASETS:
            return store.load_raw(dataset, symbol, since)
        df = store.load_live(dataset, symbol)
        return None if df is None else df[df[RAW_DATASETS[dataset]] >= since]
    return load


def window(load: Load, symbols: Iterable[str], pool_months: Mapping[str, frozenset[str]],
           t_ms: int) -> dict[str, pd.DataFrame]:
    """每个币收盘时刻在 (t − WINDOW_DAYS 天, t] 的面板，assemble 装配，在池掩码按 universe 表；没有 K 线的币不出"""
    btc = load("klines_15m", BENCHMARK)
    out = {}
    for s in symbols:
        klines = load("klines_15m", s)
        if klines is None or klines.empty:
            continue
        panel = assemble(klines, load("funding", s), None if s == BENCHMARK else btc, premium=load("premium_15m", s),
                         metrics=load("metrics", s), pool_months=pool_months.get(s, frozenset()))
        out[s] = panel[(panel.index > t_ms - WINDOW_DAYS * DAY_MS) & (panel.index <= t_ms)]
    return out


def closes(binance: Binance, symbols: Iterable[str], close_time: int) -> dict[str, float]:
    """收盘时刻正好是 close_time 的那根 15m 的收盘价，一个币拉一根（权重 1）；拉不到的币不出"""
    out = {}
    for s in symbols:
        try:
            df = binance.recent_klines(s, close_time + 1 - BAR_MS, close_time + 1)
        except BinanceError as exc:
            logger.warning("拉 %s 的收盘价失败：%s", s, exc)
            continue
        hit = df.loc[df["close_time"] == close_time, "close"]
        if len(hit):
            out[s] = float(hit.iloc[0])
    return out


def main() -> None:
    argparse.ArgumentParser(description="预热 live：给最新一个月币池里在交易的币和 BTC 补满最近 29 天").parse_args()
    logging.basicConfig(level=logging.WARNING)
    # fapi 已用权重打出来
    logging.getLogger("wiib_alpha").setLevel(logging.INFO)
    cfg = config()
    universe, _, _ = scheme.read_db(cfg.db_path)
    binance = Binance(cfg.binance_vision_url, fapi_url=cfg.binance_fapi_url, list_url=cfg.binance_vision_list_url)
    info = binance.exchange_info()
    # 和调仓同口径：下架、停牌的币 fapi 拉不到
    pool = universe.loc[universe["month"] == universe["month"].max(), "symbol"]
    symbols = sorted({*(s for s in pool if info.get(s, {}).get("status") == "TRADING"), BENCHMARK})
    progress: dict[str, Any] = {}
    update(PanelStore(cfg.data_dir), binance, symbols, now_ms(), progress)
    print(f"live 补好了：{progress['done']} 个币", flush=True)


if __name__ == "__main__":
    main()
