import os

import numpy as np
import pandas as pd
import pytest

from wiib_alpha.data import panel
from wiib_alpha.data.binance import BAR_MS, KLINE_COLUMNS, METRICS_COLUMNS

DAY = panel.DAY_MS
N = 3 * 96  # 三天
# 2026-01-30 00:00 UTC：三天跨 1 月和 2 月
BASE = 1_769_731_200_000


def klines(n: int, base_price: float) -> pd.DataFrame:
    rows = [[BASE + k * BAR_MS, base_price, base_price + 1, base_price - 1, base_price + k, 10.0, close_time(k),
             1000.0, 7, 4.0, 400.0] for k in range(n)]
    return pd.DataFrame(rows, columns=KLINE_COLUMNS)


def close_time(k: int) -> int:
    return BASE + (k + 1) * BAR_MS - 1


def premium_frame() -> pd.DataFrame:
    """每根一条，第 7 根缺"""
    ks = [k for k in range(N) if k != 7]
    return pd.DataFrame({"close_time": [close_time(k) for k in ks], "premium": [0.0001 * k for k in ks]})


def metrics_frame(until: int) -> pd.DataFrame:
    """每小时一条，落在整点 bar 收盘前 1 分钟，故意倒序；oi = 第几根 + 1（0 是坏数）"""
    ks = list(range(0, until, 4))[::-1]
    return pd.DataFrame({"time": [close_time(k) - 60_000 for k in ks], "oi": [k + 1.0 for k in ks],
                         "oi_value": [(k + 1) * 10.0 for k in ks],
                         **{c: 1.0 for c in METRICS_COLUMNS[3:]}})


def build(with_liq: bool = True, **extra) -> pd.DataFrame:
    alt, btc = klines(N, 100.0), klines(N, 1000.0)
    funding = pd.DataFrame({"funding_time": [close_time(2), close_time(50)], "funding_rate": [0.0001, -0.0002]})
    if not with_liq:
        return panel.assemble(alt, funding, btc, **extra)
    # 第 0 天有强平、第 1 天整天的桶都判成断档、第 2 天又有
    liq = pd.DataFrame({"symbol": ["X"] * 3, "close_time": [close_time(3), close_time(5), close_time(2 * 96 + 1)],
                        "liq_long": [5.0, 0.0, 1.0], "liq_short": [0.0, 2.0, 0.0], "liq_count": [1, 1, 1]})
    outage = frozenset(close_time(k) for k in range(96, 192))
    return panel.assemble(alt, funding, btc, liq, liq_start=close_time(3), outage=outage, **extra)


def test_index_and_returns():
    df = build(with_liq=False)
    assert df.index.name == "close_time" and df.index[0] == close_time(0)
    assert np.isnan(df["ret"].iloc[0])
    assert df["ret"].iloc[1] == np.log(101.0 / 100.0)
    # bar 开始时刻的小时：00:45~01:00 那根还是 0 点，23:45~24:00 是 23 点
    assert df["hour"].iloc[0] == 0 and df["hour"].iloc[3] == 0 and df["hour"].iloc[4] == 1 and df["hour"].iloc[95] == 23
    assert panel.describe(df)["gaps"] == 0


def test_funding_is_last_settled_at_close():
    df = build(with_liq=False)
    assert np.isnan(df["funding_rate"].iloc[1])
    assert df["funding_rate"].iloc[2] == 0.0001 and df["funding_rate"].iloc[49] == 0.0001
    assert df["funding_rate"].iloc[50] == -0.0002
    # 离上次结算超过 12 小时还没新的，是数据断了，不再硬填
    assert df["funding_rate"].iloc[98] == -0.0002 and np.isnan(df["funding_rate"].iloc[99])


def test_funding_is_converted_to_per_8h():
    """4 小时结算的币每期费率是 8 小时币的一半（同样的日成本），折算后一样；第一条和断档按 8 小时"""
    alt, btc = klines(N, 100.0), klines(N, 1000.0)
    h, t0 = 3_600_000, close_time(2)
    eight = pd.DataFrame({"funding_time": [t0, t0 + 8 * h, t0 + 16 * h], "funding_rate": [0.0002] * 3})
    four = pd.DataFrame({"funding_time": [t0 + 4 * h * k for k in range(5)], "funding_rate": [0.0001] * 5})
    a, b = panel.assemble(alt, eight, btc), panel.assemble(alt, four, btc)
    # 第一条没有上一次结算，按 8 小时算：4 小时币原样
    assert a["funding_rate"].iloc[2] == 0.0002 and b["funding_rate"].iloc[2] == 0.0001
    # 之后 4 小时币每期 0.0001 折成每 8 小时 0.0002，和 8 小时币一样
    assert a["funding_rate"].iloc[34] == pytest.approx(0.0002) and b["funding_rate"].iloc[18] == pytest.approx(0.0002)
    assert b["funding_rate"].iloc[66] == pytest.approx(0.0002) == a["funding_rate"].iloc[66]
    # 断档：隔了 24 小时才有下一条，按 8 小时算，不放大
    gap = pd.DataFrame({"funding_time": [t0, t0 + 24 * h], "funding_rate": [0.0001, 0.0001]})
    assert panel.assemble(alt, gap, btc)["funding_rate"].iloc[98] == 0.0001


