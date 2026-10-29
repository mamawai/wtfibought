import asyncio
import json
import sqlite3
import threading

import httpx
import pytest
from conftest import POOL_SYMBOLS, REFERENCES, planted_universe, write_store
from fakes import LESSONS, gen_reply, model, review_reply

from wiib_alpha import main as m
from wiib_alpha import prompts, reference
from wiib_alpha.auth import Authenticator
from wiib_alpha.config import ModelSpec, config
from wiib_alpha.data.panel import FEATURE_COLUMNS
from wiib_alpha.data.pool import on_grid
from wiib_alpha.data.refresh import RefreshResult
from wiib_alpha.dsl import parse
from wiib_alpha.eval.params import EvalParams, Thresholds
from wiib_alpha.store import SCHEMA
from wiib_alpha.trade import scheduler

ADMIN = {"satoken": "admin"}
# fixture 会把 main.build_models 换成假的，想测真的从这里拿
REAL_BUILD_MODELS = m.build_models


async def idle(app, library, refresh) -> None:
    return None


def sim(request: httpx.Request) -> httpx.Response:
    token = request.headers.get("satoken")
    if token == "admin":
        return httpx.Response(200, json={"code": 0, "msg": "", "data": {"id": 1}})
    if token == "user":
        return httpx.Response(200, json={"code": 0, "msg": "", "data": {"id": 7}})
    return httpx.Response(401)


@pytest.fixture
def prepared(tmp_path, monkeypatch, planted_panels):
    """池子面板、status 与参照网格落到 tmp，池子表先写进 sqlite；环境变量指到 tmp、参照集缩成一条、模型换成假的；
    返回还没进 lifespan 的 app"""
    write_store(tmp_path, planted_panels)
    universe = planted_universe(planted_panels)
    conn = sqlite3.connect(tmp_path / "alpha.sqlite")
    for statement in SCHEMA:
        conn.execute(statement)
    conn.executemany("INSERT INTO universe(month, symbol, rank, avg_quote_volume) VALUES (?, ?, ?, ?)",
                     list(universe.itertuples(index=False)))
    conn.commit()
    conn.close()
    monkeypatch.setenv("ALPHA_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("ALPHA_DB_PATH", str(tmp_path / "alpha.sqlite"))
    monkeypatch.setenv("ALPHA_SYMBOLS", "AAA,BBB,CCC")
    config.cache_clear()
    monkeypatch.setattr(reference, "parsed", lambda: {k: parse(v, ["trades"]) for k, v in REFERENCES.items()})
    monkeypatch.setattr(m, "build_models", lambda cfg: (model(gen_reply), model(review_reply)))
    monkeypatch.setattr(m, "_refresh_panels", lambda cfg, job: RefreshResult(universe, {}))
    # 交易员的后台循环不起：用到它的测试直接调 scheduler 的函数
    monkeypatch.setattr(scheduler, "loop", idle)
    app = m.app
    app.state.params = EvalParams(test_size=96, embargo=4, min_train=500, iterations=200, seed=7, holdout_days=10)
    app.state.thresholds = Thresholds(min_samples=30, admit_min_points=5)
    app.state.authenticator = Authenticator("http://sim", httpx.AsyncClient(transport=httpx.MockTransport(sim)))
    yield app
    config.cache_clear()


@pytest.fixture
async def bare(prepared):
    """还没配模型的服务"""
    async with m.lifespan(prepared):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=prepared), base_url="http://t") as c:
            yield c


@pytest.fixture
async def client(bare):
    """两个角色的模型都配好了：建 campaign 时记下模型名；模型本身是上面换成的假的，不看这份配置"""
    for role in m.ROLES:
        await m.app.state.store.set_model(role, ModelSpec("openai", "", f"fake-{role}", "k", ""))
    return bare


async def events(client: httpx.AsyncClient, run_id: str, after: int = 0) -> list[dict]:
    """流在这一轮不再 running 时结束，ASGI 传输把整条流收完再给"""
    async with client.stream("GET", f"/api/alpha/runs/{run_id}/events", params={"after": after}, headers=ADMIN) as r:
        assert r.status_code == 200 and r.headers["content-type"].startswith("text/event-stream")
        raw = (await r.aread()).decode()
    out = []
    for block in raw.split("\n\n"):
        if not block.strip() or block.startswith(":"):
            continue
        fields = dict(line.split(": ", 1) for line in block.split("\n") if ": " in line)
        out.append({"id": int(fields["id"]), "event": fields["event"], "data": json.loads(fields["data"])})
    return out


def data(resp: httpx.Response):
    body = resp.json()
    assert resp.status_code == 200 and body["code"] == 0, body
    return body["data"]


async def wait_until(check, seconds: float = 20.0):
    for _ in range(int(seconds / 0.05)):
        if await check():
            return
        await asyncio.sleep(0.05)
    raise AssertionError("等超时了")


async def evaluated(client: httpx.AsyncClient, expr: str) -> dict:
    """手填评估是后台任务：提交了等它跑完，拿结果"""
    assert data(await client.post("/api/alpha/eval", json={"expr": expr}, headers=ADMIN)) == {"started": True}

    async def finished():
        return not data(await client.get("/api/alpha/eval", headers=ADMIN))["running"]

    await wait_until(finished, 60)
    job = data(await client.get("/api/alpha/eval", headers=ADMIN))
    assert job["error"] is None and job["finishedAt"] is not None
    return job["result"]


async def campaign_of(client: httpx.AsyncClient, campaign_id: str) -> dict:
    return data(await client.get(f"/api/alpha/campaigns/{campaign_id}", headers=ADMIN))


async def wait_campaign(client: httpx.AsyncClient, campaign_id: str, status: str) -> dict:
    """等 campaign 停下来；一轮的事件流结束时驱动器可能还没记完进度"""
    async def stopped():
        return (await campaign_of(client, campaign_id))["campaign"]["status"] == status

    await wait_until(stopped)
    return await campaign_of(client, campaign_id)


# ========== 一个 campaign 跑一轮，观察区入库 ==========


async def library_ready(client: httpx.AsyncClient) -> dict:
    """库列表接口不等后台算库，轮询到算完再拿"""
    out = {}

    async def done():
        nonlocal out
        out = data(await client.get("/api/alpha/library", headers=ADMIN))
        return not out["computing"]

    await wait_until(done, 120)
    return out


