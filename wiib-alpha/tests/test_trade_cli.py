import sqlite3
import sys

import pytest
from conftest import planted_universe
from fakes import FakeFapi

from wiib_alpha.config import config
from wiib_alpha.data import binance as b
from wiib_alpha.data.binance import DAY_MS
from wiib_alpha.data.panel import PanelStore
from wiib_alpha.eval.params import EvalParams
from wiib_alpha.store import SCHEMA
from wiib_alpha.trade import check, live, scheme

DAY0 = 1_790_812_800_000  # 2026-10-01 00:00 UTC
SYMBOLS = [f"C{i:02d}" for i in range(21)]


@pytest.fixture(autouse=True)
def no_rest_gap(monkeypatch):
    monkeypatch.setattr(b, "REST_GAP_S", 0.0)
    monkeypatch.setattr(b, "DATA_GAP_S", 0.0)
    monkeypatch.setattr(b, "SPOT_GAP_S", 0.0)
    yield
    config.cache_clear()


def write_db(path, universe, factors) -> None:
    """池子表、因子库与交易员勾选；factors 是 (id, 名字, 表达式)，全勾上"""
    conn = sqlite3.connect(path)
    for statement in SCHEMA:
        conn.execute(statement)
    conn.executemany("INSERT INTO universe(month, symbol, rank, avg_quote_volume) VALUES (?, ?, ?, ?)", universe)
    for fid, name, expr in factors:
        conn.execute("INSERT INTO factor(id, name, hypothesis, expr, canonical, columns_json, eval_json, gates_json, "
                     "approved_at, run_id) VALUES (?, ?, 'h', ?, '', '[]', '{}', '[]', 1, 'r')", (fid, name, expr))
        conn.execute("INSERT INTO trade_factor(factor_id, selected_at) VALUES (?, 1)", (fid,))
    conn.commit()
    conn.close()


def run(monkeypatch, capsys, module, *argv) -> tuple[int, str]:
    monkeypatch.setattr(sys, "argv", [module.__name__, *argv])
    code = 0
    try:
        module.main()
    except SystemExit as exc:
        code = exc.code
    return code, capsys.readouterr().out


@pytest.fixture
def overlap(tmp_path, monkeypatch):
    """归档到 10-03；交易员 10-30 补了最近 29 天的 live：和归档重叠两天（重叠得少，核对跑得快）"""
    store = PanelStore(tmp_path)
    fake = FakeFapi([*SYMBOLS, "BTCUSDT"], DAY0 + 29 * DAY_MS + 20 * 60_000)
    fake.write_raw(store, DAY0 - 12 * DAY_MS, DAY0 + 2 * DAY_MS)
    live.update(store, fake.binance(), [*SYMBOLS, "BTCUSDT"], fake.now, {})
    write_db(tmp_path / "alpha.sqlite", [("2026-10", s, i + 1, 1e9) for i, s in enumerate(SYMBOLS)],
             [("f1", "vol4", "Mean($volume, 4)")])
    monkeypatch.setenv("ALPHA_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("ALPHA_DB_PATH", str(tmp_path / "alpha.sqlite"))
    config.cache_clear()
    return store, fake


def test_check_passes_when_live_matches_the_archive_and_names_what_differs(overlap, monkeypatch, capsys):
    store, fake = overlap
    code, out = run(monkeypatch, capsys, check)
    assert code == 0 and out.rstrip().endswith("结论：通过"), out
    # 重叠两天，每个 UTC 0 点一行，勾选的因子、basis_7d、波动都比
    assert out.count("vol4 1.0000（21）") == 2 and "basis_7d 1.0000" in out and "Std($ret, 672) 1.0000" in out
    # live 里一根 K 线的收盘价和归档不一样：K 线那项不过，例子里点名
    tail = store.load_live("klines_15m", "C03")
    tail.loc[tail.index[10], "close"] += 1.0
    store.save_live("klines_15m", "C03", tail)
    code, out = run(monkeypatch, capsys, check)
    assert code == 1 and "结论：不通过" in out and "例：C03 2026-10-" in out and "close：raw" in out


def test_check_can_fetch_its_own_overlap(overlap, monkeypatch, capsys):
    store, fake = overlap
    fake.log.clear()
    monkeypatch.setattr(check, "Binance", lambda *a, **k: fake.binance())
    code, out = run(monkeypatch, capsys, check, "--fetch", "2")
    assert code == 0 and "fapi 现拉归档最后 2 天" in out, out
    # 不写盘：live/ 还是交易员补的那份
    assert {p["symbol"] for _, p in fake.log if "symbol" in p} == {*SYMBOLS, "BTCUSDT"}


def test_live_warms_up_the_latest_pool_that_is_trading(tmp_path, monkeypatch, capsys):
    store = PanelStore(tmp_path)
    fake = FakeFapi(["C00", "C01", "C02", "BTCUSDT"], DAY0 + 22 * DAY_MS + 20 * 60_000)
    fake.halted.add("C02")
    write_db(tmp_path / "alpha.sqlite", [("2026-09", "C00", 1, 1e9), ("2026-10", "C01", 1, 1e9),
                                         ("2026-10", "C02", 2, 1e9)], [])
    monkeypatch.setenv("ALPHA_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("ALPHA_DB_PATH", str(tmp_path / "alpha.sqlite"))
    config.cache_clear()
    monkeypatch.setattr(live, "Binance", lambda *a, **k: fake.binance())
    monkeypatch.setattr(live, "now_ms", lambda: fake.now)
    code, out = run(monkeypatch, capsys, live)
    # 只补最新一个月里在交易的币和 BTC
    assert code == 0 and "live 补好了：2 个币" in out, out
    assert {p["symbol"] for _, p in fake.log if "symbol" in p} == {"C01", "BTCUSDT"}
    assert store.load_live("metrics", "C01")["time"].min() == fake.now - 29 * DAY_MS


def test_scheme_prints_search_and_holdout(tmp_path, monkeypatch, capsys, planted_store, planted_panels):
    universe = planted_universe(planted_panels)
    write_db(tmp_path / "alpha.sqlite", list(universe.itertuples(index=False)), [("f1", "planted", "Mean($volume, 4)")])
    monkeypatch.setenv("ALPHA_DATA_DIR", str(planted_store.root))
    monkeypatch.setenv("ALPHA_DB_PATH", str(tmp_path / "alpha.sqlite"))
    monkeypatch.setenv("ALPHA_SYMBOLS", "AAA,BBB,CCC")
    config.cache_clear()
    # 埋的数据只有 62 天：holdout 锁 10 天、训练窗缩短，和接口测试同一套
    monkeypatch.setattr(scheme, "EvalParams", lambda **kw: EvalParams(**kw, test_size=96, embargo=4, min_train=500,
                                                                     holdout_days=10))
    code, out = run(monkeypatch, capsys, scheme)
    lines = out.strip().splitlines()
    assert code == 0 and lines[0] == "因子：planted；carry 腿占 25%；晚 1 根成交", out
    assert lines[1].startswith("搜索段（walk-forward 样本外）：净收益 +") and "Sharpe" in lines[1] and "最大回撤" in lines[1]
    assert lines[2].startswith("holdout（2025-02-22") and "carry 资金费每期" in lines[2]
