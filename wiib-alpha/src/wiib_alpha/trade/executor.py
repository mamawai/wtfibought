"""执行器：读 sim 账户，按每币目标净权重算要加减多少，市价下单。

sim 全仓：净值 = 余额 + Σ浮盈亏（CrossMarginService）。目标名义 = 净值 × 净权重 × 名义倍数（trade/sizing.py），数量 = 名义 ÷ 标记价，按 exchangeInfo 的步长向零取整；
和当前净持仓的差额小于 max(最小名义额, 净值 × 0.2%) 的不动。sim 是对冲模式，同一个币多空可能都有：每边各自算该留多少，
多的平掉（留 0 就全平，全平不受步长和最小名义额限制），少的开上；先下全部平仓、减仓，再下加仓、开仓，平仓没成的币不开新仓。
全仓市价单，杠杆按当天的仓位规模（trade/sizing.py），不挂止损止盈；平完、开之前，还拿着的币杠杆不是今天的先调成今天的。clientRequestId = ft-{日期}-{币}-{序号}，序号按动作定死（1 减多、2 减空、3 加多、4 加空）：
同一天重跑时同一个动作还是同一个 ID，超时重发 sim 去重（它记 10 分钟），不会双仓；动作变了 ID 也跟着变，不会撞上别的单。
"""

from __future__ import annotations

import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

import httpx

USERNAME = "quant-factor"
INITIAL_BALANCE = 10000
# 差额小于净值的这么多不动，防碎单
MIN_TRADE = 0.002
_SLOTS = {("close", "LONG"): 1, ("close", "SHORT"): 2, ("open", "LONG"): 3, ("open", "SHORT"): 4}
# sim 幂等占位回的"处理中"
ORDER_IN_FLIGHT = 1106
# sim 抢不到仓位锁：单没执行，同一个 ID 再发就行
ORDER_PROCESSING = 1105
# 下单结果未知时，同一个 clientRequestId 一共发几次、隔几秒
ORDER_TRIES = 3
ORDER_RETRY_S = 2.0


class SimError(RuntimeError):
    """sim 请求失败；unknown 是结果未知（连不上、超时、sim 回"处理中"），单可能已经成交；
    retry 是能拿同一个 ID 再发：结果未知的，和 sim 抢不到仓位锁没执行的"""

    def __init__(self, message: str, unknown: bool = False, retry: bool = False):
        super().__init__(message)
        self.unknown = unknown
        self.retry = unknown or retry


class Sim:
    """sim 的 /internal/futures 接口，X-Internal-Token 鉴权；返回体 {code, msg, data}，code 不是 0 是业务失败"""

    def __init__(self, base_url: str, token: str, client: httpx.Client | None = None):
        self._base = base_url.rstrip("/") + "/internal/futures"
        self._headers = {"X-Internal-Token": token}
        self._client = client if client is not None else httpx.Client(timeout=15.0)

    def ensure_account(self) -> int:
        """建号是幂等的：已有就直接回，不重复入金"""
        data = self._call("POST", "/ensure-account", params={"username": USERNAME, "initialBalance": INITIAL_BALANCE})
        return int(data["userId"])

    def balance(self, user_id: int) -> dict[str, Any]:
        """balance 是余额，available 是全仓可用（净值 − 占用保证金 − 挂单占用）"""
        return self._call("GET", f"/{user_id}/balance")

    def positions(self, user_id: int) -> list[dict[str, Any]]:
        return self._call("GET", f"/{user_id}/positions")

    def watch(self, symbols: Sequence[str]) -> None:
        """登记今天要下单的币：feed 按交易员集合订行情，sim 那边登记 2 小时后过期"""
        self._call("POST", "/watch", json=list(symbols))

    def open(self, user_id: int, symbol: str, side: str, quantity: Decimal, request_id: str,
             leverage: int) -> dict[str, Any]:
        return self._order(f"/{user_id}/open", {
            "symbol": symbol, "side": side, "marginMode": "CROSS", "leverage": leverage, "orderType": "MARKET",
            "quantity": float(quantity), "clientRequestId": request_id})

    def leverage(self, user_id: int, symbol: str, leverage: int) -> None:
        """这个币全部仓位的杠杆调成 leverage；调成同一个值重发几次都一样"""
        self._order(f"/{user_id}/leverage", {"symbol": symbol, "leverage": leverage})

    def brackets(self, symbols: Sequence[str]) -> dict[str, list[dict[str, Any]]]:
        """这些币的档位：表外的给默认档，sim 不认识的不出"""
        return self._call("GET", "/brackets", params={"symbols": list(symbols)})

    def close(self, user_id: int, position_id: int, quantity: Decimal | None, request_id: str) -> dict[str, Any]:
        """quantity 是 None 就全平"""
        return self._order(f"/{user_id}/close", {
            "positionId": position_id, "orderType": "MARKET",
            "quantity": None if quantity is None else float(quantity), "clientRequestId": request_id})

    def _order(self, path: str, body: dict[str, Any]) -> Any:
        """结果未知、sim 抢不到仓位锁，就隔一会原样再发（下单带同一个 clientRequestId）；明确的业务失败不重发"""
        tries = 0
        while True:
            try:
                return self._call("POST", path, json=body)
            except SimError as exc:
                tries += 1
                if not exc.retry or tries == ORDER_TRIES:
                    raise
            time.sleep(ORDER_RETRY_S)

    def _call(self, method: str, path: str, params: dict[str, Any] | None = None, json: Any = None) -> Any:
        try:
            resp = self._client.request(method, self._base + path, params=params, json=json, headers=self._headers)
        except httpx.TransportError as exc:
            raise SimError(f"sim {path} 没有回应：{exc}", unknown=True) from exc
        if resp.status_code != 200:
            raise SimError(f"sim {path} 返回 HTTP {resp.status_code}")
        body = resp.json()
        code = body.get("code")
        if code != 0:
            raise SimError(f"sim {path} code={code}：{body.get('msg')}", unknown=code == ORDER_IN_FLIGHT,
                           retry=code == ORDER_PROCESSING)
        return body["data"]