async def test_one_round_campaign_then_approve_from_observation(client, planted_panels):
    assert await library_ready(client) == {"factors": [], "combo": None, "computing": False, "retired": [],
                                           "reeval": {"running": False}, "standard": 1}
    started = data(await client.post("/api/alpha/campaigns", json={"rounds": 1, "k": 2}, headers=ADMIN))
    campaign_id, run_id = started["id"], started["runId"]
    # 每一轮记下当时数据的 holdout 切点：之后复评按它算"它没见过的那段"
    assert (await m.app.state.store.run(run_id))["cut_ms"] == m.app.state.data.cut_ms

    evts = await events(client, run_id)
    trail = [(e["event"], e["data"].get("name") or e["data"].get("stage") or e["data"].get("text"),
              e["data"].get("status"), e["data"].get("reason") or e["data"].get("message")) for e in evts]
    assert evts[0]["event"] == "status", trail
    assert [e["event"] for e in evts][-2:] == ["done", "end"] and evts[-2]["data"] == {"observing": 1}, trail
    assert evts[-1]["data"] == {"status": "done", "lastId": evts[-2]["id"]}
    assert [e["data"] for e in evts if e["event"] == "lessons"] == [{"items": LESSONS}]
    ids = [e["id"] for e in evts if e["event"] != "end"]
    assert ids == sorted(ids) and len(set(ids)) == len(ids)

    state = data(await client.get(f"/api/alpha/runs/{run_id}", headers=ADMIN))
    # planted 评估了两版（$volume 被打回、Mean($volume, 4)），noise 门没过重写两版共三版
    assert state["run"]["status"] == "done" and state["run"]["campaign_id"] == campaign_id and state["trials"] == 5
    by_name = {c["name"]: c for c in state["candidates"]}
    assert by_name["planted"]["status"] == "observing" and by_name["planted"]["brief"]["gates"][2]["passed"]
    assert by_name["planted"]["standard"] == 1
    assert by_name["planted"]["brief"]["holdout"]["horizons"]["12h"]["samples"] > 0
    assert by_name["planted"]["brief"]["horizons"]["12h"]["breakEvenBps"] > 12
    assert by_name["noise"]["status"] == "rejected_gates" and "holdout" not in by_name["noise"]["brief"]
    assert "eval" not in by_name["planted"]

    # 轮数到了：campaign 自己收尾，不再起下一轮
    detail = await wait_campaign(client, campaign_id, "done")
    camp = detail["campaign"]
    assert (camp["done_rounds"], camp["survivors"], camp["stop_reason"]) == (1, 1, "跑满 1 轮")
    # 建的时候两个角色用的模型名记在 campaign 上
    assert (camp["gen_model"], camp["review_model"]) == ("fake-gen", "fake-review")
    assert [(r["id"], r["counts"]) for r in detail["runs"]] == [(run_id, {"observing": 1, "rejected_gates": 1})]
    assert [x["text"] for x in detail["lessons"]] == [x["text"] for x in reversed(LESSONS)]
    listed = data(await client.get("/api/alpha/campaigns", headers=ADMIN))["campaigns"]
    assert [(c["id"], c["status"]) for c in listed] == [(campaign_id, "done")]
    runs = data(await client.get("/api/alpha/runs", headers=ADMIN))
    assert runs["runs"][0]["counts"] == {"observing": 1, "rejected_gates": 1} and runs["trials"] == 5

    # 观察区：只有过门的那条，不自动入库
    items = data(await client.get("/api/alpha/observation", headers=ADMIN))["items"]
    assert [(c["name"], c["run_id"]) for c in items] == [("planted", run_id)]
    assert items[0]["created_at"] == state["run"]["created_at"] and items[0]["brief"]["holdout"]["since"] > 0
    assert data(await client.get("/api/alpha/library", headers=ADMIN))["factors"] == []
    overview = data(await client.get("/api/alpha/overview"))
    assert overview["observing"] == 1 and overview["factors"] == 0

    planted_id = items[0]["id"]
    # 入库条件：先在当前数据上复评过，没复评就是 409，后面几项判不了
    checks = items[0]["admission"]["checks"]
    assert not items[0]["admission"]["ok"] and checks[0] == {"ok": False, "text": "还没在当前这版数据上复评"}
    assert [x["ok"] for x in checks[1:]] == [None] * 4
    resp = await client.post(f"/api/alpha/candidates/{planted_id}/approve", headers=ADMIN)
    assert resp.status_code == 409 and resp.json()["msg"].startswith("还不能入库：还没在当前这版数据上复评")
    await client.post("/api/alpha/observation/reeval", headers=ADMIN)

    async def reevaluated():
        return not data(await client.get("/api/alpha/observation", headers=ADMIN))["reeval"]["running"]

    await wait_until(reevaluated, 60)
    items = data(await client.get("/api/alpha/observation", headers=ADMIN))["items"]
    # 复评全过，没见过那段（holdout）IC 同号且显著：能入库，逐项都过、写着那段的数字
    assert items[0]["admission"]["ok"], items[0]["admission"]
    checks = items[0]["admission"]["checks"]
    assert all(x["ok"] for x in checks) and checks[2]["text"].endswith("个时刻（要 5 个）")
    assert data(await client.post(f"/api/alpha/candidates/{planted_id}/approve", headers=ADMIN)) == {
        "id": planted_id, "name": "planted"}
    # 入过库的不在观察区了：再点入库、丢弃都是 409
    for action in ("approve", "decline"):
        resp = await client.post(f"/api/alpha/candidates/{planted_id}/{action}", headers=ADMIN)
        assert resp.status_code == 409 and resp.json()["code"] == 409
    assert (await client.post("/api/alpha/candidates/nope/approve", headers=ADMIN)).status_code == 404
    assert data(await client.get("/api/alpha/observation", headers=ADMIN))["items"] == []

    # 刚入库：列表立刻有，组合报告在后台算，算完轮询到
    assert data(await client.get("/api/alpha/library", headers=ADMIN))["computing"] is True
    lib = await library_ready(client)
    factors = lib["factors"]
    assert len(factors) == 1 and factors[0]["expr"] == "Mean($volume, 4)" and "eval" not in factors[0]
    assert factors[0]["brief"]["horizons"]["12h"]["meanIc"] > 0.3 and "holdout" in factors[0]["brief"]
    # 入库后库的组合就有了：搜索段三档加 holdout、主档权重、每币最后一根的信号
    combo = lib["combo"]
    assert [w["name"] for w in combo["weights"]] == ["planted"] and combo["weights"][0]["weight"] > 0
    assert combo["brief"]["horizons"]["12h"]["meanIc"] > 0.3 and combo["brief"]["holdout"]["horizons"]["12h"]["samples"] > 0
    # 最后一个 6h 网格时刻每个在池币的组合信号
    index = planted_panels["AAA"].index.to_numpy()
    assert set(combo["latest"]) == set(POOL_SYMBOLS) and combo["asOf"] == int(index[on_grid(index, 24)].max())
    full = data(await client.get(f"/api/alpha/library/{factors[0]['id']}", headers=ADMIN))
    assert "eval" in full and full["gates"][0]["id"] == "G0"

    recheck = data(await client.post(f"/api/alpha/library/{factors[0]['id']}/recheck", headers=ADMIN))
    assert recheck["horizons"]["48"]["samples"] == 0
    assert data(await client.get(f"/api/alpha/library/{factors[0]['id']}", headers=ADMIN))["recheck"]["since"] == full["approved_at"]

    assert (await client.get("/api/alpha/runs/nope", headers=ADMIN)).status_code == 404
    # 公开概览只有数字：这一轮一条入库一条被门拒
    overview = data(await client.get("/api/alpha/overview"))
    assert overview["trials"] == 5 and overview["factors"] == 1 and overview["observing"] == 0
    assert overview["runs"][0]["id"] == run_id and overview["runs"][0]["counts"] == {"approved": 1, "rejected_gates": 1}
    assert "hypothesis" not in overview["runs"][0]

    # 入库之后它就进了冗余对比集：再评估同一条表达式，G6 认出是库里的那条
    out = await evaluated(client, "Mean($volume, 4)")
    assert out["brief"]["redundancy"]["with"] == "lib:planted" and out["brief"]["redundancy"]["maxAbs"] > 0.99
    assert out["brief"]["redundancy"]["top"][0]["expr"] == "Mean($volume, 4)"
    assert not out["passed"]


