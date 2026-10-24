import io
import logging
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

import httpx
import pytest
from fakes import FakeFapi

from wiib_alpha.data import binance as b

BAR = b.BAR_MS
DAY = b.DAY_MS
H1 = 3_600_000
H8 = 8 * H1
UM = "data/futures/um"
HEADER = ("open_time,open,high,low,close,volume,close_time,quote_volume,count,taker_buy_volume,"
          "taker_buy_quote_volume,ignore")


def ms(y: int, m: int, d: int, h: int = 0) -> int:
    return int(datetime(y, m, d, h, tzinfo=timezone.utc).timestamp() * 1000)


LISTED = ms(2026, 7, 1)          # 7 月上市
MONTHLY_END = ms(2026, 9, 1)     # 月度文件出到 8 月
DAILY_END = ms(2026, 9, 20)      # 日度文件出到 09-19
NOW = ms(2026, 9, 21, 9)
HOLE = ms(2026, 7, 10)           # 7 月的月度文件缺这一天，日度文件有
LOST = ms(2026, 7, 20)           # 这一天月度、日度都没有


def without(lines: list[str], *days: int) -> list[str]:
    """去掉落在这些日子里的行，头行留着"""
    return [ln for ln in lines if not ln[0].isdigit() or not any(d <= int(ln.split(",")[0]) < d + DAY for d in days)]


def bars(a: int, b_: int, step: int = BAR) -> list[int]:
    """[a, b_) 内的 bar 起点，LOST 那天的没有"""
    return [t for t in range(a, b_, step) if not LOST <= t < LOST + DAY]