def test_btc_aligned_by_close_time():
    df = build(with_liq=False)
    assert df["btc_close"].iloc[4] == 1004.0
    assert df["btc_ret"].iloc[4] == np.log(1004.0 / 1003.0)


def test_targets_enter_one_bar_late_and_look_forward_only():
    df = build(with_liq=False)
    # 第 0 根出信号、第 1 根收盘建仓、持有 24 根
    assert df["fwd_ret_24"].iloc[0] == np.log(125.0 / 101.0)
    assert df["fwd_ret_96"].iloc[N - 98] == np.log((100.0 + N - 1) / (100.0 + N - 97))
    assert df["fwd_ret_96"].iloc[N - 97:].isna().all()


def test_liquidation_zero_vs_nan():
    df = build()
    assert np.isnan(df["liq_long"].iloc[2])          # 数据开始前
    assert df["liq_long"].iloc[3] == 5.0 and df["liq_short"].iloc[5] == 2.0
    assert df["liq_long"].iloc[4] == 0.0 and df["liq_count"].iloc[4] == 0.0   # 有数据的日子没强平
    assert df["liq_count"].iloc[96:192].isna().all()  # 断掉的那天
    assert df["liq_long"].iloc[2 * 96 + 1] == 1.0 and df["liq_long"].iloc[2 * 96 + 2] == 0.0


def test_no_liq_source_is_all_nan():
    df = build(with_liq=False)
    assert df[panel.LIQ_COLUMNS].isna().all().all()


def test_premium_exact_by_close_time():
    df = build(with_liq=False, premium=premium_frame())
    assert df["premium"].iloc[3] == pytest.approx(0.0003) and np.isnan(df["premium"].iloc[7])


def test_metrics_asof_and_stale_after_an_hour():
    df = build(with_liq=False, metrics=metrics_frame(until=48))
    # 整点那条落在收盘前一分钟：这根和后面三根都拿它
    assert df["oi"].iloc[0] == 1.0 and df["oi"].iloc[3] == 1.0 and df["oi"].iloc[4] == 5.0
    assert df["oi_value"].iloc[47] == 450.0
    # 最后一条在第 44 根，第 47 根离它 46 分钟还算新，第 48 根 61 分钟就断了
    assert df["ls_acct"].iloc[47] == 1.0 and np.isnan(df["oi"].iloc[48]) and np.isnan(df["ls_acct"].iloc[48])


def test_metrics_zero_is_missing_per_column():
    m = metrics_frame(until=48)
    # 第 8 根那条 oi 是 0（归档的坏数）：oi 当没有，上一条有效的离第 8 根 61 分钟已经断了；同一条的别的列照常
    m.loc[m["time"] == close_time(8) - 60_000, "oi"] = 0.0
    df = build(with_liq=False, metrics=m)
    assert df["oi"].iloc[7] == 5.0 and np.isnan(df["oi"].iloc[8]) and df["oi"].iloc[12] == 13.0
    assert df["oi_value"].iloc[8] == 90.0 and df["ls_acct"].iloc[8] == 1.0


def test_no_premium_or_metrics_is_all_nan():
    df = build(with_liq=False)
    assert df["premium"].isna().all() and df[panel.METRIC_FEATURES].isna().all().all()


def test_in_pool_by_month_of_close_time():
    df = build(with_liq=False, pool_months=frozenset({"2026-02"}))
    # 1 月 30、31 日两天 192 根不在池，2 月 1 日那天在
    assert (df[panel.POOL_COLUMN].iloc[:192] == 0.0).all() and (df[panel.POOL_COLUMN].iloc[192:] == 1.0).all()
    assert panel.describe(df)["poolMonths"] == 1
    assert panel.POOL_COLUMN not in panel.FEATURE_COLUMNS


def test_describe_coverage():
    df = build(premium=premium_frame(), metrics=metrics_frame(until=48))
    cov = panel.describe(df)["coverage"]
    assert cov["premium"] == pytest.approx((N - 1) / N, abs=1e-4) and 0 < cov["oi"] < 1 and 0 < cov["liq_count"] < 1
    assert cov["funding_rate"] < 1 and cov["spot_quote_volume"] == 0.0


def test_spot_exact_by_close_time_and_nan_where_there_is_no_spot_bar():
    # 现货比永续晚 10 根上市，第 12 根缺
    spot = klines(N, 50.0).iloc[10:]
    spot = spot[spot["close_time"] != close_time(12)].copy()
    spot["quote_volume"] = np.arange(1, len(spot) + 1, dtype="float64")
    df = build(with_liq=False, spot=spot)
    assert df["spot_quote_volume"].iloc[:10].isna().all() and df["spot_quote_volume"].iloc[10] == 1.0
    assert np.isnan(df.loc[close_time(12), "spot_quote_volume"]) and df.loc[close_time(13), "spot_quote_volume"] == 3.0
    assert df["spot_taker_buy_quote"].iloc[10] == 400.0 and df["spot_trades"].iloc[10] == 7.0
    desc = panel.describe(df)
    assert desc["spotFrom"] == close_time(10) and desc["coverage"]["spot_quote_volume"] == round((N - 11) / N, 4)
    # 没有现货对：三列全空
    none = build(with_liq=False)
    assert none[panel.SPOT_COLUMNS].isna().all().all() and panel.describe(none)["spotFrom"] is None