def fake_reeval(real: dict, cut_ms: int, mean: float = 0.05, t: float | None = 5.0, samples: int = 100,
                search: float = 0.04, passed: bool = True, standard: int | None = 1) -> dict:
    """拿一份真评估改出能喂给 admission 的复评：主档 24h 的搜索段 IC、holdout 的起点、逐点 t 和口径版本按参数改"""
    ev = json.loads(json.dumps(real["eval"]))
    ev["standard"] = standard
    ev["horizons"]["96"]["ic"]["mean"] = search
    ev["holdout"] = {"since": cut_ms, "at": 1, "horizons": {"96": {"mean": mean, "icir": None, "se": None, "t": t,
                                                                  "months": 3, "samples": samples, "monotonic": None}}}
    return {"at": 1, "direction": 1, "asked": False, "passed": passed, "reason": None if passed else "G2 x", "peers": [],
            "gates": real["gates"], "eval": ev}


async def test_admission_rule_and_duplicate_names(client):
    """入库条件逐项拒：没过门、没见过那段太短、反号、不显著、数据刷新过；丢弃的不入库；同名的入库时加序号"""
    store = m.app.state.store
    cut = m.app.state.data.cut_ms
    real = await evaluated(client, "$volume")
    for run_id, cid in (("r1", "r1-1"), ("r2", "r2-1"), ("r3", "r3-1")):
        await store.create_run(run_id, 1, "", "zh", None)
        await store.upsert_candidate({"id": cid, "run_id": run_id, "seq": 1, "name": "carry", "hypothesis": "h",
                                      "expr": "$volume", "canonical": "$volume", "status": "observing"})
    assert data(await client.post("/api/alpha/candidates/r1-1/decline", headers=ADMIN)) == {"id": "r1-1"}
    assert (await store.candidate("r1-1"))["status"] == "declined"

    async def refused(reeval: dict) -> str:
        await store.set_reeval("r2-1", reeval)
        resp = await client.post("/api/alpha/candidates/r2-1/approve", headers=ADMIN)
        assert resp.status_code == 409
        item = next(c for c in data(await client.get("/api/alpha/observation", headers=ADMIN))["items"] if c["id"] == "r2-1")
        first = next(x["text"] for x in item["admission"]["checks"] if not x["ok"])
        assert not item["admission"]["ok"] and resp.json()["msg"] == "还不能入库：" + first
        return first

    assert await refused(fake_reeval(real, cut - 1)) == "还没在当前这版数据上复评"
    assert await refused(fake_reeval(real, cut, passed=False)) == "复评没过门：G2 x"
    assert await refused(fake_reeval(real, cut, samples=4)) == "没见过那段只有 4 个时刻，要 5 个"
    # 时刻太少算不出 t：同号和 t 两项判不了，不说成复评版本老
    await refused(fake_reeval(real, cut, samples=4, t=None))
    item = next(c for c in data(await client.get("/api/alpha/observation", headers=ADMIN))["items"] if c["id"] == "r2-1")
    assert [x["ok"] for x in item["admission"]["checks"]] == [True, True, False, None, None]
    assert await refused(fake_reeval(real, cut, mean=-0.05, t=-5.0)) == "没见过那段主档 IC 和搜索段的 0.0400 反号（t -5.0）"
    assert await refused(fake_reeval(real, cut, t=1.7)) == "没见过那段主档 IC 的 t 1.7 不到 2"
    assert (await refused(fake_reeval(real, cut, t=None))).startswith("没见过那段算不出主档 IC 的 t")
    # 复评是别的版本口径算的（门槛、份额改过都算），或者是没有版本号的老结果：得重新复评
    assert await refused(fake_reeval(real, cut, standard=7)) == "复评是按第 7 版口径算的，现在是第 1 版，再复评一次"
    assert await refused(fake_reeval(real, cut, standard=None)) == "复评是按没有版本号的旧口径算的，现在是第 1 版，再复评一次"
    assert (await store.candidate("r2-1"))["status"] == "observing"

    await store.set_reeval("r2-1", fake_reeval(real, cut))
    await store.set_reeval("r3-1", fake_reeval(real, cut))
    assert data(await client.post("/api/alpha/candidates/r2-1/approve", headers=ADMIN))["name"] == "carry"
    assert data(await client.post("/api/alpha/candidates/r3-1/approve", headers=ADMIN))["name"] == "carry_2"
    factors = {f["id"]: f for f in await store.factors()}
    assert set(factors) == {"r2-1", "r3-1"} and factors["r3-1"]["run_id"] == "r3" and factors["r3-1"]["columns"] == ["volume"]
    assert {(t["run_id"], t["outcome"]) for t in await store.tried()} == {("r2", "approved"), ("r3", "approved")}
    # 退役的名字也算占用
    await store.retire_factor("r2-1", "过期")
    await store.create_run("r4", 1, "", "zh", None)
    await store.upsert_candidate({"id": "r4-1", "run_id": "r4", "seq": 1, "name": "carry", "hypothesis": "h",
                                  "expr": "$volume", "canonical": "$volume", "status": "observing"})
    await store.set_reeval("r4-1", fake_reeval(real, cut))
    assert data(await client.post("/api/alpha/candidates/r4-1/approve", headers=ADMIN))["name"] == "carry_3"


async def test_retire_and_restore(client):
    """退役：库、组合、交易员里都没它，规范形还算 G8 的已知，tried 记着原因；能恢复，交易员的勾选不跟着恢复"""
    store = m.app.state.store
    ev = await evaluated(client, "Mean($volume, 4)")
    await store.add_factor({"id": "f1", "name": "vol4", "hypothesis": "h", "expr": "Mean($volume, 4)",
                            "canonical": parse("Mean($volume, 4)", ["volume"]).canonical(), "columns": ["volume"],
                            "eval": ev["eval"], "gates": ev["gates"], "approved_at": 1, "run_id": "old"})
    await store.set_trade_factor("f1", True)
    url = "/api/alpha/library/f1"
    assert (await client.post(url + "/retire", json={"reason": "  "}, headers=ADMIN)).status_code == 400
    assert data(await client.post(url + "/retire", json={"reason": "单腿净收益为负"}, headers=ADMIN)) == {"id": "f1"}
    assert (await client.post(url + "/retire", json={"reason": "又一次"}, headers=ADMIN)).status_code == 409
    lib = data(await client.get("/api/alpha/library", headers=ADMIN))
    assert lib["factors"] == [] and lib["combo"] is None
    assert [(f["id"], f["retire_reason"]) for f in lib["retired"]] == [("f1", "单腿净收益为负")] and lib["retired"][0]["retired_at"]
    # 交易员：勾选取消了，勾不回去，也不能拿来对比
    assert await store.trade_factor_ids() == set()
    assert data(await client.get("/api/alpha/trader/factors", headers=ADMIN))["items"] == []
    assert (await client.put("/api/alpha/trader/factors/f1", json={"selected": True}, headers=ADMIN)).status_code == 409
    assert (await client.post("/api/alpha/trader/compare/f1", headers=ADMIN)).status_code == 409
    # 出题看得到它退役了和原因；换个窗口再交同一结构，G8 不再当已知，G6 也不再拿它比
    assert [(t["outcome"], t["reason"]) for t in await store.tried()] == [("retired", "单腿净收益为负")]
    again = await evaluated(client, "Mean($volume, 8)")
    assert again["gates"][8]["id"] == "G8" and again["gates"][8]["passed"]
    assert again["brief"]["redundancy"]["with"] != "lib:vol4"
    # 恢复：回到库里，tried 改回入库；交易员的勾选不跟着恢复
    assert data(await client.post(url + "/restore", headers=ADMIN)) == {"id": "f1"}
    assert (await client.post(url + "/restore", headers=ADMIN)).status_code == 409
    assert [f["id"] for f in data(await client.get("/api/alpha/library", headers=ADMIN))["factors"]] == ["f1"]
    assert [t["outcome"] for t in await store.tried()] == ["approved"]
    assert await store.trade_factor_ids() == set()


