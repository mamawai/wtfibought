import json

import httpx
import pandas as pd
import pytest
from conftest import POOL_SYMBOLS
from fakes import FakeFapi, FakeSim
from test_api import ADMIN, bare, client, data, prepared, wait_until  # noqa: F401  fixtures

from wiib_alpha import main as m
from wiib_alpha.data import binance as b
from wiib_alpha.data.binance import DAY_MS
from wiib_alpha.data.panel import FEATURE_COLUMNS, PanelStore
from wiib_alpha.data.refresh import RefreshResult
from wiib_alpha.dsl import parse
from wiib_alpha.trade import attrib, executor, scheduler

# 2025-03-01 00:00 UTC：出信号的是 2 月最后一根，币池按 2 月（测试的池子表有 2025-01 ~ 03）
DAY = 1_740_787_200_000
NOW = DAY + 20 * 60_000


@pytest.fixture(autouse=True)
def no_rest_gap(monkeypatch):
    monkeypatch.setattr(b, "REST_GAP_S", 0.0)
    monkeypatch.setattr(b, "DATA_GAP_S", 0.0)
    monkeypatch.setattr(b, "SPOT_GAP_S", 0.0)


async def first_frame(stream=None) -> dict:
    """交易员页实时推送的一帧：流不会自己结束，直接取生成器的下一帧；没给流就开一条、取完关掉"""
    own = stream is None
    stream = m._live(m.app) if own else stream
    try:
        frame = await anext(stream)
    finally:
        if own:
            await stream.aclose()
    return json.loads(frame.removeprefix("data: "))


async def add_factor(fid: str, name: str, expr: str, approved_at: int = 1) -> None:
    e = parse(expr, FEATURE_COLUMNS)
    await m.app.state.store.add_factor({"id": fid, "name": name, "hypothesis": "h", "expr": expr, "canonical": e.canonical(),
                                        "columns": sorted(e.columns()), "eval": {"horizons": {}}, "gates": [],
                                        "approved_at": approved_at, "run_id": "r"})


@pytest.fixture
async def trader(client, tmp_path, monkeypatch):
    """归档写到 2-27 0 点，假 fapi 往后给到 now；库里一条埋了信号的因子，交易员勾着它；调度的墙钟跟着假 fapi 的 now 走"""
    fake = FakeFapi([*POOL_SYMBOLS, "BTCUSDT"], NOW)
    monkeypatch.setattr(scheduler, "now_ms", lambda: fake.now)
    fake.write_raw(PanelStore(tmp_path), DAY - 28 * DAY_MS, DAY - 2 * DAY_MS)
    market = FakeSim(fake.mark)
    m.app.state.fapi, m.app.state.sim = fake.binance(), market.sim()
    await add_factor("f-vol", "vol4", "Mean($volume, 4)")
    await m.app.state.store.set_trade_factor("f-vol", True)
    return fake, market


