import json

import pytest
from conftest import PRODUCTS
from fakes import LESSONS, gen_reply, model, review_reply

from wiib_alpha import graph as graph_module
from wiib_alpha.data.panel import FEATURE_COLUMNS, truncate
from wiib_alpha.dsl import parse
from wiib_alpha.eval.combo import Combo
from wiib_alpha.eval.params import EvalParams, Thresholds
from wiib_alpha.graph import Context, build_graph
from wiib_alpha.store import Store
from wiib_alpha.trade import scheme

PARAMS = EvalParams(test_size=96, embargo=4, min_train=500, iterations=200, seed=7, holdout_days=10)
GATES = Thresholds(min_samples=30)
FACTS = {"symbols": list(PRODUCTS), "from": "2025-01-01", "to": "2025-03-04", "rows": 6000, "liqFrom": None,
         "holdoutFrom": "2025-02-22", "costTiers": [5.0, 8.0, 12.0], "poolSize": 80, "poolSymbols": 24, "carryShare": 0.25}


@pytest.fixture
async def store():
    s = await Store.open(":memory:")
    yield s
    await s.close()


def review_blind_to_holdout(prompt: str) -> str:
    """评审只能看到搜索段的数字"""
    assert "holdout" not in prompt
    return review_reply(prompt)


@pytest.fixture
def ctx_of(planted_pool, planted_vol, planted_grid, planted_panels):
    pick = planted_pool.in_search
    empty = Combo.empty(planted_pool.search, planted_pool.symbols)
    # 库是空的：G9 的基线是只有基差 carry 的交易方案
    base = scheme.Base.of(empty, planted_pool.values(scheme.CARRY).f[pick], planted_vol[pick], PARAMS, 0.25)
    funding = planted_pool.values(parse("$funding_rate", FEATURE_COLUMNS)).f

    def build(store, gen=gen_reply, review=review_blind_to_holdout) -> Context:
        products = {s: truncate(planted_panels[s], planted_pool.cut_ms) for s in PRODUCTS}
        return Context(gen=model(gen), review=model(review), pool=planted_pool, vol=planted_vol, funding=funding,
                       products=products,
                       reference_grid=planted_grid, base=base, observing=empty, peer_exprs={}, known=set(), library=[],
                       facts=FACTS,
                       store=store, params=PARAMS, thresholds=GATES)
    return build


def recording(reply, seen: list[str]):
    def ask(prompt: str) -> str:
        seen.append(prompt)
        return reply(prompt)
    return ask


async def run_round(ctx: Context, run_id: str, k: int, campaign_id: str | None = None) -> list[dict]:
    """跑一轮到底，收图里推出来的事件"""
    await ctx.store.create_run(run_id, k, "", "zh", campaign_id)
    payload = {"run_id": run_id, "campaign_id": campaign_id, "lang": "zh", "k": k, "note": ""}
    return [chunk async for chunk in build_graph(ctx).astream(payload, stream_mode="custom")]


