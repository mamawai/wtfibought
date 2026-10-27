from decimal import Decimal

import httpx
import pytest
from fakes import FakeSim

from wiib_alpha.trade import executor as ex

RULE = ex.Rule(Decimal("0.001"), Decimal("0.001"), Decimal("5"))
EQUITY = 10000.0
DAY = "2026-10-21"


@pytest.fixture(autouse=True)
def no_retry_wait(monkeypatch):
    monkeypatch.setattr(ex, "ORDER_RETRY_S", 0.0)


def position(pid: int, symbol: str, side: str, quantity: float) -> dict:
    return {"id": pid, "symbol": symbol, "side": side, "quantity": quantity, "unrealizedPnl": 0.0}


def test_plan_sizes_by_equity_and_orders_closes_first():
    prices = {s: 100.0 for s in ("NEW", "CUT", "FLIP", "OUT", "TINY", "HEDGE", "DUST")}
    positions = [position(1, "CUT", "LONG", 2.0), position(2, "FLIP", "SHORT", 1.0), position(3, "OUT", "LONG", 1.0),
                 position(4, "TINY", "SHORT", 1.0), position(5, "GONE", "LONG", 3.0), position(6, "DUST", "SHORT", 1.0)]
    weights = {
        "NEW": 0.0123456,     # 新开多：123.456 USDT → 1.234 张（向零取整到步长）
        "CUT": 0.01,          # 多头 2 张减到 1 张
        "FLIP": 0.005,        # 空 1 张反手成多 0.5 张：先全平空，再开多
        "TINY": -0.0101,      # 想空 1.01 张，差 1 USDT（< 净值 0.2%）不动
        "DUST": 0.0001,       # 空 1 张反手：开多那 0.01 张只有 1 USDT，不到最小名义额，只平不开
        "NOPRICE": -0.02,     # 没有标记价：记失败跳过
    }
    orders, rows = ex.plan(weights, positions, prices, {s: RULE for s in prices}, EQUITY, 1.0, DAY)
    got = [(o.symbol, o.action, o.side, o.quantity, o.position_id, o.request_id) for o in orders]
    assert got == [
        ("CUT", "close", "LONG", Decimal("1.000"), 1, "ft-20261021-CUT-1"),
        ("DUST", "close", "SHORT", None, 6, "ft-20261021-DUST-2"),
        ("FLIP", "close", "SHORT", None, 2, "ft-20261021-FLIP-2"),
        # 下架的币没有价格也没有规则，目标是 0：照样全平
        ("GONE", "close", "LONG", None, 5, "ft-20261021-GONE-1"),
        ("OUT", "close", "LONG", None, 3, "ft-20261021-OUT-1"),
        ("FLIP", "open", "LONG", Decimal("0.500"), None, "ft-20261021-FLIP-3"),
        ("NEW", "open", "LONG", Decimal("1.234"), None, "ft-20261021-NEW-3"),
    ]
    by = {r["symbol"]: r for r in rows}
    assert by["NEW"]["target"] == 1.234 and by["NEW"]["notional"] == pytest.approx(123.456)
    assert by["FLIP"]["current"] == -1.0 and by["TINY"]["note"] == "差额太小，不动"
    assert by["DUST"]["note"] == "要加的量不到最小下单量或最小名义额" and not by["DUST"]["failed"]
    assert by["NOPRICE"]["failed"] and by["NOPRICE"]["target"] is None


def test_execute_keeps_going_and_skips_the_open_after_a_failed_close():
    fake = FakeSim(lambda s: 100.0)
    fake.hold("BAD", "SHORT", 1.0, 4)
    fake.fail = {"BAD", "NOPE"}
    sim = fake.sim()
    orders = [ex.Order("BAD", "close", "SHORT", None, 1, "ft-1-BAD-2"),
              ex.Order("BAD", "open", "LONG", Decimal("0.5"), None, "ft-1-BAD-3"),
              ex.Order("NOPE", "open", "SHORT", Decimal("1"), None, "ft-1-NOPE-4"),
              ex.Order("OK", "open", "LONG", Decimal("0.25"), None, "ft-1-OK-3")]
    out: list = []
    ex.execute(sim, 42, orders, sim.positions(42), 4, out)
    assert [(o["symbol"], o["action"], o["ok"]) for o in out] == [
        ("BAD", "close", False), ("BAD", "open", False), ("NOPE", "open", False), ("OK", "open", True)]
    assert "1601" in out[0]["error"] and out[1]["error"] == "这个币的平仓或调杠杆没成，不开新仓"
    # 反手那笔根本没发出去；发出去的都是全仓市价，带当天的杠杆和 clientRequestId
    assert [b["clientRequestId"] for _, b in fake.calls] == ["ft-1-BAD-2", "ft-1-NOPE-4", "ft-1-OK-3"]
    assert fake.calls[-1][1] == {"symbol": "OK", "side": "LONG", "marginMode": "CROSS", "leverage": 4,
                                 "orderType": "MARKET", "quantity": 0.25, "clientRequestId": "ft-1-OK-3"}
    assert out[3] == {"symbol": "OK", "action": "open", "side": "LONG", "quantity": 0.25, "requestId": "ft-1-OK-3",
                      "ok": True, "orderId": 3, "filled": 0.25, "price": 100.0, "commission": 0}
    # 全仓净值 = 余额 + Σ浮盈亏
    assert ex.equity({"balance": 1000}, [{"unrealizedPnl": 12.5}, {"unrealizedPnl": None}]) == 1012.5