async def test_two_days_of_rebalancing_and_the_pages_that_show_it(client, trader):
    fake, market = trader
    store = m.app.state.store
    # 库里再放一条反号的、交易员没勾：调仓不用它，看板照样算
    await add_factor("f-inv", "inv8", "-Mean($volume, 8)", approved_at=2)
    assert data(await client.get("/api/alpha/board")) is None
    await scheduler.rebalance(m.app, m._library, NOW)
    run = await store.trade_run("2025-03-01")
    assert run["status"] == "done", run["error"]
    assert (run["signal_ms"], run["user_id"], run["equity_before"]) == (DAY - 1, 42, 10000)
    assert [f["name"] for f in run["factors"]] == ["vol4"] and run["factors"][0]["weight"] > 0
    # 24 个币：每条腿多空各进头尾 1/5，4 个
    assert all(len(run["legs"][leg][side]) == 4 for leg in ("combo", "carry") for side in ("long", "short"))
    # live 尾巴从归档之后补到现在，缺口两天多，K 线 1000 根一窗；币池加上 BTC 基准。下完单每个币再拉一根 00:15 的（limit 99）
    klines = [p for path, p in fake.log if path == "/fapi/v1/klines"]
    assert {p["symbol"] for p in klines if p["limit"] == "1000"} == {*POOL_SYMBOLS, "BTCUSDT"}
    assert {p["limit"] for p in klines} == {"1000", "99"}
    # 第一天全是开仓：4 倍全仓市价，数量 = |净权重| × 净值 ÷ 标记价、按步长向零取整
    coins = {c["symbol"]: c for c in run["targets"]["coins"]}
    assert market.calls and all(a == "open" and body["leverage"] == 4 and body["marginMode"] == "CROSS"
                                for a, body in market.calls)
    for _, body in market.calls:
        c = coins[body["symbol"]]
        assert body["clientRequestId"] == f"ft-20250301-{body['symbol']}-{3 if c['weight'] > 0 else 4}"
        assert 0 <= abs(c["weight"]) * 10000 / c["price"] - body["quantity"] < 0.001
        assert c["weight"] == pytest.approx(c["combo"] + c["carry"])
        # 00:15 那根的收盘价记下来对成交价
        assert c["refPrice"] == pytest.approx(fake.close(body["symbol"], DAY))
    assert sum(1 for o in run["orders"] if o["ok"]) == len(market.calls) == len(coins)
    assert run["equity_after"] == pytest.approx(10000)
    # 补数据之前就把当月在交易的币登记给 sim（feed 据此订行情），下单时都订上了
    assert market.watched == [sorted(POOL_SYMBOLS)]

    # 因子看板：库里全部在役因子按入库先后编号，各自按学到的方向排全池强弱，产品币落在头尾 1/5 是看多看空
    products = list(m.app.state.data.products)
    board = run["board"]
    assert board["coins"] == products
    assert [(f["no"], f["name"], f["sign"], f["live"]) for f in board["factors"]] == [(1, "vol4", 1.0, True),
                                                                                      (2, "inv8", -1.0, True)]
    assert all(f["weight"] for f in board["factors"]) and set(board["combo"]) == set(products)
    for p in products:
        view = board["factors"][0]["stances"][p]
        assert view["of"] == len(POOL_SYMBOLS)
        assert view["stance"] == ("long" if view["rank"] <= 4 else "short" if view["rank"] >= 21 else "neutral")
        # 第一天没有缓冲：只勾了 vol4，它看多看空的产品币就是组合腿做多做空的
        assert (view["stance"] == "long") == (p in run["legs"]["combo"]["long"])
        assert (view["stance"] == "short") == (p in run["legs"]["combo"]["short"])
    # 公开接口：访客只看到编号、进没进组合、算没算得了和看法；管理员看得到名字和表达式
    guest = data(await client.get("/api/alpha/board"))
    assert guest["day"] == "2025-03-01" and guest["signalMs"] == DAY - 1 and guest["coins"] == products
    assert [set(f) for f in guest["factors"]] == [{"no", "live", "inCombo", "stances"}] * 2
    assert guest["factors"][0]["stances"] == board["factors"][0]["stances"] and guest["combo"] == board["combo"]
    admin = data(await client.get("/api/alpha/board", headers=ADMIN))
    assert [(f["name"], f["expr"], f["inCombo"]) for f in admin["factors"]] == [("vol4", "Mean($volume, 4)", True),
                                                                                ("inv8", "-Mean($volume, 8)", True)]

    # 页面：账户、实时数（持仓带两条腿各占多少）、调仓记录与详情
    account = data(await client.get("/api/alpha/trader/account", headers=ADMIN))
    assert account["userId"] == 42 and account["initialBalance"] == 10000
    # 访客看到的账户跟管理员一样，只是调仓任务里不带 error
    guest = data(await client.get("/api/alpha/trader/account"))
    assert guest == {**account, "job": {k: v for k, v in account["job"].items() if k != "error"}}
    live = await first_frame()
    assert (live["ok"], live["userId"], live["day"], live["dayStart"], live["available"]) == (True, 42, "2025-03-01",
                                                                                              10000, 10000)
    assert live["equity"] == pytest.approx(10000) and live["dayPnl"] == pytest.approx(0)
    assert live["positionsDay"] == "2025-03-01" and len(live["positions"]) == len(coins)
    for p in live["positions"]:
        c = coins[p["symbol"]]
        assert p["side"] == ("LONG" if c["weight"] > 0 else "SHORT")
        assert p["legs"] == {"combo": pytest.approx(c["combo"] * 10000), "carry": pytest.approx(c["carry"] * 10000)}
    detail = data(await client.get("/api/alpha/trader/runs/2025-03-01", headers=ADMIN))
    assert detail["orderCount"] == len(coins) and detail["failedCount"] == 0 and detail["factorNames"] == ["vol4"]
    assert set(detail["targets"]["signals"]) == {"combo", "carry"} and detail["targets"]["notTrading"] == []
    assert (await client.get("/api/alpha/trader/runs/2099-01-01", headers=ADMIN)).status_code == 404

    # 第二天：价格动了、信号动了；先平后开，上一期留在缓冲区里的币不换
    fake.now += DAY_MS
    fake.halted = {"X00"}
    market.calls.clear()
    await scheduler.rebalance(m.app, m._library, NOW + DAY_MS)
    second = await store.trade_run("2025-03-02")
    assert second["status"] == "done", second["error"]
    actions = [a for a, _ in market.calls]
    assert "close" in actions and actions == sorted(actions, key=lambda a: a != "close")
    assert all(body["clientRequestId"].startswith("ft-20250302-") for _, body in market.calls)
    # 不在交易的币不出目标、不登记，持着的平掉（要平的币 sim 按仓位自己算进集合）
    assert second["targets"]["notTrading"] == ["X00"] and "X00" not in second["legs"]["combo"]["long"]
    assert market.watched[1] == sorted(set(POOL_SYMBOLS) - {"X00"})
    runs = data(await client.get("/api/alpha/trader/runs", headers=ADMIN))
    assert [r["day"] for r in runs["items"]] == ["2025-03-02", "2025-03-01"] and runs["job"]["running"] is False
    # 每日盈亏：03-01 那天从它调仓前算到 03-02 调仓前；03-02 还没完，在实时数里。归因还没算
    daily = data(await client.get("/api/alpha/trader/daily"))
    assert [(d["day"], d["start"], d["end"], d["attrib"]) for d in daily["days"]] == [
        ("2025-03-01", 10000, second["equity_before"], None)]
    assert daily["legs"]["combo"]["days"] == 0 and daily["legs"]["carry"]["hit"] is False
    live = await first_frame()
    assert (live["day"], live["dayStart"], live["positionsDay"]) == ("2025-03-02", second["equity_before"], "2025-03-02")

    # 01:00 之后的一次 tick 把 03-01 按腿归因：建仓价、平仓价是两天 00:15 那根的收盘，资金费是持有期里三次结算，
    # 换仓成本按当月档；实际是 03-02 调仓前的净值减 10000
    fake.log.clear()
    await scheduler.tick(m.app, m._library, m._start_refresh, NOW + DAY_MS + 60 * 60_000)
    day1 = (await store.trade_run("2025-03-01"))["attrib"]
    assert day1["until"] == "2025-03-02" and day1["actual"] == pytest.approx(second["equity_before"] - 10000)
    assert day1["missing"] == [] and {c["symbol"] for c in day1["coins"]} == set(coins)
    row = next(c for c in day1["coins"] if c["symbol"] == "AAA")
    assert (row["entry"], row["exit"]) == (pytest.approx(fake.close("AAA", DAY)), pytest.approx(fake.close("AAA", DAY + DAY_MS)))
    assert row["fund"] == pytest.approx(sum(fake.rate("AAA", DAY + k * 8 * 3_600_000) for k in (1, 2, 3)))
    legs = day1["legs"]
    for leg in attrib.LEGS:
        # 第一天全是新开：前 20 个币一档 5 bps、其余二档 8 bps
        assert legs[leg]["cost"] == pytest.approx(sum(
            abs(c[leg]) * 10000 * (0.0005 if c["symbol"] in POOL_SYMBOLS[:20] else 0.0008) for c in day1["coins"]))
    assert day1["model"] == pytest.approx(legs["combo"]["net"] + legs["carry"]["net"])
    assert day1["residual"] == pytest.approx(day1["actual"] - day1["model"])
    # 每日盈亏带归因（不带每币明细），两条腿累计对止损线；调仓详情带每币明细；算过的不再算
    daily = data(await client.get("/api/alpha/trader/daily"))
    assert daily["days"][0]["attrib"]["legs"] == day1["legs"] and "coins" not in daily["days"][0]["attrib"]
    assert daily["legs"] == attrib.summary([day1])
    detail = data(await client.get("/api/alpha/trader/runs/2025-03-01", headers=ADMIN))
    assert detail["attrib"]["coins"] == day1["coins"]
    fetched = len(fake.log)
    await scheduler.tick(m.app, m._library, m._start_refresh, NOW + DAY_MS + 61 * 60_000)
    assert len(fake.log) == fetched

    # sim 不通：推一帧 ok 是 false 的，页面停在上一帧
    m.app.state.sim = executor.Sim("http://sim", "tok", httpx.Client(transport=httpx.MockTransport(
        lambda r: (_ for _ in ()).throw(httpx.ConnectError("连不上")))))
    live = await first_frame()
    assert live["ok"] is False and set(live) == {"ts", "ok"}


