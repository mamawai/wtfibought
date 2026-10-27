"""校准脚本：把几类已知因子过一遍全部截面门，看门本身对不对。

  uv run python -m wiib_alpha.calibrate --synthetic            合成币池：验假阳率、泄漏捕获、植入信号过不过
  uv run python -m wiib_alpha.calibrate --data-dir ./data      真数据：看基线与参照集能活几个（要先刷新出池子面板）
  uv run python -m wiib_alpha.calibrate --g9 60 --data-dir ./data   真数据：G9 阈值怎么定，库里每条、库外候选、60 条随机因子
                                                                    加进交易方案后净 Sharpe 各升多少（G9 判的 2022 起和全段各一份），
                                                                    只跑这一项

合成币池：N 个币随机游走（第一个当 BTC），共同的市场因子乘各币的 beta 再加各币自己的噪音，全程在池，排名按成交额；
资金费率每 8 小时结算、按 AR(1) 慢慢变，植入一个截面信号：每根 bar 的收益加 β × 上一根费率的截面 z 分数，
所以 $funding_rate 就是植入因子，随机因子不用这一列。
验收：随机因子不做同批 BH 的单条通过率 ≤ 10%（这才是门本身的判别力），泄漏因子 100% 被 G0 抓住，植入因子过门；
同批 BH 按生产的 K 分批再算一遍只做参考。基线与参照集的门表打印出来人读。
"""

from __future__ import annotations

import argparse
import sys
import tempfile
import time
from contextlib import nullcontext
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from . import ops, reference
from .data import universe as universe_table
from .data.binance import BAR_MS, DAY_MS, KLINE_COLUMNS, day_name
from .data.panel import BENCHMARK, FEATURE_COLUMNS, PanelStore, assemble
from .data.pool import Pool
from .dsl import DslError, Expr, parse
from .eval.combo import Combo
from .eval.evaluate import evaluate
from .eval.gates import apply_fdr, judge, passed
from .eval.params import EvalParams, Thresholds
from .store import snapshot
from .trade import scheme

# funding_rate 是植入信号的列，随机因子不用它
RANDOM_COLUMNS = ("close", "volume", "ret", "quote_volume", "taker_buy_base", "high", "low")
RANDOM_ROLLING = ("Mean", "Std", "Sum", "Max", "Min", "Rank", "Delta", "EMA", "Slope", "Skew", "WMA")
RANDOM_WINDOWS = (4, 16, 48, 96, 192)
# Lead 只在这里临时注册，正式算子表里没有；最后一条把偷看藏在截面算子里面
LEAKY = {
    "lead_ret": "Lead($ret, 1)",
    "oracle_12h": "Lead(Sum($ret, 48), 48)",
    "hidden_lead": "Sum($ret, 4) - Lead($ret, 2) * 0.1",
    "cross_lead": "CsRank(Lead($ret, 4))",
}
PLANTED = {"planted_carry": "$funding_rate"}
GATE_IDS = [f"G{i}" for i in range(11)]
# 2024-01-01 00:00 UTC，对齐到 0 点，网格从这里数
SYNTHETIC_START = 1_704_067_200_000
# 一次结算 32 根 bar（8 小时）
SETTLE_BARS = 32


# ========== 数据 ==========