async def test_two_failed_rounds_in_a_row_fail_the_campaign(client):
    def boom(prompt: str) -> str:
        if "写成一条表达式" in prompt:
            raise RuntimeError("上游挂了")
        return gen_reply(prompt)

    m.app.state.models = (model(boom), model(review_reply))
    started = data(await client.post("/api/alpha/campaigns", json={"rounds": 5, "k": 2}, headers=ADMIN))
    evts = await events(client, started["runId"])
    assert evts[-1]["data"]["status"] == "failed" and any(e["event"] == "error" for e in evts)
    # 停在 proposed 的两条都标成 failed，而且推了事件，开着的页面能看到
    failed = [e["data"]["name"] for e in evts if e["event"] == "candidate" and e["data"]["status"] == "failed"]
    assert sorted(failed) == ["noise", "planted"]
    state = data(await client.get(f"/api/alpha/runs/{started['runId']}", headers=ADMIN))
    assert state["run"]["status"] == "failed"
    assert all(c["status"] == "failed" and c["reason"] == "这一轮没跑完" for c in state["candidates"])
    # 第一轮出错照样起第二轮，第二轮也错就整个停
    detail = await wait_campaign(client, started["id"], "failed")
    assert detail["campaign"]["done_rounds"] == 2 and detail["campaign"]["stop_reason"] == "连续两轮出错"
    assert "上游挂了" in detail["campaign"]["error"] and [r["status"] for r in detail["runs"]] == ["failed", "failed"]


async def test_campaign_is_closed_when_starting_the_next_round_fails(client, monkeypatch):
    """驱动器自己出错：campaign 标成失败，不挂在 running 上挡住新的"""
    async def boom(app, campaign_id, run_id):
        raise RuntimeError("库写不进去")

    monkeypatch.setattr(m, "_next_round", boom)
    m.app.state.models = (model(lambda p: "[]"), model(review_reply))
    started = data(await client.post("/api/alpha/campaigns", json={"rounds": 3, "k": 1}, headers=ADMIN))
    detail = await wait_campaign(client, started["id"], "failed")
    assert detail["campaign"]["stop_reason"] == "起下一轮时出错" and "库写不进去" in detail["campaign"]["error"]
    assert data(await client.post("/api/alpha/campaigns", json={"rounds": 1, "k": 1}, headers=ADMIN))["id"]


async def test_campaign_rolls_into_the_next_round_until_the_count(client):
    # 出不了假设的模型：每轮立刻结束
    m.app.state.models = (model(lambda p: "[]"), model(review_reply))
    started = data(await client.post("/api/alpha/campaigns", json={"rounds": 3, "k": 1, "note": "费率"},
                                     headers=ADMIN))
    detail = await wait_campaign(client, started["id"], "done")
    assert detail["campaign"]["done_rounds"] == 3 and detail["campaign"]["survivors"] == 0
    runs = detail["runs"]
    assert len(runs) == 3 and runs[-1]["id"] == started["runId"]
    assert all(r["status"] == "done" and r["k"] == 1 and r["note"] == "费率" and r["campaign_id"] == started["id"]
               for r in runs)


async def test_next_round_treats_observation_as_known(client):
    """上一轮进了观察区的，下一轮再交一遍同一条：规范形重合按 G8 拒，截面上一模一样按 G6 拒"""
    def same(prompt: str) -> str:
        if "这一轮的候选与结局" in prompt:
            return "[]"
        if "JSON 数组" in prompt:
            return '[{"name": "dup", "hypothesis": "成交量藏着信号"}]'
        return '{"expr": "Mean($volume, 4)", "direction": 1, "note": "均线"}'

    m.app.state.models = (model(same), model(review_reply))
    started = data(await client.post("/api/alpha/campaigns", json={"rounds": 2, "k": 1}, headers=ADMIN))
    detail = await wait_campaign(client, started["id"], "done")
    first, second = reversed(detail["runs"])
    assert first["counts"] == {"observing": 1} and second["counts"] == {"rejected_gates": 1}
    rejected = data(await client.get(f"/api/alpha/runs/{second['id']}", headers=ADMIN))["candidates"][0]
    assert not rejected["gates"][8]["passed"] and "G8" in rejected["reason"]
    assert not rejected["gates"][6]["passed"] and "obs:dup" in rejected["gates"][6]["note"]
    assert rejected["brief"]["redundancy"]["top"][0]["expr"] == "Mean($volume, 4)"


async def test_observation_reeval(client):
    """观察区复评：老候选先问模型补方向；逐条按当前门判，过门的互相查重；结果另存，原来的状态、eval 不动，不计试验数"""
    store = m.app.state.store
    assert (await client.post("/api/alpha/observation/reeval", headers=ADMIN)).status_code == 409
    # 发现它们的那一轮切点比现在早 5 天
    early = m.app.state.data.cut_ms - 5 * 86_400_000
    await store.create_run("old", 1, "", "zh", None, early)
    for seq, (name, expr, direction) in enumerate((("vol_ma", "Mean($volume, 4)", None), ("vol_ema", "EMA($volume, 8)", 1),
                                                    ("trades", "$trades", 1)), 1):
        await store.upsert_candidate({"id": f"old-{seq}", "run_id": "old", "seq": seq, "name": name, "hypothesis": "h",
                                      "expr": expr, "canonical": parse(expr, ["volume", "trades"]).canonical(),
                                      "status": "observing", "direction": direction})
    assert data(await client.post("/api/alpha/observation/reeval", headers=ADMIN)) == {"started": True, "total": 3}

    async def finished():
        return not data(await client.get("/api/alpha/observation", headers=ADMIN))["reeval"]["running"]

    await wait_until(finished, 60)
    out = data(await client.get("/api/alpha/observation", headers=ADMIN))
    assert out["reeval"]["done"] == 3 and out["reeval"]["error"] is None
    items = {c["name"]: c for c in out["items"]}
    assert all(c["status"] == "observing" and "brief" not in c for c in items.values())
    re = {name: c["reeval"] for name, c in items.items()}
    # 老候选没声明方向：问了模型补上；别的用自己声明的
    assert (re["vol_ma"]["asked"], re["vol_ma"]["direction"]) == (True, 1) and re["vol_ema"]["asked"] is False
    assert [g["id"] for g in re["vol_ma"]["gates"]] == [f"G{i}" for i in range(11)]
    # 两条成交量均线截面上几乎一样：只留一条，另一条按同批 G6 拒；成交笔数是噪音，单条就过不了
    kept = [n for n in ("vol_ma", "vol_ema") if re[n]["passed"]]
    assert len(kept) == 1
    other = re[{"vol_ma": "vol_ema", "vol_ema": "vol_ma"}[kept[0]]]
    assert other["reason"].startswith(f"G6 与同批 {kept[0]} 的截面") and not other["gates"][6]["passed"]
    assert re["trades"]["passed"] is False
    # 两两相关给人看：(名字, |ρ|) 的列表；数字都是当前评估器的，带资金费一栏
    peers = dict(re["vol_ma"]["peers"])
    assert peers["vol_ema"] > 0.9 and peers["trades"] < 0.3
    assert sorted(n for n, _ in re["trades"]["peers"]) == ["vol_ema", "vol_ma"]
    assert re["vol_ma"]["brief"]["horizons"]["12h"]["fundingBps"] == 0 and re["vol_ma"]["brief"]["holdout"]["since"] > 0
    # holdout 从现在的切点起；它没见过的那段从发现它那一轮的切点起，更长
    held, unseen = re["vol_ma"]["brief"]["holdout"], re["vol_ma"]["brief"]["unseen"]
    assert held["since"] == m.app.state.data.cut_ms and unseen["since"] == early
    assert unseen["horizons"]["12h"]["samples"] > held["horizons"]["12h"]["samples"]
    assert await store.trials() == 0 and (await store.candidate("old-1"))["direction"] is None
    # 复评过的入库，库里存的是复评那份
    kept_id = next(c["id"] for c in out["items"] if c["name"] == kept[0])
    assert data(await client.post(f"/api/alpha/candidates/{kept_id}/approve", headers=ADMIN))["name"] == kept[0]
    factor = await store.factor(kept_id)
    assert len(factor["gates"]) == 11 and factor["eval"]["horizons"]["48"]["leg"]["fundingBps"] == 0


