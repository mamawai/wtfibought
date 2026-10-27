"""Binance 公开数据仓 data.binance.vision：USDⓈ-M 合约的 K 线、资金费率、基差 K 线与 metrics，按文件下 zip，不要 key、无频控。

每个数据集先列目录再下载，不靠 404 探路：月度 zip 从起始月列起，日度 zip 从最后一个月度文件的下个月初列起，自然盖住当月。
月度文件偶尔缺整天（2022-02-26~28、2022-04-01~02 几十个币都缺）而同一天的日度文件在，拼完数每天的根数，不满的日子拿日度文件补。
目录列举走 S3 的 ListObjects，data.binance.vision 的网页就是这么拿的；下架币的历史文件一直在。
K 线与基差 K 线 CSV 同 12 列：open_time,open,high,low,close,volume,close_time,quote_volume,count,
taker_buy_volume,taker_buy_quote_volume,ignore，基差的 close 就是溢价率。费率三列 calc_time,funding_interval_hours,last_funding_rate。
metrics 八列，create_time 是 UTC 字符串，文件内可能无序。老文件没有头行、2025 年后的时间戳可能是微秒，解析时一并归一。
费率没有日度文件，月度文件之后的尾巴走 fapi 的 /fapi/v1/fundingRate（公开接口，不要 key），只给还在交易的币补：
有些币还在交易但费率月度文件早就不出了（TROY、ALPACA 停在 2025-06），尾巴可能长达一年多，分页翻完。
基差与 metrics 也有币中途断档（FTT 基差 2023-07 停、RAY metrics 2024-07 停），REST 补不了历史，断了就是 NaN。
交易员出信号要归档之后到现在的尾巴，另有几个 fapi 实时接口（recent_*），每次响应的已用权重打进日志。
"""

from __future__ import annotations

import io
import logging
import threading
import time
import xml.etree.ElementTree as ET
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
import numpy as np
import pandas as pd

logger = logging.getLogger(__name__)

INTERVAL = "15m"
BAR_MS = 15 * 60_000
DAY_MS = 86_400_000
# 两种 K 线一根多长，数一天该有几根用
_BAR_MS = {"15m": BAR_MS, "1d": DAY_MS}
_PAGE = 1000
# fapi K 线的权重按 limit 算：< 100 是 1，不传默认 500 是 5；日常补一天用这个
_SMALL = 99
# /futures/data 的 5 分钟统计，一页最多 500 条
METRIC_MS = 5 * 60_000
_METRIC_PAGE = 500
DEFAULT_LIST_URL = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision"
_UM = "data/futures/um"
_S3 = "{http://s3.amazonaws.com/doc/2006-03-01/}"
_RETRIES = 3
# fapi 费率接口每个 IP 5 分钟 500 次，服务器上 Java 服务也在用：两次之间至少隔这么多秒（5 分钟最多 250 次），几个币一起拉也一样
REST_GAP_S = 1.2
# /futures/data 下的 5 分钟统计是单独的限速桶（每个 IP 5 分钟 1000 次、权重 0）：间隔短一点（5 分钟最多 750 次）
DATA_GAP_S = 0.4

KLINE_COLUMNS = ["open_time", "open", "high", "low", "close", "volume", "close_time",
                 "quote_volume", "trades", "taker_buy_base", "taker_buy_quote"]
_INT_COLUMNS = ["open_time", "close_time", "trades"]
_FLOAT_COLUMNS = ["open", "high", "low", "close", "volume", "quote_volume", "taker_buy_base", "taker_buy_quote"]
FUNDING_COLUMNS = ["funding_time", "funding_rate"]
PREMIUM_COLUMNS = ["close_time", "premium"]
# metrics：持仓量（币）、持仓名义（USDT）、大户多空账户数比、大户多空持仓比、全体账户多空比、taker 买卖量比
METRICS_COLUMNS = ["time", "oi", "oi_value", "ls_top_acct", "ls_top_pos", "ls_acct", "taker_ls_vol"]
# CSV 按位置对到我们的列名，K 线最后一列 ignore 不要
_KLINE_CSV = [*KLINE_COLUMNS, "ignore"]
_FUNDING_CSV = ["funding_time", "interval_hours", "funding_rate"]
_METRICS_CSV = ["create_time", "symbol", *METRICS_COLUMNS[1:]]
# 超过 15 位就是微秒
_MICRO_THRESHOLD = 100_000_000_000_000