def synthetic_store(root: str | Path, coins: int, days: int, seed: int, beta: float) -> tuple[PanelStore, pd.DataFrame]:
    """合成币池落到 root：每币一张真格式的面板（全程在池），返回 store 和池子表"""
    n = days * 96
    rng = np.random.default_rng(seed)
    symbols = [BENCHMARK, *(f"C{i:03d}USDT" for i in range(1, coins))]
    # 费率按结算点走 AR(1)，两次结算之间不变
    settle = np.arange(SETTLE_BARS - 1, n, SETTLE_BARS)
    rates = np.zeros((len(settle), coins))
    rates[0] = rng.normal(0, 1e-4, coins)
    for k in range(1, len(settle)):
        rates[k] = 0.95 * rates[k - 1] + rng.normal(0, 1e-4 * np.sqrt(1 - 0.95 ** 2), coins)
    level = np.full((n, coins), np.nan)
    level[settle] = rates
    level = pd.DataFrame(level).ffill().to_numpy()
    with np.errstate(invalid="ignore"):
        z = (level - level.mean(axis=1, keepdims=True)) / level.std(axis=1, ddof=1, keepdims=True)
    ret = rng.normal(0, 0.002, n)[:, None] * rng.uniform(0.5, 1.5, coins) + rng.normal(0, 0.003, (n, coins))
    ret[1:] += beta * np.nan_to_num(z[:-1])
    size = rng.normal(0, 1, coins)
    store = PanelStore(root)
    open_time = SYNTHETIC_START + np.arange(n) * BAR_MS
    months = frozenset(np.datetime_as_string(np.unique((open_time + BAR_MS - 1).astype("datetime64[ms]")
                                                       .astype("datetime64[M]"))))
    btc = None
    quote = {}
    for i, s in enumerate(symbols):
        close = 100 * np.exp(np.cumsum(ret[:, i]))
        open_ = np.concatenate([[close[0]], close[:-1]])
        spread = np.abs(rng.normal(0, 0.002, n))
        volume = rng.lognormal(5 + size[i], 0.6, n)
        taker = volume * rng.uniform(0.3, 0.7, n)
        rows = np.column_stack([
            open_time, open_, np.maximum(open_, close) * (1 + spread), np.minimum(open_, close) * (1 - spread), close,
            volume, open_time + BAR_MS - 1, volume * close, rng.integers(10, 500, n), taker, taker * close,
        ])
        klines = pd.DataFrame(rows, columns=KLINE_COLUMNS).astype({"open_time": "int64", "close_time": "int64",
                                                                   "trades": "int64"})
        funding = pd.DataFrame({"funding_time": klines["close_time"].to_numpy()[settle], "funding_rate": rates[:, i]})
        # 多空腿按 raw 里的结算记录付资金费，和生产一样
        store.save_raw("funding", s, funding)
        store.save_panel(s, assemble(klines, funding, btc, pool_months=months))
        btc = klines if btc is None else btc
        quote[s] = float((volume * close).mean())
    store.write_status({s: {} for s in symbols})
    ranked = sorted(symbols, key=lambda s: -quote[s])
    table = pd.DataFrame([(m, s, r + 1, quote[s]) for m in sorted(months) for r, s in enumerate(ranked)],
                         columns=universe_table.COLUMNS)
    return store, table


def real_universe(db_path: str) -> pd.DataFrame:
    with snapshot(db_path) as db:
        return pd.read_sql_query("SELECT month, symbol, rank, avg_quote_volume FROM universe", db)


def load_pool(store: PanelStore, table: pd.DataFrame, holdout_days: int) -> Pool:
    last = int(store.load(BENCHMARK, ["close"]).index.max())
    return Pool.load(store, table, last - holdout_days * DAY_MS)


# ========== 几类因子 ==========


def random_expression(rng: np.random.Generator, panel: pd.DataFrame) -> Expr:
    """随机拼一条表达式；拼出来全 NaN 或不动的重拼"""
    def leaf() -> str:
        return f"${rng.choice(RANDOM_COLUMNS)}"

    def node(depth: int) -> str:
        if depth == 0 or rng.random() < 0.25:
            return leaf()
        kind = rng.integers(0, 3)
        if kind == 0:
            return f"{rng.choice(RANDOM_ROLLING)}({node(depth - 1)}, {rng.choice(RANDOM_WINDOWS)})"
        if kind == 1:
            return f"({node(depth - 1)} {rng.choice(['+', '-', '*', '/'])} {node(depth - 1)})"
        return f"Corr({node(depth - 1)}, {node(depth - 1)}, {rng.choice(RANDOM_WINDOWS)})"

    while True:
        try:
            expr = parse(node(3), FEATURE_COLUMNS)
        except DslError:
            continue
        out = expr.evaluate(panel).to_numpy()
        finite = np.isfinite(out)
        if finite.mean() > 0.5 and np.nanstd(out) > 0:
            return expr


def run(name: str, exprs: dict[str, Expr], pool: Pool, vol: np.ndarray, params: EvalParams, thresholds: Thresholds,
        grid: reference.Grid, known: set[str], base: scheme.Base) -> list[dict[str, Any]]:
    out = []
    print(f"\n== {name}：{len(exprs)} 条 ==", flush=True)
    for i, (label, expr) in enumerate(exprs.items(), 1):
        t = time.perf_counter()
        ev = evaluate(expr, pool, vol, params, grid, base)
        ev["name"] = label
        ev["seconds"] = time.perf_counter() - t
        ev["gates"] = judge(ev, thresholds, known)
        ev["passed"] = passed(ev["gates"])
        out.append(ev)
        print(f"  [{i}/{len(exprs)}] {label} {ev['seconds']:.1f}s {'PASS' if ev['passed'] else ''}", flush=True)
    return out