def equity(balance: Mapping[str, Any], positions: Sequence[Mapping[str, Any]]) -> float:
    """全仓净值 = 余额 + Σ浮盈亏"""
    return float(balance["balance"]) + sum(float(p.get("unrealizedPnl") or 0) for p in positions)


@dataclass(frozen=True)
class Rule:
    """一个币的下单规则：LOT_SIZE 的步长、最小数量，MIN_NOTIONAL 的最小名义额（与 sim 校验用的同一份）"""
    step: Decimal
    min_qty: Decimal
    min_notional: Decimal


def rules(info: Mapping[str, Mapping[str, Any]]) -> dict[str, Rule]:
    """exchangeInfo 里每个币的下单规则"""
    out = {}
    for s, row in info.items():
        f = {x["filterType"]: x for x in row.get("filters", [])}
        if "LOT_SIZE" in f and "MIN_NOTIONAL" in f:
            out[s] = Rule(Decimal(f["LOT_SIZE"]["stepSize"]), Decimal(f["LOT_SIZE"]["minQty"]),
                          Decimal(f["MIN_NOTIONAL"]["notional"]))
    return out


@dataclass(frozen=True)
class Order:
    symbol: str
    action: str  # open / close
    side: str  # LONG / SHORT
    # None 是全平
    quantity: Decimal | None
    position_id: int | None
    request_id: str


