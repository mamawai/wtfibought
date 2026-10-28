from datetime import datetime, timezone

import numpy as np
import pandas as pd
import pytest

from wiib_alpha import reference
from wiib_alpha.data import refresh as r
from wiib_alpha.data.binance import BAR_MS, DAY_MS, KLINE_COLUMNS, METRICS_COLUMNS
from wiib_alpha.data.panel import POOL_COLUMN, PanelStore
from wiib_alpha.dsl import parse


def ms(y: int, m: int, d: int) -> int:
    return int(datetime(y, m, d, tzinfo=timezone.utc).timestamp() * 1000)


FROM = ms(2026, 1, 1)
NOW = ms(2026, 6, 1)
LIVE = ms(2027, 1, 1)   # 还在交易的币：数据一直有
PRODUCT = ("BTCUSDT", "ETHUSDT")
# 符号：上市日、最后一天、日均成交额
SYMBOLS = {
    "BTCUSDT": (ms(2025, 1, 1), LIVE, 5e9),
    "ETHUSDT": (ms(2025, 1, 1), LIVE, 5e7),      # 产品币但成交额不够，不进池
    "AAAUSDT": (ms(2025, 1, 1), LIVE, 2e8),
    "DEADUSDT": (ms(2025, 1, 1), ms(2026, 3, 15), 3e8),
    "NEWBUSDT": (ms(2026, 3, 1), LIVE, 4e8),
    "USDCUSDT": (ms(2025, 1, 1), LIVE, 9e9),     # 名单排除
    "TSLAUSDT": (ms(2025, 1, 1), LIVE, 9e9),     # exchangeInfo 排除
    "BTCUSDC": (ms(2025, 1, 1), LIVE, 9e9),      # 不是 USDT
}
# 现货归档里有的对
SPOT = {"BTCUSDT", "ETHUSDT", "NEWBUSDT", "BTCUSDC"}