def carry_share(db_path: str) -> float:
    """线上设的 carry 腿份额，和服务里的 G9 基线同口径；没设过是默认"""
    with snapshot(db_path) as db:
        row = db.execute("SELECT share FROM trade_carry WHERE id = 1").fetchone()
    return float(row[0]) if row else scheme.CARRY_SHARE


def g9(store: PanelStore, db_path: str, params: EvalParams, randoms: int, seed: int) -> None:
    """G9 的阈值怎么定：库里每条（拿掉它的方案再加回去）、库外的候选、随机因子，加进交易方案后搜索段净 Sharpe 各升多少"""
    with snapshot(db_path) as db:
        universe = pd.read_sql_query("SELECT month, symbol, rank, avg_quote_volume FROM universe", db)
        library = db.execute("SELECT name, expr FROM factor WHERE retired_at IS NULL ORDER BY approved_at").fetchall()
        # 每条假设留着的是最后一版表达式；同一个表达式只算一次
        candidates = {expr: name for name, expr in db.execute(
            "SELECT name, expr FROM candidate WHERE expr IS NOT NULL ORDER BY updated_at")}
    share = carry_share(db_path)
    pool = load_pool(store, universe, params.holdout_days)
    pick = pool.in_search
    vol, basis = pool.values(scheme.VOL).f[pick], scheme.carry_signal(pool)[pick]
    search = lambda expr: pool.values(parse(expr, FEATURE_COLUMNS)).f[pick]

    def base(values: dict[str, np.ndarray]) -> scheme.Base:
        c = Combo.empty(pool.search, pool.symbols)
        for name, f in values.items():
            c = c.plus(name, f)
        return scheme.Base.of(c, basis, vol, params, share)

    lib = {name: search(expr) for name, expr in library}
    full = base(lib)
    since = day_name(params.recent_from)
    print(f"基线：库里 {len(lib)} 条 + carry（占 {share:.0%}），搜索段净 Sharpe {since} 起 {full.result['recentSharpe']:.2f}、"
          f"全段 {full.result['sharpe']:.2f}；下面的增量都是 {since} 起（G9 判这份） / 全段", flush=True)
    print("\n== 库里每条：拿掉它的方案，再加回去 ==", flush=True)
    for name, f in lib.items():
        g = scheme.gain(base({n: v for n, v in lib.items() if n != name}), name, f, params)
        r, s = g["recentSharpe"], g["sharpe"]
        print(f"  {name}: {r['base']:.2f} → {r['with']:.2f}（{r['gain']:+.2f}） / "
              f"{s['base']:.2f} → {s['with']:.2f}（{s['gain']:+.2f}）", flush=True)
    rng = np.random.default_rng(seed)
    first = store.load(pool.symbols[0])
    groups = {"库外候选": [(n, e) for e, n in candidates.items() if e not in {x for _, x in library}],
              "随机因子": [(f"rand{i:03d}", str(random_expression(rng, first))) for i in range(randoms)]}
    for label, items in groups.items():
        print(f"\n== {label}：{len(items)} 条 ==", flush=True)
        gains = []
        for name, expr in items:
            try:
                f = search(expr)
            except DslError as exc:
                print(f"  {name}：解析不了，跳过（{exc}）", flush=True)
                continue
            g = scheme.gain(full, name, f, params)
            gains.append((g["recentSharpe"]["gain"], g["sharpe"]["gain"]))
            print(f"  {gains[-1][0]:+.3f} / {gains[-1][1]:+.3f}  {name}  {expr}", flush=True)
        if gains:
            for k, which in enumerate((f"{since} 起", "全段")):
                xs = [x[k] for x in gains]
                q = np.quantile(xs, [0.5, 0.9, 0.95, 0.99])
                print(f"  {label}{which}增量：中位 {q[0]:+.3f}，90% {q[1]:+.3f}，95% {q[2]:+.3f}，99% {q[3]:+.3f}，"
                      f"最大 {max(xs):+.3f}（{len(xs)} 条）", flush=True)


# ========== 打印 ==========