async def test_carry_leg_can_be_switched_off_and_its_share_stays_idle(client, trader):
    fake, market = trader
    assert data(await client.get("/api/alpha/trader/factors", headers=ADMIN))["carry"] == {
        "enabled": True, "share": 0.25, "changedAt": None}
    assert (await client.put("/api/alpha/trader/carry", json={"enabled": False},
                             headers={"satoken": "user"})).status_code == 403
    off = data(await client.put("/api/alpha/trader/carry", json={"enabled": False}, headers=ADMIN))
    assert off["enabled"] is False and off["changedAt"] is not None
    # 份额单独改，开关不动；超出 0 到 1 的拒绝
    tenth = data(await client.put("/api/alpha/trader/carry", json={"share": 0.1}, headers=ADMIN))
    assert (tenth["enabled"], tenth["share"]) == (False, 0.1)
    assert (await client.put("/api/alpha/trader/carry", json={"share": 1.5}, headers=ADMIN)).status_code == 422
    # 库复评跑着时改份额会让它后半截按旧基线算：拒
    m.app.state.runs.jobs["libraryReeval"]["running"] = True
    assert (await client.put("/api/alpha/trader/carry", json={"share": 0.2}, headers=ADMIN)).status_code == 409
    m.app.state.runs.jobs["libraryReeval"]["running"] = False
    await scheduler.rebalance(m.app, m._library, NOW)
    run = await m.app.state.store.trade_run("2025-03-01")
    assert run["status"] == "done", run["error"]
    # carry 腿空仓，那天记下的份额是改过的
    assert run["targets"]["carry"] is False and run["targets"]["carryShare"] == 0.1
    assert run["legs"]["carry"] == {"long": [], "short": []}
    assert data(await client.get("/api/alpha/trader/factors", headers=ADMIN))["carry"]["enabled"] is False