class BinanceError(RuntimeError):
    """拉行情失败；status 是 HTTP 状态码，没有是 None"""

    def __init__(self, message: str, status: int | None = None):
        super().__init__(message)
        self.status = status


def empty_klines() -> pd.DataFrame:
    df = pd.DataFrame({c: pd.Series(dtype="float64") for c in KLINE_COLUMNS})
    return df.astype({c: "int64" for c in _INT_COLUMNS})


def empty_funding() -> pd.DataFrame:
    return pd.DataFrame({"funding_time": pd.Series(dtype="int64"), "funding_rate": pd.Series(dtype="float64")})


def empty_premium() -> pd.DataFrame:
    return pd.DataFrame({"close_time": pd.Series(dtype="int64"), "premium": pd.Series(dtype="float64")})


def empty_metrics() -> pd.DataFrame:
    return pd.DataFrame({c: pd.Series(dtype="int64" if c == "time" else "float64") for c in METRICS_COLUMNS})


class Binance:
    def __init__(self, base_url: str, client: httpx.Client | None = None, fapi_url: str = "https://fapi.binance.com",
                 list_url: str = DEFAULT_LIST_URL, concurrency: int = 6):
        self._base = base_url.rstrip("/")
        self._fapi = fapi_url.rstrip("/")
        self._list_url = list_url.rstrip("/")
        self._client = client if client is not None else httpx.Client(timeout=60.0)
        self._concurrency = max(1, concurrency)
        # fapi 一次只发一个，上一个结束的时刻
        self._rest_lock = threading.Lock()
        self._rest_at = float("-inf")

    # ========== 目录 ==========

    def symbols(self) -> list[str]:
        """归档里出现过的全部符号，含已下架"""
        prefix = f"{_UM}/monthly/klines/"
        _, dirs = self._list(prefix)
        return sorted(d[len(prefix):].rstrip("/") for d in dirs)

    def exchange_info(self) -> dict[str, dict[str, Any]]:
        """fapi 的合约表，只有在交易的；fapi 地址留空给空表"""
        if not self._fapi:
            return {}
        return {row["symbol"]: row for row in self._rest("/fapi/v1/exchangeInfo", {}).get("symbols", [])}

    # ========== 数据集 ==========

    def klines(self, symbol: str, start_ms: int, end_ms: int, interval: str = INTERVAL) -> pd.DataFrame:
        """[start_ms, end_ms) 内已收盘的 bar，按 open_time 升序"""
        df = self._bars("klines", symbol, interval, start_ms, end_ms)
        return empty_klines() if df is None else df

    def premium(self, symbol: str, start_ms: int, end_ms: int) -> pd.DataFrame:
        """基差 K 线的收盘溢价率，按 close_time"""
        df = self._bars("premiumIndexKlines", symbol, INTERVAL, start_ms, end_ms)
        if df is None:
            return empty_premium()
        return df[["close_time", "close"]].rename(columns={"close": "premium"}).reset_index(drop=True)

    def funding(self, symbol: str, start_ms: int, end_ms: int, live: bool = False) -> pd.DataFrame:
        """[start_ms, end_ms) 内已结算的资金费率。

        月度文件之后的尾巴只给还在交易的币（live，exchangeInfo 里有）走 fapi 补到 end_ms，不管尾巴多长；
        下架币归档停在哪就到哪，fapi 对它回 400 不去问；fapi 地址留空也不补。
        """
        prefix = f"{_UM}/monthly/fundingRate/{symbol}/"
        keys = self._zips(prefix, f"{symbol}-fundingRate-{_month_name(start_ms)}")
        frames = [_parse(csv, _FUNDING_CSV, ["funding_time"]) for csv in self._download(keys)]
        tail_from = start_ms
        if keys:
            tail_from = max(start_ms, ms(next_month(_month(_stamp(keys[-1], 2)))))
        if self._fapi and live and tail_from < end_ms:
            frames.append(self._funding_rest(symbol, tail_from, end_ms))
        if not frames:
            return empty_funding()
        df = pd.concat(frames, ignore_index=True)[FUNDING_COLUMNS]
        df = df.astype({"funding_time": "int64", "funding_rate": "float64"})
        df = df[(df["funding_time"] >= start_ms) & (df["funding_time"] < end_ms)]
        return df.drop_duplicates("funding_time").sort_values("funding_time").reset_index(drop=True)

    def metrics(self, symbol: str, start_ms: int, end_ms: int) -> pd.DataFrame:
        """[start_ms, end_ms) 内的 metrics，只有日度文件，按 time 升序"""
        prefix = f"{_UM}/daily/metrics/{symbol}/"
        keys = self._zips(prefix, f"{symbol}-metrics-{day_name(start_ms)}")
        frames = [_parse_metrics(csv) for csv in self._download(keys)]
        if not frames:
            return empty_metrics()
        df = pd.concat(frames, ignore_index=True)
        df = df[(df["time"] >= start_ms) & (df["time"] < end_ms)]
        return df.drop_duplicates("time").sort_values("time").reset_index(drop=True)

    def _bars(self, kind: str, symbol: str, interval: str, start_ms: int, end_ms: int) -> pd.DataFrame | None:
        """K 线形状的数据集：月度 zip 从起始月起，日度 zip 从最后一个月度文件的下个月初起；一个文件都没有给 None。

        月度文件覆盖的日子里根数不满一天的，再下那天的日度文件补上；日度也没有的就缺着。
        """
        stem = f"{symbol}-{interval}-"
        keys = self._zips(f"{_UM}/monthly/{kind}/{symbol}/{interval}/", stem + _month_name(start_ms))
        day_from = utc(start_ms)
        if keys:
            day_from = max(day_from, next_month(_month(_stamp(keys[-1], 2))))
        daily_prefix = f"{_UM}/daily/{kind}/{symbol}/{interval}/"
        keys += self._zips(daily_prefix, stem + day_from.strftime("%Y-%m-%d"))
        frames = [_parse(csv, _KLINE_CSV, ["open_time", "close_time"]) for csv in self._download(keys)]
        if not frames:
            return None
        holes = _short_days(pd.concat(frames)["open_time"], DAY_MS // _BAR_MS[interval], ms(day_from))
        if holes:
            patch = self._download([f"{daily_prefix}{stem}{d}.zip" for d in holes])
            frames += [_parse(csv, _KLINE_CSV, ["open_time", "close_time"]) for csv in patch]
        df = pd.concat(frames, ignore_index=True)[KLINE_COLUMNS]
        df[_INT_COLUMNS] = df[_INT_COLUMNS].astype("int64")
        df[_FLOAT_COLUMNS] = df[_FLOAT_COLUMNS].astype("float64")
        df = df[(df["open_time"] >= start_ms) & (df["close_time"] < end_ms)]
        return df.drop_duplicates("open_time").sort_values("open_time").reset_index(drop=True)

    def _funding_rest(self, symbol: str, start_ms: int, end_ms: int) -> pd.DataFrame:
        """一页最多 1000 条，按时间往后翻到 end_ms；1 小时结算的币一年要翻九页"""
        times: list[int] = []
        rates: list[float] = []
        while start_ms < end_ms:
            rows = self._rest("/fapi/v1/fundingRate",
                              {"symbol": symbol, "startTime": start_ms, "endTime": end_ms - 1, "limit": _PAGE})
            times += [int(r["fundingTime"]) for r in rows]
            rates += [float(r["fundingRate"]) for r in rows]
            if len(rows) < _PAGE:
                break
            start_ms = times[-1] + 1
        if not times:
            return empty_funding()
        return pd.DataFrame({"funding_time": times, "funding_rate": rates})

    # ========== fapi 实时：交易员的 live 尾巴和下单价 ==========

    def recent_klines(self, symbol: str, start_ms: int, end_ms: int) -> pd.DataFrame:
        """fapi 的 15m K 线，open_time 在 [start_ms, end_ms) 内、已收盘的，列同归档"""
        return self._recent_bars("klines", symbol, start_ms, end_ms)

    def recent_premium(self, symbol: str, start_ms: int, end_ms: int) -> pd.DataFrame:
        """fapi 的 15m 溢价 K 线收盘，列同归档 premium_15m"""
        df = self._recent_bars("premiumIndexKlines", symbol, start_ms, end_ms)
        return df[["close_time", "close"]].rename(columns={"close": "premium"})

    def recent_metrics(self, symbol: str, start_ms: int, end_ms: int) -> pd.DataFrame:
        """[start_ms, end_ms) 内的 metrics，5 分钟一条，列同归档：持仓量（openInterestHist）、大户持仓多空比（topLongShortPositionRatio）、
        大户账户数多空比（topLongShortAccountRatio）、全体账户多空比（globalLongShortAccountRatio）、taker 买卖量比（takerlongshortRatio）。
        接口只留最近 30 天。前四个接口的时间戳比归档 metrics 的 create_time 晚一格（同一个值，接口记在 5 分钟之后），挪回来和归档对齐，
        taker 买卖量比不晚、不挪；多空比接口只给 4 位小数，归档是 6 位。
        taker 的最新一条可能比另四个早出一格，只留到每个有数据的接口都出了的那一刻，不留只有一列的半行"""
        df = None
        # 每个有数据的接口最后一条的时刻
        ends = []
        for name, shift, fields in (
                ("openInterestHist", METRIC_MS, {"oi": "sumOpenInterest", "oi_value": "sumOpenInterestValue"}),
                ("topLongShortPositionRatio", METRIC_MS, {"ls_top_pos": "longShortRatio"}),
                ("topLongShortAccountRatio", METRIC_MS, {"ls_top_acct": "longShortRatio"}),
                ("globalLongShortAccountRatio", METRIC_MS, {"ls_acct": "longShortRatio"}),
                ("takerlongshortRatio", 0, {"taker_ls_vol": "buySellRatio"})):
            rows = self._history(name, symbol, start_ms + shift, end_ms + shift)
            part = pd.DataFrame({"time": pd.Series([r["timestamp"] - shift for r in rows], dtype="int64"),
                                 **{col: pd.Series([r[f] for r in rows], dtype="float64") for col, f in fields.items()}})
            if rows:
                ends.append(int(part["time"].max()))
            df = part if df is None else df.merge(part, on="time", how="outer")
        df = df.reindex(columns=METRICS_COLUMNS)
        keep = (df["time"] >= start_ms) & (df["time"] < end_ms) & (df["time"] <= min(ends, default=end_ms))
        return df[keep].sort_values("time").reset_index(drop=True)

    def recent_funding(self, symbol: str, start_ms: int, end_ms: int) -> pd.DataFrame:
        """fapi 的实际结算记录，[start_ms, end_ms) 内的，列同归档 funding"""
        return self._funding_rest(symbol, start_ms, end_ms)

    def mark_prices(self) -> dict[str, float]:
        """全部合约的标记价：/fapi/v1/premiumIndex 不带 symbol 一次全给（权重 10）"""
        return {r["symbol"]: float(r["markPrice"]) for r in self._rest("/fapi/v1/premiumIndex", {})}

    def _recent_bars(self, kind: str, symbol: str, start_ms: int, end_ms: int) -> pd.DataFrame:
        """K 线形状的接口按窗往后翻，一窗正好装得下 limit 根：整段不到 99 根一次拿完，缺口大按 1000 根一窗；
        还没收盘的那根不要"""
        limit = _SMALL if end_ms - start_ms <= _SMALL * BAR_MS else _PAGE
        rows: list[list[Any]] = []
        for a in range(start_ms, end_ms, limit * BAR_MS):
            rows += self._rest(f"/fapi/v1/{kind}", {"symbol": symbol, "interval": INTERVAL, "startTime": a,
                                                    "endTime": min(end_ms, a + limit * BAR_MS) - 1, "limit": limit})
        if not rows:
            return empty_klines()
        df = pd.DataFrame(rows, columns=_KLINE_CSV)[KLINE_COLUMNS]
        df[_INT_COLUMNS] = df[_INT_COLUMNS].astype("int64")
        df[_FLOAT_COLUMNS] = df[_FLOAT_COLUMNS].astype("float64")
        df = df[df["close_time"] < end_ms]
        return df.drop_duplicates("open_time").sort_values("open_time").reset_index(drop=True)

    def _history(self, name: str, symbol: str, start_ms: int, end_ms: int) -> list[dict[str, Any]]:
        """/futures/data 下的 5 分钟统计，一窗 500 条往后翻"""
        rows: list[dict[str, Any]] = []
        for a in range(start_ms, end_ms, _METRIC_PAGE * METRIC_MS):
            rows += self._rest(f"/futures/data/{name}", {"symbol": symbol, "period": "5m", "startTime": a,
                                                         "endTime": min(end_ms, a + _METRIC_PAGE * METRIC_MS) - 1,
                                                         "limit": _METRIC_PAGE})
        return rows

    # ========== 传输 ==========

    def _zips(self, prefix: str, after: str) -> list[str]:
        """prefix 下名字排在 after 之后的 zip，不要 CHECKSUM；S3 的 marker 是"从这个名字之后开始"，after 不带 .zip 所以自己也算在内"""
        keys, _ = self._list(prefix, prefix + after)
        return [k for k in keys if k.endswith(".zip")]

    def _list(self, prefix: str, marker: str | None = None) -> tuple[list[str], list[str]]:
        """ListObjects 分页列完：返回 (文件 key, 子目录前缀)"""
        keys: list[str] = []
        dirs: list[str] = []
        while True:
            params: dict[str, Any] = {"delimiter": "/", "prefix": prefix, "max-keys": 1000}
            if marker:
                params["marker"] = marker
            resp = self._get(self._list_url, params)
            if resp is None:
                raise BinanceError(f"列目录 {prefix} 返回 404")
            root = ET.fromstring(resp.content)
            keys += [str(k.text) for k in root.iterfind(f"{_S3}Contents/{_S3}Key")]
            dirs += [str(p.text) for p in root.iterfind(f"{_S3}CommonPrefixes/{_S3}Prefix")]
            if root.findtext(f"{_S3}IsTruncated") != "true":
                return keys, dirs
            marker = root.findtext(f"{_S3}NextMarker") or (keys + dirs)[-1]

    def _download(self, keys: list[str]) -> list[bytes]:
        """按 key 顺序并发下载解压；列过目录的文件不该 404，真遇到就跳过"""
        if not keys:
            return []
        with ThreadPoolExecutor(max_workers=min(self._concurrency, len(keys))) as pool:
            got = list(pool.map(lambda k: self._zip("/" + k), keys))
        return [g for g in got if g is not None]

    def _zip(self, path: str) -> bytes | None:
        """下载 zip 解出里面唯一的 CSV；404 是没这个文件"""
        resp = self._get(self._base + path, None)
        if resp is None:
            return None
        with zipfile.ZipFile(io.BytesIO(resp.content)) as zf:
            return zf.read(zf.namelist()[0])

    def _rest(self, path: str, params: dict[str, Any]) -> Any:
        with self._rest_lock:
            gap = DATA_GAP_S if path.startswith("/futures/data/") else REST_GAP_S
            time.sleep(max(0.0, self._rest_at + gap - time.monotonic()))
            resp = self._get(self._fapi + path, params)
            self._rest_at = time.monotonic()
        if resp is None:
            raise BinanceError(f"fapi {path} 返回 404")
        # 这个 IP 这一分钟已用的权重，对着方案估的请求量看
        logger.info("fapi %s %s 已用权重 %s", path, params.get("symbol", ""), resp.headers.get("X-MBX-USED-WEIGHT-1M"))
        return resp.json()

    def _get(self, url: str, params: dict[str, Any] | None) -> httpx.Response | None:
        """带重试的 GET；404 返回 None"""
        for attempt in range(_RETRIES):
            if attempt:
                time.sleep(2 ** attempt)
            try:
                resp = self._client.get(url, params=params)
            except httpx.TransportError as exc:
                logger.warning("%s 网络错误，第 %d 次：%s", url, attempt + 1, exc)
                continue
            if resp.status_code == 200:
                return resp
            if resp.status_code == 404:
                return None
            # 429 是限流，5xx 是对面抖，都值得再试；其它直接报
            if resp.status_code == 429 or resp.status_code >= 500:
                logger.warning("%s HTTP %d，第 %d 次", url, resp.status_code, attempt + 1)
                continue
            raise BinanceError(f"{url} 返回 HTTP {resp.status_code}", resp.status_code)
        raise BinanceError(f"{url} 连试 {_RETRIES} 次都失败")


# ========== 解析 ==========


def _parse(csv: bytes, names: list[str], time_cols: list[str]) -> pd.DataFrame:
    df = pd.read_csv(io.BytesIO(csv), header=None, names=names, dtype=str)
    # 头行第一格是列名不是数字
    df = df[df[names[0]].str.isdigit()].apply(pd.to_numeric)
    for c in time_cols:
        t = df[c].to_numpy(dtype="int64")
        df[c] = np.where(t > _MICRO_THRESHOLD, t // 1000, t)
    return df


def _short_days(open_time: pd.Series, per_day: int, before_ms: int) -> list[str]:
    """before_ms 之前、根数不满 per_day 的日子（YYYY-MM-DD）；头尾两天不算，上市下架就在那天"""
    counts = (open_time // DAY_MS).value_counts()
    first, last = int(counts.index.min()) + 1, min(int(counts.index.max()), before_ms // DAY_MS)
    return [day_name(d * DAY_MS) for d in range(first, last) if counts.get(d, 0) < per_day]


def _parse_metrics(csv: bytes) -> pd.DataFrame:
    df = pd.read_csv(io.BytesIO(csv), header=None, names=_METRICS_CSV, dtype=str)
    df = df[df["create_time"] != "create_time"]
    stamp = pd.to_datetime(df["create_time"], utc=True, format="%Y-%m-%d %H:%M:%S")
    out = pd.DataFrame({"time": ((stamp - pd.Timestamp(0, tz="UTC")) // pd.Timedelta(milliseconds=1))
                        .to_numpy(dtype="int64")})
    for c in METRICS_COLUMNS[1:]:
        out[c] = pd.to_numeric(df[c], errors="coerce").to_numpy(dtype="float64")
    return out


# ========== 时间 ==========


def utc(ms_: int) -> datetime:
    return datetime.fromtimestamp(ms_ / 1000, tz=timezone.utc)


def ms(moment: datetime) -> int:
    return int(moment.timestamp() * 1000)


def next_month(month: datetime) -> datetime:
    return (month.replace(day=28) + timedelta(days=4)).replace(day=1)


def _month(stamp: str) -> datetime:
    return datetime.strptime(stamp, "%Y-%m").replace(tzinfo=timezone.utc)


def _month_name(ms_: int) -> str:
    return utc(ms_).strftime("%Y-%m")


def day_name(ms_: int) -> str:
    """UTC 日期 YYYY-MM-DD"""
    return utc(ms_).strftime("%Y-%m-%d")


def _stamp(key: str, parts: int) -> str:
    """文件名末尾的日期：月度取 YYYY-MM 两段，日度取三段"""
    return "-".join(key.rsplit("/", 1)[-1][:-4].split("-")[-parts:])