async def test_full_round_rewrites_reviews_reflects_and_observes(store, ctx_of, planted_pool):
    seen: list[str] = []
    events = await run_round(ctx_of(store, gen=recording(gen_reply, seen)), "r1", 2, "c1")
    # 第一轮还没有评估过的候选：出题提示里没有成败表
    assert "提出 2 条" in seen[0] and "按数据类" not in seen[0]

    rows = {c["name"]: c for c in await store.candidates("r1")}
    # planted：第一次少窗口重写、第二次 $volume 被评审打回、第三次过，进观察区等人入库
    planted = rows["planted"]
    assert planted["status"] == "observing" and planted["attempts"] == 3 and planted["review"]["verdict"] == "pass"
    assert planted["expr"] == "Mean($volume, 4)" and planted["reason"] is None and planted["direction"] == 1
    assert planted["gates"][10]["id"] == "G10" and planted["gates"][10]["passed"]
    # 评估前的预检记在 eval 里
    assert isinstance(planted["eval"]["precheck"]["rankAutocorr"], float)
    # 幸存者带着 holdout 的数字；埋进去的信号在 holdout 上也该是正的
    assert planted["eval"]["holdout"]["since"] == planted_pool.cut_ms
    assert planted["eval"]["holdout"]["horizons"]["48"]["mean"] > 0
    # 库是空的：G9 的基线是只有 carry 的交易方案，加进埋了信号的因子净 Sharpe 升
    assert planted["gates"][9]["id"] == "G9" and planted["gates"][9]["passed"]
    assert planted["eval"]["gain"]["sharpe"]["gain"] > 1
    # noise：门没过，带着数字重写两版还是没过，拒掉；拒掉的不算 holdout
    noise = rows["noise"]
    assert noise["status"] == "rejected_gates" and noise["attempts"] == 3 and "G5" in noise["reason"]
    assert noise["expr"] == "Sum($trades, 16)"
    assert "holdout" not in noise["eval"]
    # family 在机制清单里的原样存，不在的存成 other
    assert planted["family"] == "liquidity" and noise["family"] == "other"
    rewrites = [e["data"] for e in events if e["event"] == "candidate" and e["data"]["status"] == "rewrite"]
    assert [c["name"] for c in rewrites] == ["noise", "noise"] and all("G5" in c["reason"] for c in rewrites)
    # 重写的提示词：同一假设、上一版表达式、没过的门、主档数字，不替模型下结论
    again = [p for p in seen if "[noise]" in p and "上一版表达式" in p]
    assert len(again) == 2 and "上一版表达式：$trades（声明的方向 1，因子值越大越强）\n没过的门：\n- G" in again[0]
    assert "盈亏平衡单边成本" in again[0] and "多空腿每期资金费" in again[0] and "24h 秩自相关" in again[0]
    assert "同一假设，换一种写法；这些数字说明了什么由你判断" in again[0]
    # planted 评估了两版、noise 三版，都计入试验数
    assert any(e["event"] == "log" and "累计试验 5 次" in e["data"]["text"] for e in events)
    assert await store.trials() == 5

    # 复盘：看得到每条的结局和数字，经验存表、推事件
    reflect = next(p for p in seen if "这一轮的候选与结局" in p)
    assert "[planted]" in reflect and "进观察区" in reflect and "硬门拒" in reflect and "评估了 3 版" in reflect
    # 复盘不给表达式，只给没过的门和几个数；模型回的超长、抄表达式的两条丢掉，只存合格的
    assert "Mean($volume, 4)" not in reflect and "没过的门：G2 IC" in reflect and "换手" in reflect
    assert {"event": "lessons", "data": {"items": LESSONS}} in [{k: e[k] for k in ("event", "data")} for e in events]
    assert any(e["event"] == "log" and e["data"]["text"] == "复盘有 2 条超长或带着表达式，丢掉" for e in events)
    stored = await store.lessons()
    assert [(x["kind"], x["text"]) for x in stored] == [(x["kind"], x["text"]) for x in reversed(LESSONS)]
    assert {(x["run_id"], x["campaign_id"]) for x in stored} == {("r1", "c1")}

    stages = [e["data"]["stage"] for e in events if e["event"] == "status"]
    assert stages == ["proposing", "summarizing", "reflecting", "committing"]
    assert events[-1]["event"] == "done" and events[-1]["data"] == {"observing": 1}
    assert (await store.run("r1"))["status"] == "done"
    # 观察区不自动入库
    assert await store.factors() == []
    assert {t["outcome"] for t in await store.tried()} == {"observing", "rejected_gates"}

    # 下一轮出题带着经验，最新在前
    seen.clear()
    await run_round(ctx_of(store, gen=recording(lambda p: "[]", seen)), "r2", 1)
    propose = seen[0]
    assert propose.index("[try] 成交量平滑后更稳") < propose.index("[avoid] 成交笔数没有截面信号")
    # 上一轮的两条都用到成交量，按数据类、按机制 ID 进了成败表
    assert "- 成交量：试过 2、" in propose and "- liquidity：试过 1、" in propose and "- other：试过 1、" in propose


