"""live 尾巴和归档的一致性核对（方案 2.4），在服务器上跑：python -m wiib_alpha.trade.check [--fetch 天数]

默认拿 live/ 里和 raw 重叠的那段（live 存最近 29~30 天，和归档重叠大半）；--fetch N 是现从 fapi 拉每个币归档最后 N 天放内存里比，不写盘。
K 线、溢价 K 线、费率、现货 K 线按时刻逐条逐列比，应完全一致；metrics（持仓量、三种多空比、taker 买卖量比）报时间戳对上多少、相对差多少，只看不判；
再拿重叠段里每个 UTC 0 点，分别用"归档版"（只用 raw）和"live 版"（重叠段换成 live，窗口整个落在 live 里时就是交易员用的那份）拼 28 天面板，
算勾选因子、basis_7d、Std($ret, 672) 在那一刻的截面值，两版的 Spearman 排名相关都 ≥ 0.99 算过。全过退出码 0，否则 1。
"""

from __future__ import annotations

import argparse
import logging
import sys
from typing import Any

import numpy as np
import pandas as pd

from ..config import config
from ..data import universe as universe_table
from ..data.binance import DAY_MS, KLINE_COLUMNS, Binance
from ..data.panel import BENCHMARK, FEATURE_COLUMNS, RAW_DATASETS, PanelStore
from ..data.pool import month_label, month_of
from ..dsl import parse
from ..eval.params import EvalParams
from ..eval.stats import spearman
from . import live, scheme, targets

MIN_RHO = 0.99
# 逐列比的列：K 线除了收盘时刻（对齐用）全比
_BARS = [c for c in KLINE_COLUMNS if c != "close_time"]
COMPARE = {"klines_15m": _BARS, "premium_15m": ["premium"], "spot_15m": _BARS,
           "metrics": ["oi", "oi_value", "ls_top_pos", "ls_top_acct", "ls_acct", "taker_ls_vol"], "funding": ["funding_rate"]}
_EXAMPLES = 5

Frames = dict[str, dict[str, pd.DataFrame]]


def stored(store: PanelStore, symbols: list[str]) -> Frames:
    """live/ 里的尾巴"""
    return {d: {s: df for s in symbols if (df := store.load_live(d, s)) is not None and not df.empty}
            for d in live.DATASETS}


def fetched(store: PanelStore, binance: Binance, symbols: list[str], days: int) -> Frames:
    """每个币每个数据集现拉归档最后 days 天"""
    spot = live.spot_pairs(binance, symbols)
    fetch = {"klines_15m": binance.recent_klines, "premium_15m": binance.recent_premium,
             "metrics": binance.recent_metrics, "funding": binance.recent_funding,
             "spot_15m": lambda s, a, b: binance.recent_spot_klines(spot[s], a, b)}
    out: Frames = {d: {} for d in live.DATASETS}
    for s in symbols:
        for d in live.DATASETS:
            raw = store.load_raw(d, s)
            if raw is None or raw.empty or (d == "spot_15m" and spot[s] is None):
                continue
            end = int(raw[RAW_DATASETS[d]].max()) + 1
            df = fetch[d](s, end - days * DAY_MS, end)
            if not df.empty:
                out[d][s] = df
    return out


def compare_rows(raws: Frames, tails: Frames, dataset: str, exact: bool) -> bool:
    """重叠段按时间列对齐逐列比；exact 要完全一致（不一致、只在一边的都算不过），否则只报相对差"""
    col, columns = RAW_DATASETS[dataset], COMPARE[dataset]
    matched = same = only_raw = only_live = 0
    examples: list[str] = []
    rel: dict[str, list[np.ndarray]] = {c: [] for c in columns}
    for s, tail in sorted(tails[dataset].items()):
        raw = raws[dataset].get(s)
        if raw is None or raw.empty:
            continue
        lo, hi = int(tail[col].min()), int(raw[col].max())
        a, b = raw[(raw[col] >= lo) & (raw[col] <= hi)], tail[tail[col] <= hi]
        m = a[[col, *columns]].merge(b[[col, *columns]], on=col, how="outer", suffixes=("_raw", "_live"), indicator=True)
        only_raw += int((m["_merge"] == "left_only").sum())
        only_live += int((m["_merge"] == "right_only").sum())
        both = m[m["_merge"] == "both"]
        matched += len(both)
        bad = np.zeros(len(both), dtype=bool)
        for c in columns:
            x, y = both[f"{c}_raw"].to_numpy(dtype="float64"), both[f"{c}_live"].to_numpy(dtype="float64")
            diff = (x != y) & ~(np.isnan(x) & np.isnan(y))
            bad |= diff
            with np.errstate(all="ignore"):
                rel[c].append(np.abs(x - y) / np.abs(x))
            for i in np.flatnonzero(diff)[:_EXAMPLES - len(examples)]:
                examples.append(f"{s} {_time(int(both[col].iloc[i]))} 收盘的那条 {c}：raw {float(x[i])!r}，live {float(y[i])!r}")
        same += int((~bad).sum())
    print(f"[{dataset}] 时间对上 {matched} 条，逐列完全一致 {same}，有差 {matched - same}；只在 raw {only_raw}，只在 live {only_live}")
    for c in columns:
        r = np.concatenate(rel[c]) if rel[c] else np.empty(0)
        r = r[np.isfinite(r)]
        # 要完全一致的只报有差的列
        if r.size and (not exact or r.max() > 0):
            print(f"  {c} 相对差：中位 {np.median(r):.2e}，95% 分位 {np.quantile(r, 0.95):.2e}，最大 {r.max():.2e}")
    for e in examples:
        print(f"  例：{e}")
    if not exact:
        print("  （只看不判，下面的因子排名相关才是判据）")
        return True
    ok = matched > 0 and same == matched and only_raw == only_live == 0
    print(f"  {'通过' if ok else '不通过'}" + ("（没有重叠的数据）" if not matched else ""))
    return ok


