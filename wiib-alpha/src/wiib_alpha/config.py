"""环境变量配置：进程起来读一次，之后只读。"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import datetime, timezone
from functools import lru_cache

# feed 订阅的 7 个加密永续，与 wiib-feed application.yml 的 symbols 同一份；始终建面板，时序报告只看它们
DEFAULT_SYMBOLS = ("BTCUSDT", "ETHUSDT", "DOGEUSDT", "SOLUSDT", "XRPUSDT", "BNBUSDT", "ZECUSDT")
# 不进池子的：稳定币对、指数型永续、代币化股票与商品。在交易的币还会按 exchangeInfo 的 underlyingType 再筛，已下架的只靠这份名单
DEFAULT_EXCLUDE = (
    "USDCUSDT", "FDUSDUSDT", "BUSDUSDT", "TUSDUSDT", "USDPUSDT",
    "BTCDOMUSDT", "DEFIUSDT",
    "XAUUSDT", "CLUSDT", "SNDKUSDT", "SOXLUSDT", "SKHYNIXUSDT", "MUUSDT", "KORUUSDT", "SPCXUSDT",
    # 黄金代币：exchangeInfo 标的是 COIN，只能靠名单挡
    "PAXGUSDT", "XAUTUSDT",
    "AAPLUSDT", "TSLAUSDT", "NVDAUSDT", "MSTRUSDT",
)


@dataclass(frozen=True)
class ModelSpec:
    """一个角色的模型：管理员在页面上配，存 SQLite 的 model 表；协议分叉与 Java 侧 ByokModelBuilder 同，baseUrl 不带版本后缀"""
    protocol: str
    base_url: str
    model: str
    api_key: str = field(repr=False)
    effort: str = ""


@dataclass(frozen=True)
class Config:
    binance_vision_url: str
    # 列目录走 S3 的 ListObjects，data.binance.vision 的网页就是从这里拿的
    binance_vision_list_url: str
    # 月度费率文件还没出的月份走这里的 REST 补；留空就不补，尾巴保持 NaN（本地连不上 fapi 时用）
    binance_fapi_url: str
    # 现货实时接口（交易员补现货 K 线尾巴），限速桶和 fapi 分开
    binance_spot_url: str
    # 产品币：始终建面板，时序报告只看它们
    symbols: tuple[str, ...]
    # 历史从这天起（ms）
    history_from: int
    # 每月取成交额前几名进池，日均成交额（USDT）低于这个数的不进
    universe_size: int
    min_daily_quote_volume: float
    exclude_symbols: tuple[str, ...]
    fetch_concurrency: int
    data_dir: str
    db_path: str
    # 多空腿单边成本 bps，按流动性档：当月排名 1~20 / 21~50 / 其余
    cost_tiers_bps: tuple[float, float, float]
    # force_order.trade_time 是不带时区的本地时间，按这个时区换成 UTC；与容器 TZ 同值
    force_order_tz: str
    sim_base_url: str
    pg_host: str
    pg_port: int
    pg_db: str
    pg_user: str
    pg_password: str = field(repr=False)
    # 调 sim /internal/** 的 X-Internal-Token，三个 Java 进程同一份
    internal_api_token: str = field(repr=False)

    @property
    def pg_conninfo(self) -> dict[str, object]:
        """psycopg.connect 的关键字参数"""
        return {
            "host": self.pg_host,
            "port": self.pg_port,
            "dbname": self.pg_db,
            "user": self.pg_user,
            "password": self.pg_password,
        }


def _csv(name: str, default: tuple[str, ...]) -> tuple[str, ...]:
    """逗号分隔、转大写；没设或留空都用 default"""
    raw = os.getenv(name) or ""
    return tuple(s.strip().upper() for s in raw.split(",") if s.strip()) or default


def _tiers(text: str) -> tuple[float, float, float]:
    a, b, c = (float(s) for s in text.split(","))
    return a, b, c


def _day_ms(text: str) -> int:
    return int(datetime.strptime(text, "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp() * 1000)


@lru_cache(maxsize=1)
def config() -> Config:
    return Config(
        binance_vision_url=os.getenv("BINANCE_VISION_URL", "https://data.binance.vision").rstrip("/"),
        binance_vision_list_url=os.getenv("BINANCE_VISION_LIST_URL",
                                          "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision").rstrip("/"),
        binance_fapi_url=os.getenv("BINANCE_FAPI_URL", "https://fapi.binance.com").rstrip("/"),
        binance_spot_url=os.getenv("BINANCE_SPOT_URL", "https://api.binance.com").rstrip("/"),
        symbols=_csv("ALPHA_SYMBOLS", DEFAULT_SYMBOLS),
        history_from=_day_ms(os.getenv("ALPHA_HISTORY_FROM", "2021-01-01")),
        universe_size=int(os.getenv("ALPHA_UNIVERSE_SIZE", "80")),
        min_daily_quote_volume=float(os.getenv("ALPHA_MIN_DAILY_QUOTE_VOLUME", "20000000")),
        exclude_symbols=_csv("ALPHA_EXCLUDE_SYMBOLS", DEFAULT_EXCLUDE),
        fetch_concurrency=int(os.getenv("ALPHA_FETCH_CONCURRENCY", "6")),
        data_dir=os.getenv("ALPHA_DATA_DIR", "./data"),
        db_path=os.getenv("ALPHA_DB_PATH", "./data/alpha.sqlite"),
        cost_tiers_bps=_tiers(os.getenv("ALPHA_COST_TIERS_BPS", "5,8,12")),
        force_order_tz=os.getenv("TZ", "Asia/Shanghai"),
        sim_base_url=os.getenv("SIM_BASE_URL", "http://localhost:8080").rstrip("/"),
        pg_host=os.getenv("PG_HOST", "localhost"),
        pg_port=int(os.getenv("PG_PORT", "5432")),
        pg_db=os.getenv("PG_DB", "wiib"),
        pg_user=os.getenv("PG_USER", ""),
        pg_password=os.getenv("PG_PASSWORD", ""),
        internal_api_token=os.getenv("INTERNAL_API_TOKEN", ""),
    )