async def test_sizing_scales_the_notional_and_moves_held_coins_to_the_new_leverage(client, trader):
    fake, market = trader
    store = m.app.state.store
    # 没改过是 4 倍、保证金一半（名义和回测一样）；还没出过目标就没有预览
    assert data(await client.get("/api/alpha/trader/sizing", headers=ADMIN)) == {
        "leverage": 4, "margin": 0.5, "changedAt": None, "maxMargin": 0.95, "preview": None, "previewError": None}
    assert (await client.put("/api/alpha/trader/sizing", json={"leverage": 8, "margin": 0.5},
                             headers={"satoken": "user"})).status_code == 403
    assert (await client.put("/api/alpha/trader/sizing", json={"leverage": 8, "margin": 0.96},
                             headers=ADMIN)).status_code == 422
    saved = data(await client.put("/api/alpha/trader/sizing", json={"leverage": 8, "margin": 0.5}, headers=ADMIN))
    assert (saved["leverage"], saved["margin"]) == (8, 0.5) and saved["changedAt"] is not None
    await scheduler.rebalance(m.app, m._library, NOW)
    run = await store.trade_run("2025-03-01")
    assert run["status"] == "done", run["error"]
    # 8 倍、保证金一半：名义是回测的 2 倍；权重还是 1 倍名义的
    sized = run["targets"]["sizing"]
    assert (sized["leverage"], sized["multiple"], sized["caps"]["bracket"]) == (8, 2.0, 10)
    assert sized["setting"] == {"leverage": 8, "margin": 0.5}
    coins = {c["symbol"]: c for c in run["targets"]["coins"]}
    assert market.calls and all(a == "open" and body["leverage"] == 8 for a, body in market.calls)
    for _, body in market.calls:
        c = coins[body["symbol"]]
        assert c["weight"] == pytest.approx(c["combo"] + c["carry"]) and c["notional"] == pytest.approx(2 * c["weight"] * 10000)
        assert 0 <= 2 * abs(c["weight"]) * 10000 / c["price"] - body["quantity"] < 0.001
    # 实时数里两条腿的目标名义也是 2 倍；预览按这次的篮子和净值算
    for p in (await first_frame())["positions"]:
        c = coins[p["symbol"]]
        assert p["legs"] == {"combo": pytest.approx(2 * c["combo"] * 10000), "carry": pytest.approx(2 * c["carry"] * 10000)}
    preview = data(await client.get("/api/alpha/trader/sizing", headers=ADMIN))["preview"]
    assert preview == {**{k: sized[k] for k in ("leverage", "margin", "multiple", "caps")}, "day": "2025-03-01"}

    # 第二天设 20 倍：表外的币最高 10 倍，按 10 倍下；还拿着的币先调到 10 倍再加仓
    await client.put("/api/alpha/trader/sizing", json={"leverage": 20, "margin": 0.5}, headers=ADMIN)
    fake.now += DAY_MS
    await scheduler.rebalance(m.app, m._library, NOW + DAY_MS)
    second = await store.trade_run("2025-03-02")
    assert second["status"] == "done", second["error"]
    assert (second["targets"]["sizing"]["leverage"], second["targets"]["sizing"]["multiple"]) == (10, 2.5)
    assert any(o["action"] == "leverage" for o in second["orders"]) and all(o["ok"] for o in second["orders"])
    assert {p["leverage"] for p in market.positions.values()} == {10}
    # sim 连不上：设定照样存，预览换成问档位失败的原因
    m.app.state.sim = executor.Sim("http://sim", "tok", httpx.Client(transport=httpx.MockTransport(
        lambda r: (_ for _ in ()).throw(httpx.ConnectError("连不上")))))
    down = data(await client.put("/api/alpha/trader/sizing", json={"leverage": 6, "margin": 0.5}, headers=ADMIN))
    assert (down["leverage"], down["preview"]) == (6, None) and "没有回应" in down["previewError"]