def test_describe_counts_index_gaps():
    df = build(with_liq=False)
    holed = df.drop(df.index[[10, 11, 50]])
    assert panel.describe(holed)["gaps"] == 2


def test_dead_stretch_dropped_and_gap_returns_nan():
    alt, btc = klines(N, 100.0), klines(N, 1000.0)
    alt.loc[10:14, "trades"] = 0      # 5 根零成交，不到一天，留着
    alt.loc[100:219, "trades"] = 0    # 120 根零成交，整段扔
    funding = pd.DataFrame({"funding_time": [close_time(2)], "funding_rate": [0.0001]})
    df = panel.assemble(alt, funding, btc)
    assert len(df) == N - 120 and close_time(10) in df.index and close_time(100) not in df.index
    # 缺口后第一根的涨跌幅跨了缺口，不算；再往后照常
    after = df.index.get_loc(close_time(220))
    assert np.isnan(df["ret"].iloc[after]) and np.isnan(df["btc_ret"].iloc[after])
    assert df["ret"].iloc[after + 1] == np.log(321.0 / 320.0)
    # 缺口前 25 根之内（晚一根建仓、持有 24 根）的 6 小时标准答案跨了缺口，不算；再往前的照常
    before = df.index.get_loc(close_time(99))
    assert df["fwd_ret_24"].iloc[before - 24:before + 1].isna().all()
    assert df["fwd_ret_24"].iloc[before - 25] == np.log(199.0 / 175.0)
    assert panel.describe(df)["gaps"] == 1


def test_holdout_cut_and_truncate():
    df = build(with_liq=False)
    cut = panel.holdout_cut({"X": df, "Y": df.iloc[:-5]}, 1)
    assert cut == close_time(N - 1) - DAY
    search = panel.truncate(df, cut)
    assert search.index.max() <= cut and len(search) == 2 * 96
    # 搜索段末尾的答案在截断处重算：最后 97 根的 24h 答案看不到 holdout 的价格
    assert search["fwd_ret_96"].iloc[-97:].isna().all() and df["fwd_ret_96"].iloc[len(search) - 2] > 0
    assert search["fwd_ret_24"].iloc[0] == df["fwd_ret_24"].iloc[0]
    assert set(search.columns) == set(df.columns)


def test_feature_columns_exist_and_targets_excluded():
    df = build()
    assert set(panel.FEATURE_COLUMNS) <= set(df.columns)
    assert not set(panel.TARGET_COLUMNS) & set(panel.FEATURE_COLUMNS)


def test_store_grows_incrementally_and_leaves_no_tmp(tmp_path):
    store = panel.PanelStore(tmp_path)
    calls = []

    def fetch(symbol, start, end):
        calls.append(start)
        k0 = max(0, (start - BASE) // BAR_MS)
        return klines(N, 100.0).iloc[k0:]

    now = close_time(N - 1) + 1
    first = store.grow("klines_15m", "X", fetch, BASE, now)
    again = store.grow("klines_15m", "X", fetch, BASE, now)
    # 第二次从最后一根之后接着拉，对面没新的就原样保留
    assert calls == [BASE, now] and len(first) == N and len(again) == N
    assert (tmp_path / "raw" / "klines_15m" / "X.parquet").exists()
    assert not list((tmp_path / "raw" / "klines_15m").glob("*.tmp"))


def test_panel_and_status_roundtrip(tmp_path):
    store = panel.PanelStore(tmp_path)
    assert store.read_status() == {} and not store.has("X")
    store.save_panel("X", build(with_liq=False))
    store.write_status({"X": {"rows": N}})
    assert store.has("X") and len(store.load("X")) == N and store.read_status() == {"X": {"rows": N}}
    # 按列读：index 照样带着
    part = store.load("X", ["close", panel.POOL_COLUMN])
    assert list(part.columns) == ["close", panel.POOL_COLUMN] and part.index.name == "close_time" and len(part) == N


def test_reference_grid_older_than_status_is_stale(tmp_path):
    store = panel.PanelStore(tmp_path)
    assert store.load_reference_grid() is None
    grid = pd.DataFrame({"t": [1, 2], "symbol": ["X", "X"], "MA4": np.array([0.5, 1.0], dtype="float32")})
    store.write_status({"X": {"rows": N}})
    store.save_reference_grid(grid)
    pd.testing.assert_frame_equal(store.load_reference_grid(), grid)
    # 之后又刷新过面板：网格作废，等重算
    status = store.panels / "status.json"
    later = (store.panels / panel.REFERENCE_GRID).stat().st_mtime + 5
    os.utime(status, (later, later))
    assert store.load_reference_grid() is None
