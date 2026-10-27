import numpy as np
import pandas as pd
import pytest

from wiib_alpha import reference
from wiib_alpha.data.binance import BAR_MS
from wiib_alpha.data.panel import PanelStore, holdout_cut
from wiib_alpha.data.pool import Pool
from wiib_alpha.dsl import parse
from wiib_alpha.trade import scheme

PLANTED_N = 6000
# 截面每个时刻要至少 20 个币；前三个兼当产品币
POOL_SYMBOLS = ("AAA", "BBB", "CCC", *(f"X{i:02d}" for i in range(21)))
PRODUCTS = POOL_SYMBOLS[:3]
HOLDOUT_DAYS = 10
# 参照网格只放一条与埋进去的信号无关的；用价格类的会和 planted 真的相关（G6 会正确地拒掉它）
REFERENCES = {"noise4": "Mean($trades, 4)"}


def planted_panel(seed: int, n: int = PLANTED_N) -> pd.DataFrame:
    """volume 是埋进去的 AR(1) 预测信号，trades、premium、funding_rate 是纯噪音；全程在池；列够 evaluate、预检、交易方案和
    标定 G9 的随机因子用。每个币各埋一条：同一时刻 volume 高的币之后也涨得多，截面上就是信号"""
    rng = np.random.default_rng(seed)
    s = np.zeros(n)
    for i in range(1, n):
        s[i] = 0.98 * s[i - 1] + rng.normal(0, 1.0)
    r = 0.0004 * np.roll(s, 1) + rng.normal(0, 0.003, n)
    close = 100 * np.exp(np.cumsum(r))
    df = pd.DataFrame({"close": close, "high": close * 1.001, "low": close * 0.999, "ret": r, "volume": s,
                       "quote_volume": np.abs(s) * close, "taker_buy_base": s / 2, "trades": rng.normal(0, 1, n),
                       "liq_long": np.nan, "liq_short": np.nan, "liq_count": np.nan, "in_pool": 1.0},
                      index=np.arange(n) * BAR_MS + 1_735_689_600_000)
    df["premium"] = rng.normal(0, 0.0005, n)
    # 像真数据的结算：32 根（8 小时）一个费率，期内不变
    df["funding_rate"] = np.repeat(rng.normal(0, 1e-4, -(-n // 32)), 32)[:n]
    df.index.name = "close_time"
    for h in (24, 48, 96):
        df[f"fwd_ret_{h}"] = np.log(df["close"].shift(-h) / df["close"])
    return df


def planted_universe(panels: dict[str, pd.DataFrame]) -> pd.DataFrame:
    """每个月全部币都在池，排名按 POOL_SYMBOLS 的顺序：前 20 一档，后面二档"""
    index = next(iter(panels.values())).index.to_numpy()
    months = sorted(set(np.asarray(index, dtype="datetime64[ms]").astype("datetime64[M]").astype(str)))
    rows = [(m, s, i + 1, 1e9 - i) for m in months for i, s in enumerate(panels)]
    return pd.DataFrame(rows, columns=["month", "symbol", "rank", "avg_quote_volume"])


def write_store(root, panels: dict[str, pd.DataFrame]) -> PanelStore:
    """面板落成 parquet，再写 status.json 和参照网格（网格要比 status 新）"""
    store = PanelStore(root)
    for s, p in panels.items():
        store.save_panel(s, p)
    store.write_status({s: {"rows": len(p)} for s, p in panels.items()})
    exprs = {k: parse(v, ["trades"]) for k, v in REFERENCES.items()}
    store.save_reference_grid(reference.grid(store, list(panels), {"done": 0}, exprs))
    return store


@pytest.fixture(scope="session")
def planted_panels() -> dict[str, pd.DataFrame]:
    return {s: planted_panel(i) for i, s in enumerate(POOL_SYMBOLS)}


@pytest.fixture(scope="session")
def planted_store(tmp_path_factory, planted_panels) -> PanelStore:
    return write_store(tmp_path_factory.mktemp("pool"), planted_panels)


@pytest.fixture(scope="session")
def planted_pool(planted_store, planted_panels) -> Pool:
    cut = holdout_cut(planted_panels, HOLDOUT_DAYS)
    return Pool.load(planted_store, planted_universe(planted_panels), cut)


@pytest.fixture(scope="session")
def planted_vol(planted_pool) -> np.ndarray:
    """全量骨架上的 Std($ret, 672)：多空腿按它分仓"""
    return planted_pool.values(scheme.VOL).f


@pytest.fixture(scope="session")
def planted_grid(planted_store, planted_pool) -> reference.Grid:
    return reference.align(planted_store.load_reference_grid(), planted_pool)