async def test_a_month_missing_from_the_pool_table_fails_the_day_without_trading(client, trader):
    fake, market = trader
    # 4 月底：池子表只到 3 月
    await scheduler.rebalance(m.app, m._library, DAY + 60 * DAY_MS + 20 * 60_000)
    run = await m.app.state.store.trade_run("2025-04-30")
    assert run["status"] == "failed" and "2025-04" in run["error"] and run["user_id"] is None
    assert market.calls == [] and fake.log == []
    assert m.app.state.runs.jobs["trader"]["error"] == run["error"]
    # 没下单就没有这一期的多空集合，下一期的缓冲不拿它算
    assert await m.app.state.store.last_trade_legs() is None


async def test_a_retry_that_reaches_0100_before_ordering_skips_the_day(client, trader):
    fake, market = trader
    # 00:59 开始的重试，补完数据、算完目标已经 01:01
    fake.now = DAY + 61 * 60_000
    await scheduler.rebalance(m.app, m._library, DAY + 59 * 60_000)
    run = await m.app.state.store.trade_run("2025-03-01")
    assert run["status"] == "failed" and "01:00" in run["error"] and market.calls == []


async def test_tick_rebalances_between_0005_and_0100_and_records_equity_every_hour(client, trader, monkeypatch):
    store = m.app.state.store
    ran: list[int] = []
    # 每次调仓的结局：状态和下出去的单
    outcomes = [("failed", []), ("done", [{"action": "open", "ok": True}]),
                ("partial", [{"action": "leverage", "ok": True}, {"action": "open", "ok": False}]),
                ("partial", [{"action": "open", "ok": True}, {"action": "open", "ok": False}])]

    async def rebalance(app, library, now):
        ran.append(now)
        status, orders = outcomes.pop(0)
        await store.add_trade_run({"day": b.day_name(now), "signal_ms": 0, "started_at": now,
                                   "finished_at": now, "status": status, "user_id": 42, "orders": orders})

    monkeypatch.setattr(scheduler, "rebalance", rebalance)
    t0 = DAY + 5 * 60_000
    # 00:05 之前、刷新中都不调
    await scheduler.tick(m.app, m._library, m._start_refresh, t0 - 60_000)
    m.app.state.runs.jobs["refresh"]["running"] = True
    await scheduler.tick(m.app, m._library, m._start_refresh, t0)
    m.app.state.runs.jobs["refresh"]["running"] = False
    assert ran == [] and await store.trade_equity() == []
    # 一笔单都没下出去就失败了：下一分钟再来；调成了当天不再调。调过仓有了账户：每个小时记一次净值
    await scheduler.tick(m.app, m._library, m._start_refresh, t0)
    await scheduler.tick(m.app, m._library, m._start_refresh, t0 + 60_000)
    await scheduler.tick(m.app, m._library, m._start_refresh, t0 + 120_000)
    assert ran == [t0, t0 + 60_000]
    # 下了单但一笔都没成（只调成了杠杆不算）：下一分钟再来；成了一笔的当天不重跑
    day2 = t0 + DAY_MS
    await scheduler.tick(m.app, m._library, m._start_refresh, day2)
    await scheduler.tick(m.app, m._library, m._start_refresh, day2 + 60_000)
    await scheduler.tick(m.app, m._library, m._start_refresh, day2 + 120_000)
    assert ran == [t0, t0 + 60_000, day2, day2 + 60_000]
    # 01:00 之后还没调过：跳过这一天，不拿 0 点的信号隔几小时再下
    day3 = DAY + 2 * DAY_MS + 61 * 60_000
    await scheduler.tick(m.app, m._library, m._start_refresh, day3)
    assert ran == [t0, t0 + 60_000, day2, day2 + 60_000] and await store.trade_run(b.day_name(day3)) is None
    curve = await store.trade_equity()
    assert [p["ts"] for p in curve] == [t0 + 60_000, day2, day3]
    assert curve[0] == {"ts": t0 + 60_000, "equity": 10000, "balance": 10000, "long_notional": 0, "short_notional": 0}