def zipped(lines: list[str]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("x.csv", "\n".join(lines) + "\n")
    return buf.getvalue()


def kline_lines(a: int, b_: int, step: int = BAR, header: bool = True, micro: bool = False) -> list[str]:
    """[a, b_) 内的 bar；close = 第几根 15m bar，好核对；7 月的老文件没头行，8 月的时间戳是微秒"""
    scale = 1000 if micro else 1
    rows = [f"{t * scale},99.0,101.0,98.0,{t // BAR},10.0,{(t + step - 1) * scale},1000.0,7,4.0,400.0,0"
            for t in range(a, b_, step)]
    return ([HEADER] if header else []) + rows


def premium_lines(a: int, b_: int) -> list[str]:
    """基差 K 线：close 是溢价率，成交量那几列是 0"""
    return [HEADER] + [f"{t},0,0,0,{(t // BAR % 5) * 0.0001:.4f},0,{t + BAR - 1},0,180,0,0,0" for t in range(a, b_, BAR)]


def funding_lines(a: int, b_: int) -> list[str]:
    return ["calc_time,funding_interval_hours,last_funding_rate"] + [f"{t},8,0.0001" for t in range(a, b_, H8)]


def metrics_lines(day_ms: int) -> list[str]:
    """一天三条，故意乱序；oi = 当天几号"""
    day = datetime.fromtimestamp(day_ms / 1000, tz=timezone.utc)
    stamps = [day.replace(hour=h).strftime("%Y-%m-%d %H:%M:%S") for h in (12, 0, 23)]
    return ["create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,"
            "sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio"] + \
           [f"{s},X,{day.day},{day.day * 100.0},1.1,1.2,1.3,1.4" for s in stamps]


class Bucket:
    """内存里的 S3：按前缀列目录、按 key 下文件；每页只给 3 条，逼出分页"""
    PAGE = 3

    def __init__(self):
        self.files: dict[str, bytes] = {}

    def put(self, key: str, lines: list[str]) -> None:
        self.files[key] = zipped(lines)
        self.files[key + ".CHECKSUM"] = b"x"

    def list(self, params) -> httpx.Response:
        prefix, marker = params.get("prefix", ""), params.get("marker", "")
        entries: list[str] = []
        for key in sorted(self.files):
            if not key.startswith(prefix):
                continue
            rest = key[len(prefix):]
            entry = prefix + rest.split("/")[0] + "/" if "/" in rest else key
            if entry > marker and entry not in entries:
                entries.append(entry)
        page, more = entries[:self.PAGE], entries[self.PAGE:]
        body = '<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">'
        for e in page:
            body += (f"<CommonPrefixes><Prefix>{e}</Prefix></CommonPrefixes>" if e.endswith("/")
                     else f"<Contents><Key>{e}</Key></Contents>")
        body += f"<IsTruncated>{'true' if more else 'false'}</IsTruncated>"
        if more:
            body += f"<NextMarker>{page[-1]}</NextMarker>"
        return httpx.Response(200, content=(body + "</ListBucketResult>").encode())


def fill(bucket: Bucket) -> None:
    for mo in (7, 8):
        a, b_ = ms(2026, mo, 1), ms(2026, mo + 1, 1)
        # 7 月的月度文件缺 HOLE、LOST 两天
        gone = (HOLE, LOST) if mo == 7 else ()
        bucket.put(f"{UM}/monthly/klines/X/15m/X-15m-2026-{mo:02d}.zip",
                   without(kline_lines(a, b_, header=mo != 7, micro=mo == 8), *gone))
        bucket.put(f"{UM}/monthly/klines/X/1d/X-1d-2026-{mo:02d}.zip", without(kline_lines(a, b_, step=DAY), *gone))
        bucket.put(f"{UM}/monthly/fundingRate/X/X-fundingRate-2026-{mo:02d}.zip", funding_lines(a, b_))
        bucket.put(f"{UM}/monthly/premiumIndexKlines/X/15m/X-15m-2026-{mo:02d}.zip", without(premium_lines(a, b_), *gone))
    # 日度目录里历史上每天都有文件（LOST 那天除外），7、8 月的也在，客户端只该碰 9 月的和补洞的
    for t in range(LISTED, DAILY_END, DAY):
        if t == LOST:
            continue
        day = datetime.fromtimestamp(t / 1000, tz=timezone.utc).strftime("%Y-%m-%d")
        bucket.put(f"{UM}/daily/klines/X/15m/X-15m-{day}.zip", kline_lines(t, t + DAY))
        bucket.put(f"{UM}/daily/klines/X/1d/X-1d-{day}.zip", kline_lines(t, t + DAY, step=DAY))
        bucket.put(f"{UM}/daily/premiumIndexKlines/X/15m/X-15m-{day}.zip", premium_lines(t, t + DAY))
        bucket.put(f"{UM}/daily/metrics/X/X-metrics-{day}.zip", metrics_lines(t))
    # 别的符号只有目录，测 symbols() 的分页
    for s in ("W", "Y", "Z"):
        bucket.put(f"{UM}/monthly/klines/{s}/15m/{s}-15m-2026-07.zip", kline_lines(LISTED, LISTED + BAR))
    # D 的费率月度文件停在 3 月：下架了就到此为止，还在交易就是归档断了
    bucket.put(f"{UM}/monthly/fundingRate/D/D-fundingRate-2026-03.zip", funding_lines(ms(2026, 3, 1), ms(2026, 4, 1)))


def handler(bucket: Bucket, log: list[str]):
    def handle(request: httpx.Request) -> httpx.Response:
        if request.url.host == "s3":
            log.append(f"LIST {request.url.params.get('prefix')} > {request.url.params.get('marker', '')}")
            return bucket.list(request.url.params)
        log.append(request.url.path)
        if request.url.host == "f":
            if request.url.path == "/fapi/v1/exchangeInfo":
                return httpx.Response(200, json={"symbols": [{"symbol": "X", "underlyingType": "COIN"}]})
            # fapi 费率：[startTime, endTime] 内已结算的，最多 limit 条；X 上市后 8 小时一条，D 是 1 小时结算的老币
            p = request.url.params
            step, listed = (H8, LISTED) if p["symbol"] == "X" else (H1, ms(2025, 1, 1))
            a = max(int(p["startTime"]), listed)
            b_ = min(int(p["endTime"]) + 1, NOW)
            rows = [{"symbol": p["symbol"], "fundingTime": t, "fundingRate": "0.0002", "markPrice": "1"}
                    for t in range(-(-a // step) * step, b_, step)]
            return httpx.Response(200, json=rows[:int(p["limit"])])
        key = request.url.path.lstrip("/")
        return httpx.Response(200, content=bucket.files[key]) if key in bucket.files else httpx.Response(404)
    return handle


@pytest.fixture(autouse=True)
def no_rest_gap(monkeypatch):
    """fapi 限速在这里不等，翻页的用例要连发好几次"""
    monkeypatch.setattr(b, "REST_GAP_S", 0.0)
    monkeypatch.setattr(b, "DATA_GAP_S", 0.0)


def client(log: list[str] | None = None, fapi: str = "https://f") -> b.Binance:
    bucket = Bucket()
    fill(bucket)
    transport = httpx.MockTransport(handler(bucket, [] if log is None else log))
    return b.Binance("https://v", httpx.Client(transport=transport), fapi_url=fapi, list_url="https://s3", concurrency=3)


def downloaded(log: list[str]) -> list[str]:
    """真下了的文件；列目录的那些行不算"""
    return [p for p in log if not p.startswith("LIST") and (p.endswith(".zip") or p.endswith(".CHECKSUM"))]


def test_klines_monthly_then_daily_for_the_current_month():
    log: list[str] = []
    df = client(log).klines("X", LISTED, NOW)
    # 月度文件缺的 HOLE 那天用日度文件补上了，LOST 那天哪都没有就缺着
    assert df["open_time"].tolist() == bars(LISTED, DAILY_END)
    assert df["close"].tolist() == [t // BAR for t in bars(LISTED, DAILY_END)]
    assert df["close_time"].iloc[-1] == DAILY_END - 1
    assert df["open_time"].dtype == "int64" and df["trades"].dtype == "int64" and df["close"].dtype == "float64"
    got = downloaded(log)
    # 7、8 月走月度，9 月走日度；日度目录里 7、8 月的只碰了补洞的两天（LOST 那次 404），CHECKSUM 没碰
    assert sum("/monthly/" in p for p in got) == 2 and sum("/daily/" in p for p in got) == 21
    assert [p for p in got if "/daily/" in p and "2026-07-" in p] == ["/" + f"{UM}/daily/klines/X/15m/X-15m-2026-07-{d}.zip" for d in ("10", "20")]
    assert not any("/daily/" in p and "2026-08-" in p for p in got)
    assert not any(p.endswith(".CHECKSUM") for p in got)


def test_klines_incremental_start_only_lists_from_that_day():
    log: list[str] = []
    start = ms(2026, 9, 10, 6)
    df = client(log).klines("X", start, NOW)
    assert df["open_time"].iloc[0] == start and df["open_time"].iloc[-1] == DAILY_END - BAR
    got = downloaded(log)
    assert len(got) == 10 and not any("/monthly/" in p for p in got) and not any("2026-09-09" in p for p in got)


def test_daily_klines_for_the_universe():
    log: list[str] = []
    df = client(log).klines("X", LISTED, NOW, "1d")
    assert df["open_time"].tolist() == bars(LISTED, DAILY_END, DAY)
    assert (df["quote_volume"] == 1000.0).all()
    # 日线一天一根，缺一根就是缺一天，同样拿日度文件补
    assert f"/{UM}/daily/klines/X/1d/X-1d-2026-07-10.zip" in downloaded(log)


def test_premium_is_the_close_by_close_time():
    df = client().premium("X", LISTED, NOW)
    assert list(df.columns) == b.PREMIUM_COLUMNS
    assert df["close_time"].tolist() == [t + BAR - 1 for t in bars(LISTED, DAILY_END)]
    assert df["premium"].iloc[1] == pytest.approx(((LISTED + BAR) // BAR % 5) * 0.0001)


def test_funding_monthly_then_rest_tail():
    log: list[str] = []
    df = client(log).funding("X", LISTED, NOW, live=True)
    # 7、8 月走月度文件，9 月没有月度文件改走 fapi，补到最近一次结算
    assert df["funding_time"].tolist() == list(range(LISTED, ms(2026, 9, 21, 8) + H8, H8))
    assert df["funding_rate"].iloc[0] == 0.0001 and df["funding_rate"].iloc[-1] == 0.0002
    assert df["funding_time"].dtype == "int64"
    assert [p for p in log if p.startswith("/fapi/")] == ["/fapi/v1/fundingRate"]


def test_funding_of_a_delisted_symbol_does_not_ask_fapi():
    log: list[str] = []
    df = client(log).funding("D", ms(2026, 3, 1), NOW)
    # 不在 exchangeInfo 里的币，月度文件停在 3 月就到 3 月，fapi 对它回 400，不去问
    assert df["funding_time"].tolist() == list(range(ms(2026, 3, 1), ms(2026, 4, 1), H8))
    assert not any(p.startswith("/fapi/") for p in log)


def test_funding_of_a_live_symbol_with_a_stale_archive_pages_through_fapi():
    log: list[str] = []
    df = client(log).funding("D", ms(2026, 3, 1), NOW, live=True)
    # 还在交易但月度文件停在 3 月：4 月起全走 fapi；1 小时一条有四千多，一页 1000 翻了 5 页
    assert df["funding_time"].tolist() == list(range(ms(2026, 3, 1), ms(2026, 4, 1), H8)) + list(range(ms(2026, 4, 1), NOW, H1))
    assert [p for p in log if p.startswith("/fapi/")] == ["/fapi/v1/fundingRate"] * 5


def test_funding_rest_can_be_switched_off():
    log: list[str] = []
    df = client(log, fapi="").funding("X", LISTED, NOW, live=True)
    assert df["funding_time"].tolist() == list(range(LISTED, MONTHLY_END, H8))
    assert not any(p.startswith("/fapi/") for p in log)


def test_metrics_are_sorted_in_ms_and_cut_at_start():
    df = client().metrics("X", ms(2026, 9, 18, 12), NOW)
    assert list(df.columns) == b.METRICS_COLUMNS
    # 18 日 00:00 那条在起点之前被滤掉；文件里的乱序排好了
    assert df["time"].tolist() == [ms(2026, 9, 18, 12), ms(2026, 9, 18, 23), ms(2026, 9, 19), ms(2026, 9, 19, 12),
                                   ms(2026, 9, 19, 23)]
    assert df["oi"].tolist() == [18.0, 18.0, 19.0, 19.0, 19.0] and df["oi_value"].iloc[-1] == 1900.0
    assert df["time"].dtype == "int64" and df["taker_ls_vol"].dtype == "float64"


def test_symbols_lists_directories_across_pages():
    log: list[str] = []
    assert client(log).symbols() == ["W", "X", "Y", "Z"]
    assert sum(p.startswith("LIST") for p in log) >= 2


def test_exchange_info():
    assert client().exchange_info()["X"]["underlyingType"] == "COIN"
    assert client(fapi="").exchange_info() == {}


def test_fapi_calls_are_spaced_even_from_several_threads(monkeypatch):
    monkeypatch.setattr(b, "REST_GAP_S", 0.05)
    stamps: list[float] = []

    def handle(request: httpx.Request) -> httpx.Response:
        stamps.append(time.monotonic())
        return httpx.Response(200, json={"symbols": []})

    api = b.Binance("https://v", httpx.Client(transport=httpx.MockTransport(handle)), fapi_url="https://f",
                    list_url="https://s3")
    with ThreadPoolExecutor(4) as pool:
        list(pool.map(lambda _: api.exchange_info(), range(6)))
    # 四个线程一起问，照样一个一个发、前后隔够 REST_GAP_S
    stamps.sort()
    assert len(stamps) == 6 and min(later - earlier for earlier, later in zip(stamps, stamps[1:])) >= 0.05 - 1e-3


def test_empty_range_gives_typed_empty():
    api = client()
    df = api.klines("X", ms(2020, 1, 1), ms(2020, 2, 1))
    assert df.empty and df["close_time"].dtype == "int64"
    assert api.funding("X", ms(2020, 1, 1), ms(2020, 2, 1)).empty
    assert api.premium("X", ms(2020, 1, 1), ms(2020, 2, 1)).empty
    assert api.metrics("X", ms(2020, 1, 1), ms(2020, 2, 1)).empty


def test_client_error_raises():
    api = b.Binance("https://v", httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(403))),
                    list_url="https://s3")
    with pytest.raises(b.BinanceError):
        api.klines("X", LISTED, NOW)


# ========== fapi 实时 ==========

# 0 点过 5 分：0 点开盘那根还没收
LIVE_NOW = ms(2026, 9, 21) + 5 * 60_000


def live_client() -> tuple[b.Binance, FakeFapi]:
    fake = FakeFapi(["X", "Y"], LIVE_NOW)
    return fake.binance(), fake


def test_recent_klines_take_one_small_window_for_a_day_and_pages_of_1000_for_a_gap():
    api, fake = live_client()
    day = api.recent_klines("X", LIVE_NOW - DAY, LIVE_NOW)
    # 一天 96 根以内一次拿完，limit 99（权重 1）；还没收盘的那根扔掉
    assert [p["limit"] for _, p in fake.log] == ["99"]
    assert day["open_time"].tolist() == list(range(ms(2026, 9, 20) + BAR, ms(2026, 9, 21), BAR))
    assert day["open_time"].dtype == "int64" and day["close"].dtype == "float64" and list(day.columns) == b.KLINE_COLUMNS
    assert day["close"].iloc[-1] == pytest.approx(fake.close("X", ms(2026, 9, 21) - BAR))
    fake.log.clear()
    # 缺 12 天：1000 根一窗翻两窗，接起来不重不漏
    gap = api.recent_klines("X", LIVE_NOW - 12 * DAY, LIVE_NOW)
    assert [p["limit"] for _, p in fake.log] == ["1000", "1000"]
    assert gap["open_time"].tolist() == list(range(ms(2026, 9, 9) + BAR, ms(2026, 9, 21), BAR))
    premium = api.recent_premium("X", LIVE_NOW - DAY, LIVE_NOW)
    assert list(premium.columns) == b.PREMIUM_COLUMNS and premium["close_time"].tolist() == (day["close_time"]).tolist()


def test_recent_metrics_join_the_five_data_endpoints():
    api, fake = live_client()
    df = api.recent_metrics("Y", LIVE_NOW - 2 * DAY, LIVE_NOW)
    # 两天 576 条，500 条一窗：五个接口各翻两窗
    assert [path for path, _ in fake.log] == [f"/futures/data/{name}" for name in (
        "openInterestHist", "topLongShortPositionRatio", "topLongShortAccountRatio", "globalLongShortAccountRatio",
        "takerlongshortRatio") for _ in range(2)]
    assert all(p["period"] == "5m" and p["limit"] == "500" for _, p in fake.log)
    # 前四个接口的时间戳比归档晚一格：请求往后挪一格、拿回来再挪回去；taker 买卖量比不挪
    assert int(fake.log[0][1]["startTime"]) == LIVE_NOW - 2 * DAY + b.METRIC_MS
    assert int(fake.log[8][1]["startTime"]) == LIVE_NOW - 2 * DAY
    # 列同归档 metrics，七列都有数
    assert list(df.columns) == b.METRICS_COLUMNS and df.iloc[0].notna().all()
    assert df["time"].iloc[0] == LIVE_NOW - 2 * DAY and df["time"].is_monotonic_increasing
    # taker 不挪，最新一条 LIVE_NOW - 5 分钟比另四个早出一格：那行只有一列，不留
    assert df["time"].iloc[-1] == LIVE_NOW - 2 * b.METRIC_MS and df.iloc[-1].notna().all()
    # 挪的接口：记在 t + 5 分钟的那条值落在 t；不挪的：记在 t 的落在 t
    i, t0 = fake.code["Y"], LIVE_NOW - 2 * DAY
    api_t = t0 // b.METRIC_MS + 1
    assert df["ls_top_pos"].iloc[0] == pytest.approx(1 + 0.01 * ((api_t + i) % 9))
    assert df["ls_top_acct"].iloc[0] == pytest.approx(1 + 0.01 * ((api_t + i + 3) % 9))
    assert df["ls_acct"].iloc[0] == pytest.approx(1 + 0.01 * ((api_t + i + 6) % 9))
    assert df["taker_ls_vol"].iloc[0] == pytest.approx(0.8 + 0.01 * ((t0 // b.METRIC_MS + 2 * i) % 7))
    assert df["oi"].iloc[0] == 1001 + api_t % 7 and df["oi_value"].iloc[0] == 2e5


def test_rest_logs_the_used_weight_and_marks_come_in_one_call(caplog):
    api, fake = live_client()
    with caplog.at_level(logging.INFO, logger="wiib_alpha.data.binance"):
        api.recent_klines("X", LIVE_NOW - DAY, LIVE_NOW)
    assert "fapi /fapi/v1/klines X 已用权重 3" in caplog.text
    assert api.mark_prices() == {"X": pytest.approx(fake.mark("X")), "Y": pytest.approx(fake.mark("Y"))}
    assert fake.log[-1] == ("/fapi/v1/premiumIndex", {})