def table(evals: list[dict[str, Any]]) -> str:
    """主档的数字"""
    head = (f"{'name':22s} " + " ".join(f"{g:>3s}" for g in GATE_IDS)
            + f" {'IC':>8s} {'ICIR':>7s} {'mono':>5s} {'spread':>8s} {'seg':>3s} {'perm':>6s} {'net':>8s}"
            + f" {'turn':>5s} {'be':>6s} {'red':>5s}  verdict")
    lines = [head, "-" * len(head)]
    for ev in evals:
        gates = {g["id"]: g for g in ev["gates"]}
        ic = ev["horizons"][ev["primary"]]["ic"]
        leg = ev["horizons"][ev["primary"]]["leg"]
        red = ev["redundancy"]["maxAbs"]
        lines.append(
            f"{ev['name'][:22]:22s} " + " ".join(f"{'Y' if gates[g]['passed'] else '.':>3s}" for g in GATE_IDS)
            + f" {ic['mean']:8.4f} {ic['icir']:7.2f} {ic['monotonic']['rho']:5.2f} {ic['monotonic']['spread']:8.5f}"
            + f" {gates['G3']['value']['sameSign']:3d} {leg.get('permPct') or 0:6.3f} {leg.get('netReturn', np.nan):8.3f}"
            + f" {leg.get('turnover', np.nan):5.2f} {leg.get('breakEvenBps', np.nan):6.1f}"
            + f" {red if red is not None else np.nan:5.2f}"
            + f"  {'PASS' if ev['passed'] else 'fail'}"
        )
    return "\n".join(lines)


def gate_counts(evals: list[dict[str, Any]]) -> str:
    counts = {g: sum(1 for ev in evals if next(x for x in ev["gates"] if x["id"] == g)["passed"]) for g in GATE_IDS}
    return "  ".join(f"{g}:{c}/{len(evals)}" for g, c in counts.items())


def _window_of(name: str) -> str:
    """Alpha158 名字末尾的窗口数字；KBAR 那 9 个没有窗口，返回空串"""
    return name[len(name.rstrip("0123456789")):]