async def test_tick_refreshes_after_1400_utc_until_the_month_has_its_pool(client, monkeypatch):
    store, jobs = m.app.state.store, m.app.state.runs.jobs
    hour = scheduler.HOUR_MS
    # 刷新的开始时刻跟着调度的时钟走
    clock = {"now": 0}
    monkeypatch.setattr(m, "now_ms", lambda: clock["now"])
    table = pd.DataFrame(await store.universe())
    pulls: list[int] = []

    def pull(cfg, job):
        pulls.append(clock["now"])
        # 前两次归档连不上，第三次排出 4 月的榜单
        if len(pulls) < 3:
            raise RuntimeError("归档连不上")
        return RefreshResult(pd.concat([table, table[table["month"] == "2025-03"].assign(month="2025-04")]), {})

    monkeypatch.setattr(m, "_refresh_panels", pull)

    async def tick(at: int) -> None:
        clock["now"] = at
        await scheduler.tick(m.app, m._library, m._start_refresh, at)

        async def settled():
            return not jobs["refresh"]["running"]

        await wait_until(settled)

    april = DAY + 31 * DAY_MS
    # 3 月的池子在：不刷；4 月 1 日 14 点前不刷
    await tick(DAY + 15 * hour)
    await tick(april + 14 * hour - 60_000)
    # 参照集在跑（刷新接口 409）：下一分钟再看
    jobs["reference"]["running"] = True
    await tick(april + 14 * hour)
    jobs["reference"]["running"] = False
    assert pulls == []
    # 刷失败了隔一小时再起，2 日 14 点前也起；排出 4 月的池子就不再起
    await tick(april + 14 * hour + 60_000)
    assert jobs["refresh"]["error"] == "归档连不上"
    await tick(april + 15 * hour)
    await tick(april + 15 * hour + 60_000)
    await tick(april + DAY_MS + 2 * hour)
    await tick(april + DAY_MS + 4 * hour)
    assert pulls == [april + 14 * hour + 60_000, april + 15 * hour + 60_000, april + DAY_MS + 2 * hour]
    assert await store.has_universe("2025-04") and jobs["refresh"]["error"] is None