def test_execute_moves_the_coins_still_held_to_the_day_leverage_before_opening():
    fake = FakeSim(lambda s: 100.0)
    # 昨天 4 倍（仓位 1 到 5）：ADD 要加仓、CUT 减仓、GONE 全平、STUCK 调杠杆会失败；SAME 已经是 8 倍。今天 8 倍
    for s, side, q, leverage in (("ADD", "LONG", 1.0, 4), ("CUT", "LONG", 2.0, 4), ("GONE", "SHORT", 1.0, 4),
                                 ("STUCK", "LONG", 1.0, 4), ("SAME", "SHORT", 1.0, 8)):
        fake.hold(s, side, q, leverage)
    fake.fail = {"STUCK"}
    sim = fake.sim()
    orders = [ex.Order("CUT", "close", "LONG", Decimal("1"), 2, "ft-1-CUT-1"),
              ex.Order("GONE", "close", "SHORT", None, 3, "ft-1-GONE-2"),
              ex.Order("ADD", "open", "LONG", Decimal("1"), None, "ft-1-ADD-3"),
              ex.Order("STUCK", "open", "LONG", Decimal("1"), None, "ft-1-STUCK-3"),
              ex.Order("NEW", "open", "SHORT", Decimal("1"), None, "ft-1-NEW-4")]
    out: list = []
    ex.execute(sim, 42, orders, sim.positions(42), 8, out)
    # 平完再调：全平掉的和已经是 8 倍的不调；没调成的不加仓（sim 同一个币杠杆不一样会拒）
    assert [(o["symbol"], o["action"], o["ok"]) for o in out] == [
        ("CUT", "close", True), ("GONE", "close", True), ("ADD", "leverage", True), ("CUT", "leverage", True),
        ("STUCK", "leverage", False), ("ADD", "open", True), ("STUCK", "open", False), ("NEW", "open", True)]
    assert out[2] == {"symbol": "ADD", "action": "leverage", "side": None, "quantity": None, "requestId": None,
                      "leverage": 8, "ok": True}
    assert "1601" in out[4]["error"] and out[6]["error"] == "这个币的平仓或调杠杆没成，不开新仓"
    assert {p["symbol"]: p["leverage"] for p in fake.positions.values()} == {"ADD": 8, "CUT": 8, "STUCK": 4, "SAME": 8,
                                                                            "NEW": 8}


def test_order_resends_the_same_id_while_the_outcome_is_unknown():
    sent: list[str] = []
    replies = iter([httpx.ConnectError("断了"), {"code": 1106, "msg": "处理中"}, {"code": 0, "data": {"orderId": 9}}])

    def handle(request: httpx.Request) -> httpx.Response:
        assert request.headers["X-Internal-Token"] == "tok"
        sent.append(request.read().decode())
        reply = next(replies)
        if isinstance(reply, Exception):
            raise reply
        return httpx.Response(200, json=reply)

    sim = ex.Sim("http://sim/", "tok", httpx.Client(transport=httpx.MockTransport(handle)))
    assert sim.close(42, 7, None, "ft-x-1")["orderId"] == 9
    # 连不上、处理中都是结果未知：同一个 ID 再发，问到结果为止；全平不带数量
    assert len(sent) == 3 and len(set(sent)) == 1 and '"quantity":null' in sent[0]
    # 三次都问不到：报结果未知
    replies = iter([httpx.ReadTimeout("慢")] * 3)
    with pytest.raises(ex.SimError) as info:
        sim.close(42, 7, None, "ft-x-1")
    assert info.value.unknown
    # sim 抢不到仓位锁：单没执行，同一个 ID 再发
    sent.clear()
    replies = iter([{"code": 1105, "msg": "处理中"}, {"code": 0, "data": {"orderId": 10}}])
    assert sim.close(42, 7, None, "ft-x-2")["orderId"] == 10 and len(sent) == 2 and len(set(sent)) == 1
    # 调杠杆抢不到锁也一样再发
    sent.clear()
    replies = iter([{"code": 1105, "msg": "处理中"}, {"code": 0, "data": None}])
    sim.leverage(42, "X", 8)
    assert len(sent) == 2 and len(set(sent)) == 1
    # 明确的业务失败不重发；HTTP 出错（token 不对）也直接报
    sent.clear()
    replies = iter([{"code": 1759, "msg": "未配置"}])
    with pytest.raises(ex.SimError) as info:
        sim.open(42, "X", "LONG", Decimal("1"), "ft-x-3", 4)
    assert not info.value.unknown and len(sent) == 1 and "1759" in str(info.value)
    wrong = ex.Sim("http://sim", "wrong", httpx.Client(transport=httpx.MockTransport(FakeSim(lambda s: 1.0).handle)))
    with pytest.raises(ex.SimError, match="HTTP 401"):
        wrong.ensure_account()


def test_rules_come_from_exchange_info():
    info = {"X": {"filters": [{"filterType": "PRICE_FILTER", "tickSize": "0.1"},
                              {"filterType": "LOT_SIZE", "stepSize": "0.01", "minQty": "0.02", "maxQty": "100"},
                              {"filterType": "MIN_NOTIONAL", "notional": "20"}]},
            "Y": {"filters": []}}
    assert ex.rules(info) == {"X": ex.Rule(Decimal("0.01"), Decimal("0.02"), Decimal("20"))}