async def test_g9_calibration_runs_in_the_service_and_knows_when_it_is_outdated(client):
    """标定 G9：库里每条拿掉再加回去、库外候选、随机因子加进方案后的增量，结果连同口径、切点、在役库存下；三样有一样变了就不作数"""
    store = m.app.state.store
    e = parse("Mean($volume, 4)", ["volume"])
    ev = await evaluated(client, "Mean($volume, 4)")
    await store.add_factor({"id": "f-vol", "name": "vol4", "hypothesis": "h", "expr": "Mean($volume, 4)",
                            "canonical": e.canonical(), "columns": ["volume"], "eval": ev["eval"], "gates": ev["gates"],
                            "approved_at": 1, "run_id": "old"})
    await store.create_run("old", 1, "", "zh", None)
    # 评估过的候选是库外候选，和库里重复的表达式不算
    for cid, name, expr in (("old-1", "trades", "$trades"), ("old-2", "vol_again", "Mean($volume, 4)")):
        await store.upsert_candidate({"id": cid, "run_id": "old", "seq": 1, "name": name, "hypothesis": "h", "expr": expr,
                                      "status": "rejected_gates"})
    assert data(await client.get("/api/alpha/standard", headers=ADMIN))["calibration"] is None
    assert data(await client.post("/api/alpha/standard/calibrate", json={"randoms": 2}, headers=ADMIN)) == {"started": True}
    assert (await client.post("/api/alpha/standard/calibrate", json={"randoms": 2}, headers=ADMIN)).status_code == 409

    async def finished():
        return not data(await client.get("/api/alpha/standard", headers=ADMIN))["job"]["running"]

    await wait_until(finished, 60)
    std = data(await client.get("/api/alpha/standard", headers=ADMIN))
    # 默认不算库外候选：库 1 条 + 随机 2 条
    assert std["job"]["error"] is None and (std["job"]["done"], std["job"]["total"]) == (3, 3)
    assert std["calibration"]["result"]["candidates"] is None
    # 勾上库外候选：库 1 条 + 库外候选 1 条 + 随机 2 条
    assert data(await client.post("/api/alpha/standard/calibrate", json={"randoms": 2, "candidates": True},
                                  headers=ADMIN)) == {"started": True}
    await wait_until(finished, 60)
    std = data(await client.get("/api/alpha/standard", headers=ADMIN))
    cal = std["calibration"]
    assert std["job"]["error"] is None and (std["job"]["done"], std["job"]["total"]) == (4, 4)
    assert (cal["standardId"], cal["cutMs"], cal["randoms"], cal["seed"]) == (1, m.app.state.data.cut_ms, 2, 0)
    assert cal["library"] == [{"name": "vol4", "expr": "Mean($volume, 4)"}]
    assert cal["same"] == {"standard": True, "data": True, "library": True}
    result = cal["result"]
    assert result["base"]["carryShare"] == 0.25 and result["base"]["recentFrom"] == "2022-01-01"
    # 库里那条埋了信号：拿掉再加回去 Sharpe 升；库外候选只有成交笔数那条；随机因子两条，分位数都有
    assert result["library"][0]["name"] == "vol4" and result["library"][0]["full"]["gain"] > 0
    assert [x["name"] for x in result["candidates"]["items"]] == ["trades"]
    assert result["random"]["n"] == 2 and set(result["random"]["full"]) == {"median", "p90", "p95", "p99", "max"}
    # 按标定改 G9 的线：口径换了一版、库里那条成了旧版算的，标定照样作数（增量不看线）；改份额才不作数
    std = data(await client.put("/api/alpha/standard", json={"sharpeGainMin": 0.12}, headers=ADMIN))
    assert (std["id"], std["stale"]) == (2, {"library": 1, "observing": 0})
    assert std["calibration"]["same"] == {"standard": True, "data": True, "library": True}
    await client.put("/api/alpha/trader/carry", json={"share": 0.1}, headers=ADMIN)
    cal = data(await client.get("/api/alpha/standard", headers=ADMIN))["calibration"]
    assert cal["same"] == {"standard": False, "data": True, "library": True}


async def test_g9_line_is_set_on_the_page_and_survives_a_restart(prepared):
    """G9 的线在页面上改：存成库里的覆盖、口径换成新的一版（改回去回到原来那版）、服务里的门槛跟着变；重启后还是改过的值。
    campaign、复评跑着时改不了，标定跑着可以改"""
    async with m.lifespan(prepared):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=prepared), base_url="http://t") as client:
            std = data(await client.get("/api/alpha/standard", headers=ADMIN))
            assert (std["id"], std["content"]["thresholds"]["sharpe_gain_min"]) == (1, 0.1)
            std = data(await client.put("/api/alpha/standard", json={"sharpeGainMin": 0.12}, headers=ADMIN))
            assert (std["id"], std["content"]["thresholds"]["sharpe_gain_min"]) == (2, 0.12)
            assert [h["id"] for h in std["history"]] == [2, 1]
            assert m.app.state.standard.thresholds.sharpe_gain_min == 0.12
            assert data(await client.put("/api/alpha/standard", json={"sharpeGainMin": 0.1}, headers=ADMIN))["id"] == 1
            assert data(await client.put("/api/alpha/standard", json={"sharpeGainMin": 0.12}, headers=ADMIN))["id"] == 2
            jobs = m.app.state.runs.jobs
            jobs["reeval"]["running"] = True
            resp = await client.put("/api/alpha/standard", json={"sharpeGainMin": 0.13}, headers=ADMIN)
            assert resp.status_code == 409 and "复评" in resp.json()["msg"]
            assert data(await client.get("/api/alpha/busy", headers=ADMIN))["standard"] == "观察区还在复评，等它算完"
            jobs["reeval"]["running"] = False
            jobs["calibrate"]["running"] = True
            assert data(await client.get("/api/alpha/busy", headers=ADMIN))["standard"] is None
            jobs["calibrate"]["running"] = False
    # 重启：线是库里改过的那个，口径还是第 2 版
    async with m.lifespan(prepared):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=prepared), base_url="http://t") as client:
            std = data(await client.get("/api/alpha/standard", headers=ADMIN))
            assert (std["id"], std["content"]["thresholds"]["sharpe_gain_min"]) == (2, 0.12)