async def test_daily_pnl_runs_from_one_rebalance_to_the_next_and_drawdown_uses_hourly_snapshots(client):
    store = m.app.state.store
    # 03-02 那次没走到下单（没有调仓前净值）：03-01 那天一直算到 03-03 调仓前
    for day, before in (("2025-03-01", 10000.0), ("2025-03-02", None), ("2025-03-03", 9700.0), ("2025-03-04", 9894.0)):
        await store.add_trade_run({"day": day, "signal_ms": 0, "started_at": 0, "finished_at": 0, "status": "done",
                                   "user_id": 42, "orders": [], "equity_before": before})
    for ts, equity in ((1, 10000.0), (2, 10500.0), (3, 9450.0), (4, 9800.0)):
        await store.add_trade_equity(ts, equity, equity, 0, 0)
    daily = data(await client.get("/api/alpha/trader/daily"))
    assert daily["initialBalance"] == 10000 and daily["maxDrawdown"] == pytest.approx(0.1)
    assert [(d["day"], d["start"], d["end"]) for d in daily["days"]] == [("2025-03-01", 10000, 9700),
                                                                         ("2025-03-03", 9700, 9894)]
    assert daily["days"][1]["pnl"] == pytest.approx(194) and daily["days"][1]["pct"] == pytest.approx(0.02)


async def test_live_frames_are_shared_and_stop_when_nobody_watches(client, trader, monkeypatch):
    await scheduler.rebalance(m.app, m._library, NOW)
    calls: list[int] = []
    account = scheduler.account
    monkeypatch.setattr(scheduler, "account", lambda sim, user_id: calls.append(user_id) or account(sim, user_id))
    rt = m.app.state.realtime
    a, b = m._live(m.app), m._live(m.app)
    # 第二个人订上时直接拿到第一个人那帧：两个人只问了 sim 一次
    first, second = await first_frame(a), await first_frame(b)
    assert first == second and first["ok"] and calls == [42] and len(rt.queues) == 2
    await a.aclose()
    assert rt.task is not None
    # 最后一个人走了就停
    await b.aclose()
    assert rt.task is None and not rt.queues


