"""脚本化的假模型：按收到的整段提示词挑回答，图和接口测试共用；交易员测试用的假 fapi 和假 sim 在后面。"""

import json
import math
from typing import Any

import httpx
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, ChatResult

from wiib_alpha.data.binance import BAR_MS, METRIC_MS, Binance
from wiib_alpha.data.panel import PanelStore
from wiib_alpha.trade.executor import Sim

# 假 fapi 的资金费 8 小时结算一次
FUNDING_MS = 8 * 3_600_000


class ScriptedModel(GenericFakeChatModel):
    reply: Any = None

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        text = self.reply("\n".join(m.text for m in messages))
        return ChatResult(generations=[ChatGeneration(message=AIMessage(text))])


def model(reply) -> ScriptedModel:
    return ScriptedModel(messages=iter(()), reply=reply)


LESSONS = [{"kind": "avoid", "text": "成交笔数没有截面信号"}, {"kind": "try", "text": "成交量平滑后更稳"}]
# 复盘里不合格式的：超长、抄了表达式；代码要丢掉
BAD_LESSONS = [{"kind": "avoid", "text": "成交笔数" * 41}, {"kind": "note", "text": "别写成 Sum($trades, 4)"}]


def gen_reply(prompt: str) -> str:
    """planted：先少写窗口、再写 $volume 被评审打回、最后 Mean($volume, 4)；noise：$trades，门没过重写成成交笔数的和，都是噪音；
    twin：planted 换个结构（EMA），clone：planted 换个窗口（规范形相同）；k ≥ 3 才轮到后两条；复盘回两条经验"""
    if "这一轮的候选与结局" in prompt:
        return json.dumps(LESSONS + BAD_LESSONS, ensure_ascii=False)
    if "JSON 数组" in prompt:
        return "好的：\n```json\n" + json.dumps([
            {"name": "planted", "family": "liquidity", "hypothesis": "成交量列藏着信号"},
            # 不在机制清单里的 family，落库是 other
            {"name": "noise", "family": "量能", "hypothesis": "成交笔数没用"},
            {"name": "twin", "family": "liquidity", "hypothesis": "成交量指数平滑"},
            {"name": "clone", "family": "volume_weighted_basis", "hypothesis": "成交量再平滑一点"},
        ], ensure_ascii=False) + "\n```"
    if "写成一条表达式" in prompt:
        if "[noise]" in prompt:
            if "上一版表达式：$trades（" in prompt:
                return '{"expr": "Sum($trades, 4)", "direction": 1, "note": "加总一下"}'
            if "上一版表达式：Sum($trades, 4)（" in prompt:
                return '{"expr": "Sum($trades, 16)", "direction": 1, "note": "窗口拉长"}'
            return '{"expr": "$trades", "direction": 1, "note": "直接用"}'
        if "[twin]" in prompt:
            return '{"expr": "EMA($volume, 8)", "direction": 1, "note": "指数平滑"}'
        if "[clone]" in prompt:
            return '{"expr": "Mean($volume, 16)", "direction": 1, "note": "换个窗口"}'
        if "用 4 根均值" in prompt:
            return '{"expr": "Mean($volume, 4)", "direction": 1, "note": "平滑后的成交量"}'
        if "表达式不合法" in prompt:
            return '{"expr": "$volume", "direction": 1, "note": "直接用成交量"}'
        return '{"expr": "Mean($volume)", "direction": 1, "note": "少写了窗口"}'
    # 复评老候选补方向
    if "这个表达式的值越大" in prompt:
        return '{"direction": 1}'
    return "？"


def review_reply(prompt: str) -> str:
    if "表达式：Mean($volume," in prompt or "表达式：EMA($volume," in prompt:
        return '{"verdict": "pass", "comment": "对得上", "suggestion": ""}'
    if "表达式：$volume" in prompt:
        return '{"verdict": "revise", "comment": "太毛", "suggestion": "用 4 根均值"}'
    return '{"verdict": "reject", "comment": "不行", "suggestion": ""}'


# ========== 交易员：假 fapi、假 sim ==========