async def test_library_reeval(client):
    """库里因子复评：G9 的基线是拿掉它的库 + carry，G6 的库对比集是别的因子，G8 的已知结构去掉它自己，
    G10 用它当初声明的方向（没声明过的问模型补）；结果另存在 reeval，入库时那份和状态都不动"""
    store = m.app.state.store
    assert (await client.post("/api/alpha/library/reeval", headers=ADMIN)).status_code == 409
    early = m.app.state.data.cut_ms - 5 * 86_400_000
    await store.create_run("old", 1, "", "zh", None, early)
    for fid, name, expr, direction in (("f-vol", "vol4", "Mean($volume, 4)", 1), ("f-trades", "trades", "$trades", None)):
        e = parse(expr, ["volume", "trades"])
        await store.upsert_candidate({"id": fid, "run_id": "old", "seq": 1, "name": name, "hypothesis": "h", "expr": expr,
                                      "canonical": e.canonical(), "status": "approved", "direction": direction})
        # 入库时那份照 /eval 的样子存一份，复评后要原样还在
        ev = await evaluated(client, expr)
        await store.add_factor({"id": fid, "name": name, "hypothesis": "h", "expr": expr, "canonical": e.canonical(),
                                "columns": sorted(e.columns()), "eval": ev["eval"], "gates": ev["gates"],
                                "approved_at": 1, "run_id": "old"})
    assert data(await client.post("/api/alpha/library/reeval", headers=ADMIN)) == {"started": True, "total": 2}

    async def finished():
        return not data(await client.get("/api/alpha/library", headers=ADMIN))["reeval"]["running"]

    await wait_until(finished, 60)
    out = data(await client.get("/api/alpha/library", headers=ADMIN))
    assert out["reeval"]["done"] == 2 and out["reeval"]["error"] is None
    by = {f["name"]: f for f in out["factors"]}
    # 埋了信号的成交量均线全过：规范形不和已知结构重合（库里的已知去掉了它自己），方向和 IC 对得上；没有同批
    vol = by["vol4"]["reeval"]
    assert vol["passed"] is True and vol["direction"] == 1 and vol["asked"] is False and vol["peers"] == []
    assert [g["id"] for g in vol["gates"]] == [f"G{i}" for i in range(11)]
    # G6 的库对比集是别的因子，不含它自己
    assert "lib:vol4" not in (vol["gates"][6]["note"] or "")
    # 成交笔数是噪音：单条过不了；它当初没声明方向，问了模型补上
    assert by["trades"]["reeval"]["passed"] is False
    assert (by["trades"]["reeval"]["asked"], by["trades"]["reeval"]["direction"]) == (True, 1)
    # holdout 从现在的切点起；它没见过的那段从发现它那一轮的切点起
    assert vol["brief"]["holdout"]["since"] == m.app.state.data.cut_ms and vol["brief"]["unseen"]["since"] == early
    # 入库时那份不动，详情里两份都在
    factor = await store.factor("f-vol")
    assert factor["eval"]["horizons"] and factor["gates"] and factor["reeval"]["passed"] is True
    # 两份都记着是第 1 版口径算的；改 carry 份额就是另一版口径，库里的数字都成了旧版的，改回去回到第 1 版
    assert (by["vol4"]["standard"], vol["standard"], out["standard"]) == (1, 1, 1)
    std = data(await client.get("/api/alpha/standard", headers=ADMIN))
    assert std["id"] == 1 and std["stale"] == {"library": 0, "observing": 0} and std["content"]["scheme"]["carryShare"] == 0.25
    assert std["content"]["reference"]["count"] == 1 and std["content"]["thresholds"]["min_samples"] == 30
    await client.put("/api/alpha/trader/carry", json={"share": 0.1}, headers=ADMIN)
    std = data(await client.get("/api/alpha/standard", headers=ADMIN))
    assert (std["id"], std["stale"]["library"], std["content"]["scheme"]["carryShare"]) == (2, 2, 0.1)
    assert [h["id"] for h in std["history"]] == [2, 1]
    assert data(await client.get("/api/alpha/library", headers=ADMIN))["standard"] == 2
    await client.put("/api/alpha/trader/carry", json={"share": 0.25}, headers=ADMIN)
    assert data(await client.get("/api/alpha/standard", headers=ADMIN))["id"] == 1
    detail = data(await client.get("/api/alpha/library/f-vol", headers=ADMIN))
    assert detail["reeval"]["passed"] is True and detail["gates"] == factor["gates"]
    assert await store.trials() == 0


async def test_stop_after_this_round_and_only_one_campaign(client):
    gate = threading.Event()

    def slow(prompt: str) -> str:
        gate.wait(10)
        return "[]"

    m.app.state.models = (model(slow), model(review_reply))
    started = data(await client.post("/api/alpha/campaigns", json={"rounds": 5}, headers=ADMIN))
    # 同时只有一个 campaign；跑着的时候不刷新数据、不跑参照集
    assert (await client.post("/api/alpha/campaigns", json={}, headers=ADMIN)).status_code == 409
    assert (await client.post("/api/alpha/data/refresh", headers=ADMIN)).status_code == 409
    assert (await client.post("/api/alpha/reference/run", json={}, headers=ADMIN)).status_code == 409
    assert data(await client.post(f"/api/alpha/campaigns/{started['id']}/stop", headers=ADMIN)) == {"stopping": True}
    assert (await campaign_of(client, started["id"]))["campaign"]["status"] == "stopping"
    resp = await client.post(f"/api/alpha/campaigns/{started['id']}/stop", headers=ADMIN)
    assert resp.status_code == 409
    gate.set()
    # 这一轮跑完就停
    detail = await wait_campaign(client, started["id"], "stopped")
    assert detail["campaign"]["done_rounds"] == 1 and detail["campaign"]["stop_reason"] == "手动停止"
    assert len(detail["runs"]) == 1
    assert (await client.post("/api/alpha/campaigns/nope/stop", headers=ADMIN)).status_code == 404
    assert (await client.get("/api/alpha/campaigns/nope", headers=ADMIN)).status_code == 404
    # 停了就能开新的
    assert data(await client.post("/api/alpha/campaigns", json={"rounds": 1}, headers=ADMIN))["id"] != started["id"]


async def test_policy_versions_are_add_only_and_locked_while_a_campaign_runs(client):
    first = data(await client.get("/api/alpha/policy", headers=ADMIN))
    # 启动时代码里的默认方针存成第一版
    assert first["id"] == 1 and first["genRules"] == prompts.DEFAULT_POLICY.gen_rules
    assert [f["id"] for f in first["families"]] == list(prompts.FAMILIES)
    body = {"families": [*first["families"], {"id": "liq_gap", "what": "强平缺口"}], "genRules": "- 新规则",
            "implementChecks": first["implementChecks"], "reviewRules": first["reviewRules"]}
    second = data(await client.put("/api/alpha/policy", json=body, headers=ADMIN))
    assert second["families"][-1] == {"id": "liq_gap", "what": "强平缺口", "cols": "", "hold": ""}
    assert second["genRules"] == "- 新规则" and [h["id"] for h in second["history"]] == [second["id"], first["id"]]
    # 机制 ID 只加不删
    resp = await client.put("/api/alpha/policy", json={**body, "families": body["families"][1:]}, headers=ADMIN)
    assert resp.status_code == 400 and "basis" in resp.json()["msg"]
    gate = threading.Event()

    def slow(prompt: str) -> str:
        gate.wait(10)
        return "[]"

    m.app.state.models = (model(slow), model(review_reply))
    started = data(await client.post("/api/alpha/campaigns", json={"rounds": 1}, headers=ADMIN))
    # campaign 记下建的时候是哪一版；跑着的时候不改
    assert (await m.app.state.store.campaign(started["id"]))["policy_id"] == second["id"]
    assert (await client.put("/api/alpha/policy", json=body, headers=ADMIN)).status_code == 409
    gate.set()
    await wait_campaign(client, started["id"], "done")