class FakeBinance:
    def __init__(self):
        self.calls: list[tuple[str, str, int]] = []
        # 费率拉取时 refresh 说这个币活不活
        self.live: dict[str, bool] = {}

    def symbols(self):
        return sorted(SYMBOLS)

    def spot_symbols(self):
        return sorted(SPOT)

    def exchange_info(self):
        return {"TSLAUSDT": {"underlyingType": "STOCK"}, "BTCUSDT": {"underlyingType": "COIN"}}

    def _range(self, symbol, start, end, step):
        first, last, _ = SYMBOLS[symbol]
        a = -(-max(start, first) // step) * step
        return np.arange(a, min(end, last), step, dtype="int64")

    def klines(self, symbol, start, end, interval="15m"):
        self.calls.append((symbol, interval, start))
        return self._bars(symbol, start, end, DAY_MS if interval == "1d" else BAR_MS, SYMBOLS[symbol][2])

    def spot_klines(self, symbol, start, end):
        self.calls.append((symbol, "spot", start))
        # 现货成交额是永续的三成
        return self._bars(symbol, start, end, BAR_MS, SYMBOLS[symbol][2] * 0.3)

    def _bars(self, symbol, start, end, step, quote):
        t = self._range(symbol, start, end, step)
        price = 100.0 + (t // BAR_MS % 50)
        return pd.DataFrame({"open_time": t, "open": price, "high": price + 1, "low": price - 1, "close": price,
                             "volume": 1.0, "close_time": t + step - 1, "quote_volume": quote, "trades": 1,
                             "taker_buy_base": 0.5, "taker_buy_quote": 50.0})[KLINE_COLUMNS]

    def funding(self, symbol, start, end, live=False):
        self.live[symbol] = live
        t = self._range(symbol, start, end, 8 * 3_600_000)
        return pd.DataFrame({"funding_time": t, "funding_rate": np.full(len(t), 0.0001)})

    def premium(self, symbol, start, end):
        t = self._range(symbol, start, end, BAR_MS)
        return pd.DataFrame({"close_time": t + BAR_MS - 1, "premium": np.full(len(t), 0.0002)})

    def metrics(self, symbol, start, end):
        t = self._range(symbol, start, end, 3_600_000)
        return pd.DataFrame({"time": t, **{c: np.full(len(t), 1.0) for c in METRICS_COLUMNS[1:]}})


@pytest.fixture(autouse=True)
def few_references(monkeypatch):
    """参照网格只算两条，159 条在这里太慢"""
    monkeypatch.setattr(reference, "parsed", lambda: {"ret4": parse("Sum($ret, 4)", ["ret"]),
                                                      "vol": parse("$volume", ["volume"])})


def run(store: PanelStore, api: FakeBinance, now_ms: int, progress: dict | None = None) -> r.RefreshResult:
    return r.refresh(store, api, history_from=FROM, product=PRODUCT, universe_size=2, min_volume=1e8,
                     exclude=("USDCUSDT",), progress={} if progress is None else progress, now_ms=now_ms)


def test_refresh_builds_pool_and_panels(tmp_path):
    old, api, progress = PanelStore(tmp_path), FakeBinance(), {}
    result = run(old, api, NOW, progress)
    # 面板写进新的一版并换成当前版；刷新前打开的还读原来那版
    store = PanelStore(tmp_path)
    assert store.panels != old.panels and not old.has("BTCUSDT")

    table = result.universe
    by_month = {m: g.sort_values("rank")["symbol"].tolist() for m, g in table.groupby("month")}
    assert by_month["2026-01"] == ["BTCUSDT", "DEADUSDT"] and by_month["2026-04"] == ["BTCUSDT", "AAAUSDT"]
    assert by_month["2026-06"] == ["BTCUSDT", "NEWBUSDT"]
    assert not {"USDCUSDT", "TSLAUSDT", "BTCUSDC", "ETHUSDT"} & set(table["symbol"])
    # 排除掉的连日线都不拉；日线从起点再往前 90 天拉，15m 从起点拉
    assert not any(s in ("USDCUSDT", "TSLAUSDT", "BTCUSDC") for s, _, _ in api.calls)
    assert ("BTCUSDT", "1d", FROM - 90 * DAY_MS) in api.calls and ("BTCUSDT", "15m", FROM) in api.calls
    # 费率尾巴：exchangeInfo 里有的才算活
    assert api.live["BTCUSDT"] and not api.live["AAAUSDT"]

    # 面板：进过池的 + 产品币
    members = ["AAAUSDT", "BTCUSDT", "DEADUSDT", "ETHUSDT", "NEWBUSDT"]
    assert sorted(result.status) == members
    assert sorted(p.stem for p in store.panels.glob("*USDT.parquet")) == members
    assert store.read_status() == result.status
    dead = store.load("DEADUSDT")
    months = pd.to_datetime(dead.index, unit="ms", utc=True).strftime("%Y-%m")
    assert set(months[dead[POOL_COLUMN] == 1.0]) == {"2026-01", "2026-02", "2026-03"}
    assert (store.load("ETHUSDT")[POOL_COLUMN] == 0.0).all()
    btc = store.load("BTCUSDT")
    assert btc[["btc_close", "btc_ret"]].isna().all().all() and store.load("AAAUSDT")["btc_close"].notna().all()
    assert btc["premium"].notna().all() and btc["oi"].notna().sum() > 0 and btc["liq_count"].isna().all()
    # 榜上有 6 个月，但 NOW 是 6 月 1 日零点，面板里还没有 6 月收盘的 bar
    assert result.status["BTCUSDT"]["poolMonths"] == 5 and result.status["ETHUSDT"]["coverage"]["premium"] == 1.0
    # 现货：对上的币拉现货 K 线、三列有数；对不上的不拉、不建 raw 文件、三列空；映射写进 status，没对上的名单在进度里
    assert ("BTCUSDT", "spot", FROM) in api.calls and not any(s == "AAAUSDT" and i == "spot" for s, i, _ in api.calls)
    assert btc["spot_quote_volume"].eq(5e9 * 0.3).all() and store.load("AAAUSDT")["spot_quote_volume"].isna().all()
    assert (store.raw / "spot_15m" / "BTCUSDT.parquet").exists() and not (store.raw / "spot_15m" / "AAAUSDT.parquet").exists()
    assert result.status["BTCUSDT"]["spotSymbol"] == "BTCUSDT" and result.status["AAAUSDT"]["spotSymbol"] is None
    assert result.status["BTCUSDT"]["spotFrom"] == FROM + BAR_MS - 1 and result.status["AAAUSDT"]["spotFrom"] is None
    assert progress["spotMissing"] == ["AAAUSDT", "DEADUSDT"]
    # 收尾算参照网格：只算进过池的 4 个币，写在 status.json 之后
    assert progress["stage"] == "reference" and progress["done"] == progress["total"] == 4 and progress["symbol"] is None
    grid = store.load_reference_grid()
    # NEWB 6 月才进池，面板里还没有 6 月的 bar，网格里没有它的行
    assert grid is not None and set(grid["symbol"]) == {"AAAUSDT", "BTCUSDT", "DEADUSDT"}
    assert list(grid.columns) == ["t", "symbol", "ret4", "vol"]


def test_second_refresh_is_incremental(tmp_path):
    api = FakeBinance()
    run(PanelStore(tmp_path), api, NOW)
    first = PanelStore(tmp_path)
    (tmp_path / "panel-backup").mkdir()
    api.calls.clear()
    run(first, api, NOW + DAY_MS)
    starts = {(s, i): start for s, i, start in api.calls}
    # 15m 按 close_time 接：最后一根收在 NOW − 1；日线按 open_time 接：最后一根开在 NOW − 1 天
    assert starts[("BTCUSDT", "15m")] == NOW and starts[("BTCUSDT", "1d")] == NOW - DAY_MS + 1
    assert starts[("BTCUSDT", "spot")] == NOW
    store = PanelStore(tmp_path)
    assert len(store.load("BTCUSDT")) == (NOW + DAY_MS - FROM) // BAR_MS
    # 上一版留着（刷新时可能还有评估在读），再早的那版删了；不是自己建的目录不碰
    assert len(first.load("BTCUSDT")) == (NOW - FROM) // BAR_MS
    assert sorted(d.name for d in tmp_path.iterdir() if d.is_dir() and d.name.startswith("panel")) == sorted(
        [first.panels.name, store.panels.name, "panel-backup"])


def test_failed_refresh_leaves_the_current_panels_alone(tmp_path):
    class Broken(FakeBinance):
        def metrics(self, symbol, start, end):
            if symbol == "DEADUSDT":
                raise RuntimeError("断网")
            return super().metrics(symbol, start, end)

    run(PanelStore(tmp_path), FakeBinance(), NOW)
    before = PanelStore(tmp_path)
    with pytest.raises(RuntimeError):
        run(before, Broken(), NOW + DAY_MS)
    # 写到一半的那版不换上去：当前还是上一次刷新完整的那版
    after = PanelStore(tmp_path)
    assert after.panels == before.panels and len(after.load("BTCUSDT")) == (NOW - FROM) // BAR_MS
    assert after.load_reference_grid() is not None


def test_prefetched_keeps_order_and_runs_only_a_few_ahead():
    started: list[str] = []

    def fn(s: str) -> str:
        started.append(s)
        return s * 2

    out = []
    for s, v in r._prefetched(fn, list("abcdefghij")):
        out.append((s, v))
        # 取到第 k 个时最多只开拉了 k + PULL_WORKERS 个，拉完没取的不越攒越多
        assert len(started) <= len(out) + r.PULL_WORKERS
    assert out == [(c, c * 2) for c in "abcdefghij"]

    def broken(s: str) -> str:
        if s == "c":
            raise RuntimeError("断网")
        return s

    got = []
    with pytest.raises(RuntimeError):
        for s, _ in r._prefetched(broken, list("abcdef")):
            got.append(s)
    # 出错那个之前的照常按顺序给出
    assert got == ["a", "b"]


def test_thin_coin_stays_in_the_pool_and_is_reported(tmp_path):
    class NoPremium(FakeBinance):
        def premium(self, symbol, start, end):
            if symbol in ("AAAUSDT", "ETHUSDT"):
                return pd.DataFrame({"close_time": pd.Series(dtype="int64"), "premium": pd.Series(dtype="float64")})
            return super().premium(symbol, start, end)

    progress = {}
    result = run(PanelStore(tmp_path), NoPremium(), NOW, progress)
    # 基差全空也不剔：照常落面板、留在榜单，基差那列是空值；覆盖低的报给页面
    assert "AAAUSDT" in result.status and "AAAUSDT" in set(result.universe["symbol"])
    assert PanelStore(tmp_path).load("AAAUSDT")["premium"].isna().all()
    assert progress["thin"] == {"AAAUSDT": "premium 0.00", "ETHUSDT": "premium 0.00"}