class FakeFapi:
    """fapi 的假行情：每个币每根 15m 的价格、成交量、溢价和 5 分钟的持仓、多空比都按 (币序号, 时刻) 算出来，问几次都一样；
    now 之后开盘的不给（没收盘的那根照样给，客户端自己扔）；halted 里的币不在交易；broken 里的币 K 线回 500；
    spot 里的币有同名现货对（/api/v3），现货 K 线的量和笔数和永续的不一样；log 记下每个请求的路径和参数"""

    def __init__(self, symbols, now_ms: int):
        self.code = {s: i for i, s in enumerate(symbols)}
        self.now = now_ms
        self.halted: set[str] = set()
        self.broken: set[str] = set()
        self.spot: set[str] = set(symbols)
        self.log: list[tuple[str, dict]] = []

    def close(self, s: str, t: int) -> float:
        i, k = self.code[s], t // BAR_MS
        return 100 * (1 + 0.05 * i) * math.exp(0.004 * (1 + i % 5) * math.sin(k / 7 + i) + 0.002 * math.sin(k / 3 + 2 * i))

    def mark(self, s: str) -> float:
        """标记价：now 所在那根的价"""
        return self.close(s, self.now - self.now % BAR_MS)

    def rate(self, s: str, t: int) -> float:
        """t 那次（8 小时一次）结算的费率，隔一次换一次正负"""
        return 0.0001 * (1 + self.code[s] % 4) * (1 if (t // FUNDING_MS) % 2 == 0 else -1)

    def handle(self, request: httpx.Request) -> httpx.Response:
        p, path = request.url.params, request.url.path
        self.log.append((path, dict(p)))
        if path == "/fapi/v1/exchangeInfo":
            rule = [{"filterType": "LOT_SIZE", "stepSize": "0.001", "minQty": "0.001", "maxQty": "1000000"},
                    {"filterType": "MIN_NOTIONAL", "notional": "5"}]
            return httpx.Response(200, json={"symbols": [
                {"symbol": s, "status": "SETTLING" if s in self.halted else "TRADING", "underlyingType": "COIN",
                 "filters": rule} for s in self.code]})
        if path == "/fapi/v1/premiumIndex":
            return httpx.Response(200, json=[{"symbol": s, "markPrice": str(self.mark(s))} for s in self.code])
        if path == "/api/v3/exchangeInfo":
            return httpx.Response(200, json={"symbols": [{"symbol": s, "status": "TRADING", "quoteAsset": "USDT"}
                                                         for s in sorted(self.spot)]})
        s, a, b = p["symbol"], int(p["startTime"]), min(int(p["endTime"]), self.now - 1)
        if s not in (self.spot if path.startswith("/api/") else self.code):
            return httpx.Response(400, json={"code": -1121, "msg": "Invalid symbol."})
        if path == "/fapi/v1/klines" and s in self.broken:
            return httpx.Response(500)
        i, limit = self.code[s], int(p["limit"])
        if path in ("/fapi/v1/klines", "/fapi/v1/premiumIndexKlines", "/api/v3/klines"):
            rows = []
            for t in range(-(-a // BAR_MS) * BAR_MS, b + 1, BAR_MS)[:limit]:
                k = t // BAR_MS
                if path.endswith("/klines"):
                    c, v = self.close(s, t), 1000 * (1 + i) * (1.5 + math.sin(k / 5 + i))
                    # 现货：量是永续的三成、笔数另一个数
                    v, n = (v * 0.3, 50 + i) if path.startswith("/api/") else (v, 100 + i)
                    rows.append([t, str(c), str(c * 1.001), str(c * 0.999), str(c), str(v), t + BAR_MS - 1, str(v * c),
                                 n, str(v / 2), str(v * c / 2), "0"])
                else:
                    prem = 0.0001 * ((i * 7) % 11 - 5) + 0.00005 * math.sin(k / 13 + i)
                    rows.append([t, "0", "0", "0", str(prem), "0", t + BAR_MS - 1, "0", 0, "0", "0", "0"])
            return httpx.Response(200, json=rows, headers={"X-MBX-USED-WEIGHT-1M": "3"})
        if path == "/fapi/v1/fundingRate":
            return httpx.Response(200, json=[{"symbol": s, "fundingTime": t, "fundingRate": str(self.rate(s, t))}
                                             for t in range(-(-a // FUNDING_MS) * FUNDING_MS, b + 1, FUNDING_MS)][:limit])
        points = range(-(-a // METRIC_MS) * METRIC_MS, b + 1, METRIC_MS)[:limit]
        if path.endswith("openInterestHist"):
            rows = [{"symbol": s, "sumOpenInterest": str(1000 + i + t // METRIC_MS % 7),
                     "sumOpenInterestValue": str(1e5 * (1 + i)), "timestamp": t} for t in points]
        elif path.endswith("takerlongshortRatio"):
            rows = [{"buySellRatio": str(0.8 + 0.01 * ((t // METRIC_MS + 2 * i) % 7)), "buyVol": "1", "sellVol": "1",
                     "timestamp": t} for t in points]
        else:
            # 三个多空比接口：大户持仓、大户账户数、全体账户数，值各不一样
            k = {"topLongShortPositionRatio": 0, "topLongShortAccountRatio": 1, "globalLongShortAccountRatio": 2}[
                path.rsplit("/", 1)[-1]]
            rows = [{"symbol": s, "longShortRatio": str(1 + 0.01 * ((t // METRIC_MS + i + 3 * k) % 9)), "timestamp": t}
                    for t in points]
        return httpx.Response(200, json=rows)

    def binance(self) -> Binance:
        return Binance("https://v", httpx.Client(transport=httpx.MockTransport(self.handle)), fapi_url="https://f",
                       list_url="https://s3", spot_url="https://sp")

    def write_raw(self, store: PanelStore, start: int, end: int) -> None:
        """[start, end) 的 K 线、溢价 K 线、metrics、现货 K 线写进 raw/，和接口给的一模一样（归档与实时同源）"""
        api, now = self.binance(), self.now
        self.now = end
        for s in self.code:
            store.save_raw("klines_15m", s, api.recent_klines(s, start, end))
            store.save_raw("premium_15m", s, api.recent_premium(s, start, end))
            store.save_raw("metrics", s, api.recent_metrics(s, start, end))
            store.save_raw("funding", s, api.recent_funding(s, start, end))
            if s in self.spot:
                store.save_raw("spot_15m", s, api.recent_spot_klines(s, start, end))
        self.now = now
        self.log.clear()


class FakeSim:
    """sim 的 /internal/futures：一个账户（userId 42），按 price(币) 市价成交、不收手续费，同方向合并、平仓减量；
    同一个币的仓位杠杆得一样（开仓带的杠杆对不上回 1764），调杠杆改这个币全部仓位；档位全是表外默认档。
    calls 按顺序记下每笔下单、调杠杆的 (open/close/leverage, 请求体)；watched 记下每次登记的币；fail 里的币下单、调杠杆回业务失败"""

    USER = 42
    # sim 表外 USDT 永续的默认档：一档到底，最高 10 倍
    BRACKETS = [{"tier": 1, "notionalFloor": 0, "notionalCap": 1_000_000_000, "maxLeverage": 10, "mmr": 0.025,
                 "maintAmount": 0}]

    def __init__(self, price):
        self.price = price
        self.balance = 10000.0
        self.positions: dict[int, dict] = {}
        self.calls: list[tuple[str, dict]] = []
        self.watched: list[list[str]] = []
        self.fail: set[str] = set()
        self._ids = 0

    def hold(self, symbol: str, side: str, quantity: float, leverage: int) -> None:
        """按现价建一个仓位"""
        self._ids += 1
        self.positions[self._ids] = {"id": self._ids, "symbol": symbol, "side": side, "quantity": quantity,
                                     "entryPrice": self.price(symbol), "leverage": leverage}

    def view(self, p: dict) -> dict:
        mark = self.price(p["symbol"])
        sign = 1 if p["side"] == "LONG" else -1
        return {**p, "markPrice": mark, "positionValue": p["quantity"] * mark,
                "unrealizedPnl": sign * (mark - p["entryPrice"]) * p["quantity"], "marginMode": "CROSS"}

    def handle(self, request: httpx.Request) -> httpx.Response:
        if request.headers.get("X-Internal-Token") != "tok":
            return httpx.Response(401, text="internal api unauthorized")
        path = request.url.path
        ok = lambda data: httpx.Response(200, json={"code": 0, "msg": None, "data": data})
        refuse = lambda code, msg: httpx.Response(200, json={"code": code, "msg": msg, "data": None})
        if path.endswith("/ensure-account"):
            return ok({"userId": self.USER, "balance": self.balance})
        if path.endswith("/balance"):
            return ok({"userId": self.USER, "balance": self.balance, "frozenBalance": 0, "available": self.balance})
        if path.endswith("/positions"):
            return ok([self.view(p) for p in self.positions.values()])
        if path.endswith("/brackets"):
            return ok({s: self.BRACKETS for s in request.url.params.get_list("symbols")})
        if path.endswith("/watch"):
            self.watched.append(json.loads(request.content))
            return ok(None)
        action = path.rsplit("/", 1)[-1]
        body = json.loads(request.content)
        self.calls.append((action, body))
        if action == "leverage":
            if body["symbol"] in self.fail:
                return refuse(1601, "价格不可用")
            mine = [p for p in self.positions.values() if p["symbol"] == body["symbol"]]
            if not mine:
                return refuse(1750, "仓位不存在")
            for p in mine:
                p["leverage"] = body["leverage"]
            return ok(None)
        if action == "open":
            if body["symbol"] in self.fail:
                return refuse(1601, "价格不可用")
            if any(p["symbol"] == body["symbol"] and p["leverage"] != body["leverage"] for p in self.positions.values()):
                return refuse(1764, "杠杆和这个币已有的仓位不一样")
            mark = self.price(body["symbol"])
            same = next((p for p in self.positions.values()
                         if p["symbol"] == body["symbol"] and p["side"] == body["side"]), None)
            if same is None:
                self.hold(body["symbol"], body["side"], body["quantity"], body["leverage"])
            else:
                q = same["quantity"] + body["quantity"]
                same.update(entryPrice=(same["entryPrice"] * same["quantity"] + mark * body["quantity"]) / q, quantity=q)
            return ok({"orderId": len(self.calls), "quantity": body["quantity"], "filledPrice": mark, "commission": 0})
        p = self.positions[body["positionId"]]
        if p["symbol"] in self.fail:
            return refuse(1601, "价格不可用")
        mark = self.price(p["symbol"])
        q = body["quantity"] if body["quantity"] is not None else p["quantity"]
        self.balance += (1 if p["side"] == "LONG" else -1) * (mark - p["entryPrice"]) * q
        p["quantity"] = round(p["quantity"] - q, 9)
        if p["quantity"] <= 0:
            del self.positions[p["id"]]
        return ok({"orderId": len(self.calls), "quantity": q, "filledPrice": mark, "commission": 0})

    def sim(self) -> Sim:
        return Sim("http://sim", "tok", httpx.Client(transport=httpx.MockTransport(self.handle)))