async def test_refresh_and_reference_block_campaigns_and_each_other(client):
    jobs = m.app.state.runs.jobs
    # 刷新中开不了 campaign，也不起会存结果的复评、参照集：刷完会换数据和切点
    jobs["refresh"]["running"] = True
    for path in ("/api/alpha/campaigns", "/api/alpha/observation/reeval", "/api/alpha/library/reeval",
                 "/api/alpha/reference/run"):
        resp = await client.post(path, json={"rounds": 1}, headers=ADMIN)
        assert resp.status_code == 409 and "刷新" in resp.json()["msg"], path
    # 页面先拿到同一句原因，按钮直接灰掉；改方针不靠数据，照样能改
    busy = data(await client.get("/api/alpha/busy", headers=ADMIN))
    assert busy["campaign"] == busy["reeval"] == busy["reference"] == busy["calibrate"] == "正在刷新数据，等它刷完"
    assert busy["policy"] is None
    jobs["refresh"]["running"] = False
    # 参照集在跑：campaign 开不了（抢内存），刷新也不开（它可能在读上一版面板）
    jobs["reference"]["running"] = True
    for path in ("/api/alpha/campaigns", "/api/alpha/data/refresh"):
        resp = await client.post(path, json={"rounds": 1}, headers=ADMIN)
        assert resp.status_code == 409 and "参照集" in resp.json()["msg"]
    jobs["reference"]["running"] = False
    # 参照网格没算好：G6 一律不过，campaign 不开
    m.app.state.data.reference_grid = None
    resp = await client.post("/api/alpha/campaigns", json={"rounds": 1}, headers=ADMIN)
    assert resp.status_code == 409 and "参照网格" in resp.json()["msg"]
    assert data(await client.get("/api/alpha/campaigns", headers=ADMIN))["campaigns"] == []


def test_stop_reasons():
    campaign = {"rounds": 10, "max_survivors": 3, "deadline_ms": None, "status": "running"}
    ok, bad = {"status": "done"}, {"status": "failed"}
    assert m._stop_reason(campaign, [ok, bad], 2, 0) is None
    assert m._stop_reason(campaign, [bad, bad], 2, 0) == ("failed", "连续两轮出错")
    assert m._stop_reason(campaign, [bad, ok], 10, 0) == ("done", "跑满 10 轮")
    assert m._stop_reason(campaign, [ok], 1, 3) == ("done", "幸存者到了 3 条")
    assert m._stop_reason({**campaign, "deadline_ms": 1}, [ok], 1, 0) == ("done", "到时间了")
    assert m._stop_reason({**campaign, "status": "stopping"}, [ok], 1, 0) == ("stopped", "手动停止")
    assert m._stop_reason({**campaign, "max_survivors": None, "deadline_ms": 2 ** 50}, [ok], 1, 5) is None


# ========== 其它接口 ==========


async def test_eval_endpoint(client):
    out = await evaluated(client, "$volume")
    assert out["passed"] is True and len(out["gates"]) == 11 and out["canonical"] == "$volume"
    # 测试的参照网格里没有老效应：回归掉 0 条，去老效应后剩的就是它自己的 IC
    assert out["brief"]["neutral"]["against"] == 0 and out["brief"]["neutral"]["retained"] == pytest.approx(1.0)
    # 库是空的：G9 的基线是只有基差 carry 的交易方案，加进埋了信号的因子净 Sharpe 升；主档 24h
    assert out["brief"]["primary"] == "24h" and out["brief"]["gainSharpe"]["gain"] > 0 and "gain" not in out["brief"]
    # 手填的没有假设，方向不判
    assert out["gates"][10]["id"] == "G10" and out["gates"][10]["passed"] and out["gates"][10]["note"] == "没有声明方向，不判"
    assert out["brief"]["redundancy"]["with"] == "noise4" and out["brief"]["redundancy"]["maxAbs"] < 0.2
    assert out["gates"][9]["id"] == "G9" and "加进交易方案后搜索段 2022-01-01 起净 Sharpe" in out["gates"][9]["note"]
    # 全过了才带 holdout：门看搜索段，holdout 另算
    assert out["brief"]["holdout"]["horizons"]["12h"]["meanIc"] > 0 and out["eval"]["holdout"]["since"] == out["brief"]["holdout"]["since"]
    # 没过门的和 campaign 一样碰都不碰 holdout
    noise = await evaluated(client, "$trades")
    assert not noise["passed"] and "holdout" not in noise["eval"] and "holdout" not in noise["brief"]
    # 上一条没跑完再交：409；表达式不合法当场 400，不起任务
    assert data(await client.post("/api/alpha/eval", json={"expr": "$volume"}, headers=ADMIN)) == {"started": True}
    assert (await client.post("/api/alpha/eval", json={"expr": "$trades"}, headers=ADMIN)).status_code == 409
    resp = await client.post("/api/alpha/eval", json={"expr": "Mean(close, 5)"}, headers=ADMIN)
    assert resp.status_code == 400 and "列要写成" in resp.json()["msg"]
    # 页面上的参考：全部列和算子，和提示词里的同一份
    ref = data(await client.get("/api/alpha/dsl", headers=ADMIN))
    assert [c["name"] for c in ref["columns"]] == list(FEATURE_COLUMNS) and ref["columns"][0]["doc"] == "开盘价"
    assert any(o["name"] == "Mean" for o in ref["operators"]) and ref["maxWindow"] == 2016

    async def finished():
        return not data(await client.get("/api/alpha/eval", headers=ADMIN))["running"]

    await wait_until(finished, 60)
    for body in ({"k": 99}, {"rounds": 51}, {"rounds": 0}, {"deadlineMinutes": 0}):
        assert (await client.post("/api/alpha/campaigns", json=body, headers=ADMIN)).status_code == 422


async def test_auth_rules(client):
    # 公开的：数据状态、参照集、概览数字；交易员那几个见 test_trader
    assert data(await client.get("/api/alpha/data/status"))["loaded"]
    assert data(await client.get("/api/alpha/reference", headers={"satoken": "user"}))["items"] == []
    assert data(await client.get("/api/alpha/overview")) == {"runs": [], "trials": 0, "factors": 0, "observing": 0}
    # 成果的内容不公开：没 token 是 401（HTTP 200 + code 401），不是管理员是 403
    resp = await client.get("/api/alpha/library")
    assert resp.status_code == 200 and resp.json()["code"] == 401
    resp = await client.get("/api/alpha/runs", headers={"satoken": "user"})
    assert resp.status_code == 403 and resp.json()["code"] == 403
    # 动的接口同样
    assert (await client.post("/api/alpha/data/refresh")).json()["code"] == 401
    assert (await client.post("/api/alpha/data/refresh", headers={"satoken": "user"})).status_code == 403
    # /me 给页面判断手里的 token 是不是管理员
    me = data(await client.get("/api/alpha/me", headers=ADMIN))
    assert me["admin"] is True and me["lang"] in ("zh", "en")
    assert (await client.get("/api/alpha/me", headers={"satoken": "user"})).status_code == 403
    assert (await client.get("/api/alpha/me")).json()["code"] == 401