async def test_factor_selection_and_compare(client, trader):
    await add_factor("f-noise", "noise", "$trades")
    await add_factor("f-liq", "liq", "Mean($liq_count, 4)")
    items = data(await client.get("/api/alpha/trader/factors", headers=ADMIN))["items"]
    assert {f["id"]: (f["selected"], f["missing"]) for f in items} == {
        "f-vol": (True, []), "f-noise": (False, []), "f-liq": (False, ["liq_count"])}
    # live 尾巴补不出来的列：不能勾
    resp = await client.put("/api/alpha/trader/factors/f-liq", json={"selected": True}, headers=ADMIN)
    assert resp.status_code == 409 and "$liq_count" in resp.json()["msg"]
    assert (await client.put("/api/alpha/trader/factors/nope", json={"selected": True}, headers=ADMIN)).status_code == 404
    assert data(await client.put("/api/alpha/trader/factors/f-noise", json={"selected": True}, headers=ADMIN))["selected"]
    assert data(await client.put("/api/alpha/trader/factors/f-noise", json={"selected": False}, headers=ADMIN)) == {
        "id": "f-noise", "selected": False}

    async def compare(fid: str) -> dict:
        assert data(await client.post(f"/api/alpha/trader/compare/{fid}", headers=ADMIN)) == {"started": True}

        async def done():
            return not data(await client.get("/api/alpha/trader/factors", headers=ADMIN))["compare"]["running"]

        await wait_until(done, 60)
        job = data(await client.get("/api/alpha/trader/factors", headers=ADMIN))["compare"]
        assert job["error"] is None and job["factorId"] == fid
        return job["result"]

    # 去掉埋了信号的那条：只剩 carry，搜索段变差，不给 holdout
    out = await compare("f-vol")
    assert out["action"] == "remove" and out["base"]["factors"] == ["vol4"] and out["alt"]["factors"] == []
    assert out["better"] is False and "holdout" not in out["base"] and "holdout" not in out["alt"]
    assert out["alt"]["search"]["sharpe"] < out["base"]["search"]["sharpe"]
    # 没勾时加上它：搜索段更好，两组的 holdout 才一起给
    await client.put("/api/alpha/trader/factors/f-vol", json={"selected": False}, headers=ADMIN)
    out = await compare("f-vol")
    assert out["action"] == "add" and out["better"] is True
    assert out["base"]["holdout"]["periods"] == out["alt"]["holdout"]["periods"] > 0
    assert (await client.post("/api/alpha/trader/compare/nope", headers=ADMIN)).status_code == 404


async def test_trader_account_is_public_but_factors_are_not_and_holds_off_refresh(client):
    # 账户、每日盈亏、实时数公开；用了哪些因子、调仓明细、因子对比只有管理员
    assert data(await client.get("/api/alpha/trader/account"))["userId"] is None
    assert data(await client.get("/api/alpha/trader/daily", headers={"satoken": "user"})) == {
        "initialBalance": 10000, "maxDrawdown": 0, "days": [], "legs": attrib.summary([])}
    live = await first_frame()
    assert live["ok"] and live["userId"] is None
    # 调仓的出错原文可能带因子表达式，只有管理员拿得到
    jobs = m.app.state.runs.jobs
    jobs["trader"]["error"] = "因子 Mean($volume, 4) 在 T 那根全池没有值"
    assert "error" not in data(await client.get("/api/alpha/trader/account"))["job"]
    assert "error" not in data(await client.get("/api/alpha/trader/account", headers={"satoken": "user"}))["job"]
    assert data(await client.get("/api/alpha/trader/account", headers=ADMIN))["job"]["error"].startswith("因子")
    for path in ("runs", "runs/2025-03-01", "factors"):
        assert (await client.get(f"/api/alpha/trader/{path}", headers={"satoken": "user"})).status_code == 403
        assert (await client.get(f"/api/alpha/trader/{path}")).json()["code"] == 401
    assert (await client.post("/api/alpha/trader/compare/x", headers={"satoken": "user"})).status_code == 403
    for name, word in (("trader", "调仓"), ("compare", "对比")):
        jobs[name]["running"] = True
        resp = await client.post("/api/alpha/data/refresh", headers=ADMIN)
        assert resp.status_code == 409 and word in resp.json()["msg"]
        jobs[name]["running"] = False
    # 刷新中不起对比
    jobs["refresh"]["running"] = True
    resp = await client.post("/api/alpha/trader/compare/x", headers=ADMIN)
    assert resp.status_code == 409 and "刷新" in resp.json()["msg"]
    jobs["refresh"]["running"] = False