def compare_ranks(raws: Frames, tails: Frames, universe: pd.DataFrame, factors: list[dict[str, Any]],
                  params: EvalParams) -> bool:
    """重叠段每个 UTC 0 点，归档版、live 版各拼一份面板，比每条因子在那一刻的截面排名"""
    exprs = {f["name"]: parse(f["expr"], FEATURE_COLUMNS) for f in factors}
    exprs.update({"basis_7d": scheme.CARRY, "Std($ret, 672)": scheme.VOL})
    starts = [int(df["close_time"].min()) for df in tails["klines_15m"].values()]
    ends = [int(df["close_time"].max()) for s in tails["klines_15m"]
            if (df := raws["klines_15m"].get(s)) is not None and not df.empty]
    if not starts or not ends:
        print("[因子排名] 没有重叠的数据\n  不通过")
        return False
    # 收盘时刻在重叠段里的 UTC 0 点那根
    moments = [t - 1 for t in range((min(starts) // DAY_MS + 1) * DAY_MS, max(ends) + 2, DAY_MS)]
    months = universe_table.months_by_symbol(universe)

    def archive(d: str, s: str) -> pd.DataFrame | None:
        return raws[d].get(s)

    def with_live(d: str, s: str) -> pd.DataFrame | None:
        raw, tail = raws[d].get(s), tails.get(d, {}).get(s)
        if tail is None:
            return raw
        col = RAW_DATASETS[d]
        return tail if raw is None else pd.concat([raw[raw[col] < tail[col].min()], tail], ignore_index=True)

    ok = bool(moments)
    print(f"[因子排名] 归档版与 live 版在每个 UTC 0 点的截面 Spearman（括号里是两版都有数的币数），≥ {MIN_RHO} 算过")
    for t in moments:
        pool = sorted(universe.loc[universe["month"] == month_label(month_of(np.array([t]))[0]), "symbol"])
        a, b = live.window(archive, pool, months, t), live.window(with_live, pool, months, t)
        cells = []
        for name, e in exprs.items():
            x, y = targets.values_at(a, e, t).align(targets.values_at(b, e, t), join="inner")
            n = int((np.isfinite(x) & np.isfinite(y)).sum())
            rho = spearman(x.to_numpy(), y.to_numpy())
            passed = n >= params.min_names and rho >= MIN_RHO
            ok &= passed
            cells.append(f"{name} {rho:.4f}（{n}）{'' if passed else ' ×'}")
        print(f"  {_time(t + 1)[:16]}  " + "  ".join(cells))
    print(f"  {'通过' if ok else '不通过'}")
    return ok


def _time(ms: int) -> str:
    """UTC，到秒"""
    return str(np.datetime64(ms // 1000, "s")).replace("T", " ")


def main() -> None:
    parser = argparse.ArgumentParser(description="live 尾巴与归档的一致性核对")
    parser.add_argument("--fetch", type=int, default=0, help="现从 fapi 拉每个币归档最后几天来比；不给就用 live/ 里的")
    args = parser.parse_args()
    logging.basicConfig(level=logging.WARNING)
    # fapi 已用权重打出来
    logging.getLogger("wiib_alpha").setLevel(logging.INFO)
    cfg = config()
    params = EvalParams(cost_tiers_bps=cfg.cost_tiers_bps)
    universe, factors, _ = scheme.read_db(cfg.db_path)
    store = PanelStore(cfg.data_dir)
    symbols = sorted({*universe.loc[universe["month"] == universe["month"].max(), "symbol"], BENCHMARK})
    if args.fetch:
        binance = Binance(cfg.binance_vision_url, fapi_url=cfg.binance_fapi_url, list_url=cfg.binance_vision_list_url,
                          spot_url=cfg.binance_spot_url)
        tails = fetched(store, binance, symbols, args.fetch)
    else:
        tails = stored(store, symbols)
    print(f"一致性核对：{len(symbols)} 个币，live 那边来自{'fapi 现拉归档最后 %d 天' % args.fetch if args.fetch else ' live/ 目录'}；"
          f"勾选的因子：{', '.join(f['name'] for f in factors) or '无'}", flush=True)
    # raw 只读一次：从最早那段重叠往前留一个面板窗口
    first = min((int(df[RAW_DATASETS[d]].min()) for d, by in tails.items() for df in by.values()), default=None)
    if first is None:
        print("live 那边没有数据\n结论：不通过")
        sys.exit(1)
    since = live.window_start(first)
    raws: Frames = {d: {s: df for s in symbols if (df := store.load_raw(d, s, since)) is not None}
                    for d in live.DATASETS}
    ok = compare_rows(raws, tails, "klines_15m", True)
    ok &= compare_rows(raws, tails, "premium_15m", True)
    ok &= compare_rows(raws, tails, "funding", True)
    ok &= compare_rows(raws, tails, "spot_15m", True)
    compare_rows(raws, tails, "metrics", False)
    ok &= compare_ranks(raws, tails, universe, factors, params)
    print(f"结论：{'通过' if ok else '不通过'}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