async def test_model_config(bare, monkeypatch):
    app = m.app
    assert data(await bare.get("/api/alpha/models", headers=ADMIN)) == {"gen": None, "review": None}
    assert (await bare.get("/api/alpha/models", headers={"satoken": "user"})).status_code == 403
    body = {"protocol": "Anthropic", "baseUrl": "https://up.test/", "model": "claude", "effort": "high", "apiKey": ""}
    # 第一次要带 key；角色、协议都要认得
    assert (await bare.put("/api/alpha/models/gen", json=body, headers=ADMIN)).json()["code"] == 400
    assert (await bare.put("/api/alpha/models/judge", json=body, headers=ADMIN)).status_code == 404
    assert (await bare.put("/api/alpha/models/gen", json={**body, "protocol": "azure", "apiKey": "k"}, headers=ADMIN)).status_code == 400
    app.state.models = ("old", "pair")
    view = data(await bare.put("/api/alpha/models/gen", json={**body, "apiKey": "sk-abcd1234"}, headers=ADMIN))
    # key 只回尾 4 位；协议归小写；存过就把建好的模型丢掉
    assert view["apiKeyTail"] == "1234" and view["protocol"] == "anthropic" and view["model"] == "claude"
    assert "sk-abcd1234" not in json.dumps(view) and app.state.models is None
    # 再存留空 key 就沿用
    view = data(await bare.put("/api/alpha/models/gen", json={**body, "model": "claude-2"}, headers=ADMIN))
    assert view["apiKeyTail"] == "1234" and view["model"] == "claude-2"
    assert (await app.state.store.models())["gen"]["api_key"] == "sk-abcd1234"
    # 只配了一个角色开不了 campaign（fixture 把 build_models 换成了假的，这里换回真的）
    monkeypatch.setattr(m, "build_models", REAL_BUILD_MODELS)
    resp = await bare.post("/api/alpha/campaigns", json={"k": 1}, headers=ADMIN)
    assert resp.status_code == 400 and "两个角色" in resp.json()["msg"]
    assert data(await bare.get("/api/alpha/campaigns", headers=ADMIN))["campaigns"] == []
    # 测试连通：按传来的配置建模型发一句话
    monkeypatch.setattr(m, "build_chat_model", lambda spec, role: model(lambda p: f"{role}:{spec.model}:ok"))
    got = data(await bare.post("/api/alpha/models/gen/test", json={**body, "model": "probe"}, headers=ADMIN))
    assert got["reply"] == "gen:probe:ok" and got["ms"] >= 0


async def test_data_status_and_refresh(client):
    status = data(await client.get("/api/alpha/data/status", headers=ADMIN))
    assert status["loaded"] and set(status["symbols"]) == {"AAA", "BBB", "CCC"} and status["facts"]["rows"] == 6000
    # 6000 根从 2025-01-01 起共 62.5 天，锁最近 10 天
    assert status["facts"]["holdoutFrom"] == "2025-02-22" and status["facts"]["costTiers"] == [5.0, 8.0, 12.0]
    pool = status["pool"]
    assert pool["symbols"] == 24 and pool["months"] == {"2025-01": 24, "2025-02": 24, "2025-03": 24}
    assert set(pool["status"]) == set(POOL_SYMBOLS)
    # 装进评估器的骨架：24 个币 × 250 个 6h 网格点；参照网格是现成的
    assert pool["loaded"] == {"symbols": 24, "months": 3, "gridPoints": 250, "rows": 6000}
    assert pool["grid"]["ready"] is True and pool["grid"]["running"] is False
    before = status["loadedAt"]
    # 观察区有候选：刷新完自动复评一遍
    store = m.app.state.store
    await store.create_run("old", 1, "", "zh", None)
    await store.upsert_candidate({"id": "old-1", "run_id": "old", "seq": 1, "name": "vol", "hypothesis": "h",
                                  "expr": "$volume", "canonical": "$volume", "status": "observing", "direction": 1})
    assert data(await client.post("/api/alpha/data/refresh", headers=ADMIN)) == {"started": True}

    async def finished():
        s = data(await client.get("/api/alpha/data/status", headers=ADMIN))
        return not s["refresh"]["running"] and s["refresh"].get("error") is None

    await wait_until(finished)
    assert data(await client.get("/api/alpha/data/status", headers=ADMIN))["loadedAt"] >= before

    async def reevaluated():
        out = data(await client.get("/api/alpha/observation", headers=ADMIN))
        return out["reeval"].get("finishedAt") is not None and not out["reeval"]["running"]

    await wait_until(reevaluated, 60)
    item = data(await client.get("/api/alpha/observation", headers=ADMIN))["items"][0]
    assert item["reeval"]["brief"]["holdout"]["since"] == m.app.state.data.cut_ms and item["admission"]["ok"] is True


async def test_reference_run(client):
    assert data(await client.post("/api/alpha/reference/run", json={}, headers=ADMIN))["total"] == 1

    async def finished():
        return not data(await client.get("/api/alpha/reference", headers=ADMIN))["job"]["running"]

    await wait_until(finished)
    out = data(await client.get("/api/alpha/reference", headers=ADMIN))
    assert out["job"]["done"] == 1 and out["items"][0]["name"] == "noise4" and isinstance(out["items"][0]["passed"], bool)
    assert out["items"][0]["brief"]["gates"][0]["id"] == "G0"


async def test_missing_reference_grid_is_built_in_the_background(prepared, tmp_path):
    (tmp_path / "panel" / "reference_grid.parquet").unlink()
    async with m.lifespan(prepared):
        assert prepared.state.data.reference_grid is None

        async def ready():
            return prepared.state.data.reference_grid is not None and not prepared.state.runs.jobs["grid"]["running"]

        await wait_until(ready)
        job = prepared.state.runs.jobs["grid"]
        assert job["done"] == job["total"] == 24 and job["error"] is None
        assert prepared.state.data.reference_grid.names == ("noise4",)
    assert (tmp_path / "panel" / "reference_grid.parquet").exists()


async def test_restart_marks_running_runs_failed_and_stops_the_campaign(prepared):
    async with m.lifespan(prepared):
        store = prepared.state.store
        await store.create_campaign("cc", 10, 1, "", "zh", None, None, "fake-gen", "fake-review", 1)
        await store.create_run("zz", 1, "", "zh", "cc")
        await store.upsert_candidate({"id": "zz-1", "run_id": "zz", "seq": 1, "name": "x", "hypothesis": "h",
                                      "status": "rewrite"})
    async with m.lifespan(prepared):
        store = prepared.state.store
        run = await store.run("zz")
        assert run["status"] == "failed" and "重启" in run["error"]
        cand = (await store.candidates("zz"))[0]
        assert cand["status"] == "failed" and "重启" in cand["reason"]
        campaign = await store.campaign("cc")
        assert campaign["status"] == "stopped" and campaign["stop_reason"] == "服务重启"
        # 重启不碰参照网格：还是现成的，不在后台重算
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=prepared), base_url="http://t") as c:
            grid = data(await c.get("/api/alpha/data/status"))["pool"]["grid"]
        assert grid["ready"] is True and grid["running"] is False