async def test_rewrite_that_clears_the_gates_goes_to_observation(store, ctx_of):
    """第一版 $trades 门没过，看了数字改成成交量均线，过门过评审"""
    def gen(prompt: str) -> str:
        if "这一轮的候选与结局" in prompt:
            return "没什么可说的"
        if "JSON 数组" in prompt:
            return json.dumps([{"name": "late", "hypothesis": "成交活跃的币之后更强"}], ensure_ascii=False)
        if "同一假设，换一种写法" in prompt:
            return '{"expr": "Mean($volume, 8)", "direction": 1, "note": "改用成交量"}'
        return '{"expr": "$trades", "direction": 1, "note": "成交笔数"}'

    events = await run_round(ctx_of(store, gen=gen), "r6", 1)
    c = (await store.candidates("r6"))[0]
    assert c["status"] == "observing" and c["expr"] == "Mean($volume, 8)" and c["attempts"] == 2
    assert [e["data"]["status"] for e in events if e["event"] == "candidate"][:3] == ["proposed", "evaluating", "rewrite"]
    assert await store.trials() == 2
    # 复盘没给出合法 JSON：不存，记一条日志
    assert await store.lessons() == []
    assert any(e["event"] == "log" and "复盘没给出合法的经验" in e["data"]["text"] for e in events)


async def test_batch_keeps_one_of_survivors_that_are_the_same_signal(store, ctx_of):
    """planted 与 clone 规范形相同，twin 换了结构但截面上几乎一样：同批只留增量最大的那条，别的按 G8 或 G6 拒，不算 holdout"""
    await run_round(ctx_of(store), "r5", 4)
    rows = {c["name"]: c for c in await store.candidates("r5")}
    same = [rows[n] for n in ("planted", "twin", "clone")]
    kept = [c for c in same if c["status"] == "observing"]
    dropped = [c for c in same if c["status"] == "rejected_gates"]
    assert len(kept) == 1 and len(dropped) == 2 and "holdout" in kept[0]["eval"]
    assert all(c["reason"].startswith(("G6", "G8")) and "holdout" not in c["eval"] for c in dropped)
    by_g6 = [c for c in dropped if c["reason"].startswith("G6")]
    assert by_g6 and all(not c["gates"][6]["passed"] and f"与同批 {kept[0]['name']} 的截面" in c["gates"][6]["note"]
                         for c in by_g6)
    assert rows["noise"]["status"] == "rejected_gates"


async def test_batch_notes_later_survivors_gain_after_earlier_ones(store, ctx_of, monkeypatch):
    """截面相关没到阈值的照样留（这里把相关压成 0.1）：同批规范形相同的只留一条（G8 拒），
    排在后面的 G9 备注里写明排在谁后面增量多少，留给人判"""
    def no_gain(base, name, f, params):
        return {"sharpe": {"base": 2.0, "with": 1.9, "gain": -0.1}, "netReturn": {"base": 0.5, "with": 0.49}}

    monkeypatch.setattr(graph_module, "gain", no_gain)
    monkeypatch.setattr(graph_module, "cross_corr", lambda rows, f, others, params: [(n, 0.1) for n in others.names])
    await run_round(ctx_of(store), "r5", 4)
    rows = {c["name"]: c for c in await store.candidates("r5")}
    # planted 与 clone 都是 Mean($volume, w)：排后面的那条按 G8 拒，不算 holdout
    dropped = [c for c in (rows["planted"], rows["clone"]) if c["status"] == "rejected_gates"]
    assert len(dropped) == 1 and dropped[0]["reason"].startswith("G8") and "holdout" not in dropped[0]["eval"]
    assert not dropped[0]["gates"][8]["passed"]
    survivors = [c for c in rows.values() if c["status"] == "observing"]
    assert {c["name"] for c in survivors} == {"twin", ("planted", "clone")[dropped[0]["name"] == "planted"]}
    # 排在后面的那条 G9 没被翻成不过，备注里带着相对前一条的增量
    noted = [c for c in survivors if "排在同批" in c["gates"][9]["note"]]
    assert len(noted) == 1 and all(g["passed"] for g in noted[0]["gates"])
    assert noted[0]["gates"][9]["note"].endswith("之后净 Sharpe 增量 -0.10")
    assert all("holdout" in c["eval"] for c in survivors)
    assert rows["noise"]["status"] == "rejected_gates"