# ========== 入口 ==========


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="因子硬门校准（截面）")
    ap.add_argument("--synthetic", action="store_true", help="用合成币池，不读 data 目录")
    ap.add_argument("--coins", type=int, default=80)
    ap.add_argument("--days", type=int, default=730)
    ap.add_argument("--beta", type=float, default=2e-5, help="植入信号：每根 bar 收益加 β × 费率截面 z")
    ap.add_argument("--random", type=int, default=100, help="随机因子条数")
    ap.add_argument("--k", type=int, default=3, help="同批 BH 的批大小，按生产一轮的候选数")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--data-dir", default="./data")
    ap.add_argument("--db", default="./data/alpha.sqlite", help="真数据模式读池子表的 SQLite")
    ap.add_argument("--skip-alpha158", action="store_true", help="只跑 5 个基线，不跑 Alpha158")
    ap.add_argument("--alpha158-windows", default="", help="只跑这些窗口的 Alpha158，逗号分隔；空 = 全部")
    ap.add_argument("--g9", type=int, default=0, help="真数据：量 G9 阈值，随机因子这么多条；只跑这一项")
    args = ap.parse_args(argv)

    params, thresholds = EvalParams(), Thresholds()
    print(f"参数 {params}\n阈值 {thresholds}")
    if args.g9:
        g9(PanelStore(args.data_dir), args.db, params, args.g9, args.seed)
        return 0
    # 合成币池落在临时目录，跑完删掉
    with tempfile.TemporaryDirectory(prefix="wiib-calibrate-") if args.synthetic else nullcontext(args.data_dir) as root:
        t0 = time.perf_counter()
        if args.synthetic:
            store, universe = synthetic_store(root, args.coins, args.days, args.seed, args.beta)
        else:
            store, universe = PanelStore(root), real_universe(args.db)
        pool = load_pool(store, universe, params.holdout_days)
        print(f"币池 {pool.describe()}，{time.perf_counter() - t0:.1f}s", flush=True)

        ref_exprs = reference.parsed()
        handwritten = {**reference.BASELINES, **reference.KNOWN_EFFECTS}
        if args.skip_alpha158:
            ref_exprs = {k: v for k, v in ref_exprs.items() if k in handwritten}
        elif args.alpha158_windows:
            keep = {w.strip() for w in args.alpha158_windows.split(",") if w.strip()}
            ref_exprs = {k: v for k, v in ref_exprs.items() if k in handwritten or _window_of(k) in keep | {""}}
        t0 = time.perf_counter()
        saved = None if args.synthetic else reference.load_grid(store)
        grid = reference.align(saved if saved is not None
                               else reference.grid(store, pool.symbols, {"done": 0}, ref_exprs), pool)
        known = {e.canonical() for e in ref_exprs.values()}
        print(f"参照网格 {len(grid.names)} 条，{time.perf_counter() - t0:.1f}s", flush=True)

        # 库是空的：G9 的基线是只有基差 carry 的交易方案（份额真数据按线上设的，合成币池按默认）；
        # 参照集自己不查冗余、不做 BH：它们是尺子不是候选
        vol, basis = pool.values(scheme.VOL).f, scheme.carry_signal(pool)
        empty = scheme.Base.of(Combo.empty(pool.search, pool.symbols), basis[pool.in_search], vol[pool.in_search],
                               params, scheme.CARRY_SHARE if args.synthetic else carry_share(args.db))
        ref_evals = run("参照集", ref_exprs, pool, vol, params, thresholds, reference.Grid.empty(), set(), empty)
        baselines = [ev for ev in ref_evals if ev["name"] in handwritten]
        alpha = [ev for ev in ref_evals if ev["name"] not in handwritten]

        rng = np.random.default_rng(args.seed)
        first = store.load(pool.symbols[0])
        random_exprs = {f"rand{i:03d}": random_expression(rng, first) for i in range(args.random)}
        random_evals = run("随机因子", random_exprs, pool, vol, params, thresholds, grid, known, empty)
        single_passes = sum(ev["passed"] for ev in random_evals)
        # 同批 BH 按生产的 K 分批做，和一轮的候选数一样
        for start in range(0, len(random_evals), args.k):
            apply_fdr(random_evals[start:start + args.k], thresholds.fdr_q, params.iterations)

        ops.OPERATORS["Lead"] = ops.Op("rolling", lambda x, n: x.shift(-n))
        try:
            leaky_exprs = {k: parse(v, FEATURE_COLUMNS) for k, v in LEAKY.items()}
            leaky_evals = run("泄漏因子", leaky_exprs, pool, vol, params, thresholds, grid, known, empty)
        finally:
            del ops.OPERATORS["Lead"]
        planted_evals = []
        if args.synthetic:
            planted_evals = run("植入因子", {k: parse(v, FEATURE_COLUMNS) for k, v in PLANTED.items()},
                                pool, vol, params, thresholds, grid, known, empty)

    print("\n===== 基线与老效应 =====")
    print(table(baselines))
    if alpha:
        print(f"\n===== Alpha158 参照集（{len(alpha)} 条）=====")
        print("各门通过数：" + gate_counts(alpha))
        survivors = [ev for ev in alpha if ev["passed"]]
        print(f"全过的 {len(survivors)} 条：" + ", ".join(ev["name"] for ev in survivors))
        print(table(sorted(alpha, key=lambda e: -abs(e["horizons"][params.primary]["ic"]["mean"]))[:15]))
        print("（按主档 |IC| 排的前 15 条）")
    print("\n===== 随机因子 =====")
    print("各门通过数：" + gate_counts(random_evals))
    print(table(random_evals))
    for ev in random_evals:
        print(f"  {ev['name']}: {ev['expr']}")
    print("\n===== 泄漏因子 =====")
    print(table(leaky_evals))
    if planted_evals:
        print("\n===== 植入因子 =====")
        print(table(planted_evals))
        for g in planted_evals[0]["gates"]:
            print(f"  {g['id']} {'过' if g['passed'] else '不过'}  {g['note']}")

    every = ref_evals + random_evals + leaky_evals + planted_evals
    print(f"\n单条评估耗时：均值 {np.mean([e['seconds'] for e in every]):.1f}s，最长 {max(e['seconds'] for e in every):.1f}s")
    n = max(1, len(random_evals))
    single_rate = single_passes / n
    batch_rate = sum(ev["passed"] for ev in random_evals) / n
    g2_rate = sum(1 for ev in random_evals if next(g for g in ev["gates"] if g["id"] == "G2")["passed"]) / n
    caught = sum(1 for ev in leaky_evals if not ev["lookaheadFree"]) / max(1, len(leaky_evals))
    planted_ok = all(ev["passed"] for ev in planted_evals)
    print(f"随机因子：单条通过率 {single_rate:.0%}（要求 ≤ 10%）  G2 单项通过率 {g2_rate:.0%}  "
          f"按 K={args.k} 同批 BH 后 {batch_rate:.0%}")
    print(f"泄漏被 G0 抓住 {caught:.0%}（要求 100%）")
    if planted_evals:
        print(f"植入因子：{'过门' if planted_ok else '没过门'}")
    ok = single_rate <= 0.10 and caught == 1.0 and planted_ok
    print("校准结论：" + ("门在工作" if ok else "门有问题，别接 LLM"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