def plan(weights: Mapping[str, float], positions: Sequence[Mapping[str, Any]], prices: Mapping[str, float],
         rules_: Mapping[str, Rule], equity_: float, multiple: float, day: str
         ) -> tuple[list[Order], list[dict[str, Any]]]:
    """weights 是每币目标净权重（1 倍名义的，不在里面的是 0），multiple 是名义倍数。出要下的单（平仓、减仓在前）和每币一行明细：
    目标、现有、差额太小或缺价格跳过的原因；refPrice（00:15 那根收盘价）下完单再补"""
    held: dict[str, dict[str, Mapping[str, Any]]] = {}
    for p in positions:
        held.setdefault(p["symbol"], {})[p["side"]] = p
    orders: list[Order] = []
    rows: list[dict[str, Any]] = []
    for s in sorted(set(weights) | set(held)):
        w = weights.get(s, 0.0)
        long_, short = held.get(s, {}).get("LONG"), held.get(s, {}).get("SHORT")
        have = {"LONG": _qty(long_), "SHORT": _qty(short)}
        price, rule = prices.get(s), rules_.get(s)
        row = {"symbol": s, "weight": w, "notional": w * equity_ * multiple, "price": price, "refPrice": None,
               "current": float(have["LONG"] - have["SHORT"]), "target": None, "note": None, "failed": False}
        rows.append(row)
        if w and (price is None or rule is None):
            row.update(note="缺标记价或下单规则", failed=True)
            continue
        # 目标数量：名义 ÷ 标记价，按步长向零取整，空头是负的
        want = Decimal(0)
        if w:
            want = (_dec(abs(w) * equity_ * multiple / price) // rule.step) * rule.step * (1 if w > 0 else -1)
        row["target"] = float(want)
        # 目标是 0 的一律全平：碎仓留着这个币会一直占着交易员集合；下架币可能没价格，照样全平
        if w and float(abs(want - have["LONG"] + have["SHORT"])) * price < max(
                float(rule.min_notional), equity_ * MIN_TRADE):
            row["note"] = "差额太小，不动"
            continue
        for side, pos in (("LONG", long_), ("SHORT", short)):
            # 这一边该留多少
            keep = max(want, Decimal(0)) if side == "LONG" else max(-want, Decimal(0))
            if have[side] > keep:
                orders.append(Order(s, "close", side, None if keep == 0 else have[side] - keep, pos["id"],
                                    _request_id(day, s, "close", side)))
            elif keep > have[side]:
                add = keep - have[side]
                if add < rule.min_qty or float(add) * price < float(rule.min_notional):
                    row["note"] = "要加的量不到最小下单量或最小名义额"
                else:
                    orders.append(Order(s, "open", side, add, None, _request_id(day, s, "open", side)))
    # 先平再开：先放出保证金，反手的先全平
    orders.sort(key=lambda o: o.action != "close")
    return orders, rows


def execute(sim: Sim, user_id: int, orders: Sequence[Order], positions: Sequence[Mapping[str, Any]], leverage: int,
            out: list[dict[str, Any]]) -> None:
    """先下全部平仓、减仓，再把还拿着、杠杆不是 leverage 的币调过来（sim 同一个币的仓位杠杆得一样，不然加仓被拒），
    最后下加仓、开仓。每笔的结果追加进 out（中途出了别的错，已下的也留着）；失败的记下接着下别的，
    平仓或调杠杆没成的币不再开新仓（反手要先全平）"""
    failed: set[str] = set()
    closes = [o for o in orders if o.action == "close"]
    for o in closes:
        _send(sim, user_id, o, leverage, failed, out)
    # 全平掉的那一边不算还拿着
    gone = {(o.symbol, o.side) for o in closes if o.quantity is None}
    stale = {p["symbol"] for p in positions if (p["symbol"], p["side"]) not in gone and p["leverage"] != leverage}
    for s in sorted(stale - failed):
        rec = {"symbol": s, "action": "leverage", "side": None, "quantity": None, "requestId": None,
               "leverage": leverage, "ok": False}
        out.append(rec)
        try:
            sim.leverage(user_id, s, leverage)
        except SimError as exc:
            failed.add(s)
            rec["error"] = str(exc)
            continue
        rec["ok"] = True
    for o in orders:
        if o.action == "open":
            _send(sim, user_id, o, leverage, failed, out)


def _send(sim: Sim, user_id: int, o: Order, leverage: int, failed: set[str], out: list[dict[str, Any]]) -> None:
    """下一笔，结果追加进 out；没成的币记进 failed"""
    rec = {"symbol": o.symbol, "action": o.action, "side": o.side,
           "quantity": None if o.quantity is None else float(o.quantity), "requestId": o.request_id, "ok": False}
    out.append(rec)
    if o.action == "open" and o.symbol in failed:
        rec["error"] = "这个币的平仓或调杠杆没成，不开新仓"
        return
    try:
        resp = (sim.open(user_id, o.symbol, o.side, o.quantity, o.request_id, leverage) if o.action == "open"
                else sim.close(user_id, o.position_id, o.quantity, o.request_id))
    except SimError as exc:
        failed.add(o.symbol)
        rec["error"] = ("结果未知，可能已成交：" if exc.unknown else "") + str(exc)
        return
    rec.update(ok=True, orderId=resp.get("orderId"), filled=resp.get("quantity"), price=resp.get("filledPrice"),
               commission=resp.get("commission"))


def _qty(position: Mapping[str, Any] | None) -> Decimal:
    return Decimal(0) if position is None else _dec(position["quantity"])


def _dec(x: float) -> Decimal:
    """float 转成它最短的十进制写法，步长整除不带二进制尾巴"""
    return Decimal(repr(float(x)))


def _request_id(day: str, symbol: str, action: str, side: str) -> str:
    return f"ft-{day.replace('-', '')}-{symbol}-{_SLOTS[(action, side)]}"