async def test_no_hypotheses_goes_straight_to_done(store, ctx_of):
    events = await run_round(ctx_of(store, gen=lambda p: "[]"), "r3", 1)
    # 没有候选就不复盘
    assert [e["data"]["stage"] for e in events if e["event"] == "status"] == ["proposing", "summarizing", "committing"]
    assert (await store.run("r3"))["status"] == "done" and await store.trials() == 0


async def test_direction_is_required_and_checked_by_g10(store, ctx_of):
    """没给方向带着毛病再要；声明越大越弱、植入信号却是越大越强，G10 拒；
    重写时看了 IC 把方向翻成 1 也不算，沿用第一版的 -1，换窗口重写两版还是拒"""
    def gen(prompt: str) -> str:
        if "这一轮的候选与结局" in prompt:
            return "[]"
        if "JSON 数组" in prompt:
            return '[{"name": "nodir", "hypothesis": "成交量高的币之后更弱"}]'
        if "上一版表达式：Mean($volume, 4)（" in prompt:
            assert "沿用上一版声明的 -1" in prompt
            return '{"expr": "Mean($volume, 8)", "direction": 1}'
        if "上一版表达式：Mean($volume, 8)（" in prompt:
            return '{"expr": "Mean($volume, 16)", "direction": 1}'
        if "direction 只能是 1 或 -1" in prompt:
            return '{"expr": "Mean($volume, 4)", "direction": -1}'
        return '{"expr": "Mean($volume, 4)"}'

    await run_round(ctx_of(store, gen=gen), "r7", 1)
    c = (await store.candidates("r7"))[0]
    assert c["status"] == "rejected_gates" and c["direction"] == -1 and c["attempts"] == 4
    assert c["expr"] == "Mean($volume, 16)" and c["reason"].startswith("G10") and not c["gates"][10]["passed"]
    assert await store.trials() == 3


async def test_rewrite_that_repeats_an_earlier_version_is_not_evaluated_again(store, ctx_of):
    """门没过让它重写，它又交回同一条：不再评估、不计试验，带着"和第 1 版一模一样"再要；三次都一样就停在第 1 版的结局上"""
    seen: list[str] = []

    def gen(prompt: str) -> str:
        if "这一轮的候选与结局" in prompt:
            return "[]"
        if "JSON 数组" in prompt:
            return '[{"name": "stuck", "hypothesis": "成交笔数多的币之后更强"}]'
        return '{"expr": "$trades", "direction": 1}'

    await run_round(ctx_of(store, gen=recording(gen, seen)), "r8", 1)
    c = (await store.candidates("r8"))[0]
    assert c["status"] == "rejected_gates" and c["expr"] == "$trades" and c["attempts"] == 4
    assert "；重写连试三次都没写出新的表达式：$trades 和这条假设的第 1 版一模一样" in c["reason"]
    assert c["reason"].startswith("G") and await store.trials() == 1
    assert sum("和这条假设的第 1 版一模一样" in p for p in seen if "写成一条表达式" in p) == 2


async def test_unparseable_expression_three_times_is_failed(store, ctx_of):
    def stubborn(prompt: str) -> str:
        if "JSON 数组" in prompt:
            return '[{"name": "bad", "hypothesis": "写不出来"}]'
        return '{"expr": "Mean(close, 5)"}'

    await run_round(ctx_of(store, gen=stubborn), "r4", 1)
    rows = await store.candidates("r4")
    assert rows[0]["status"] == "failed" and rows[0]["attempts"] == 3
    assert (await store.run("r4"))["status"] == "done"
