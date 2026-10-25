import math
import sqlite3

import numpy as np
import pytest

from wiib_alpha.config import ModelSpec
from wiib_alpha.store import SCHEMA, Store, clean, dumps


@pytest.fixture
async def store():
    s = await Store.open(":memory:")
    yield s
    await s.close()


def test_clean_turns_nan_and_numpy_into_json():
    obj = {"a": float("nan"), "b": np.float64(1.5), "c": [np.int64(2), float("inf")], "d": {"e": None}}
    assert clean(obj) == {"a": None, "b": 1.5, "c": [2, None], "d": {"e": None}}
    assert dumps({"x": math.nan}) == '{"x": null}'


async def test_run_and_candidate_roundtrip(store):
    await store.create_run("r1", 3, "试试", "zh", None)
    run = await store.run("r1")
    assert run["status"] == "running" and run["k"] == 3 and run["note"] == "试试" and run["campaign_id"] is None
    assert [r["id"] for r in await store.running()] == ["r1"]

    cand = {"id": "r1-1", "run_id": "r1", "seq": 1, "name": "mom", "hypothesis": "动量", "status": "proposed"}
    await store.upsert_candidate(cand)
    cand.update(expr="$ret", canonical="$ret", status="evaluating", attempts=2,
                eval={"ic": float("nan"), "n": np.int64(3)}, gates=[{"id": "G0", "passed": True}])
    await store.upsert_candidate(cand)
    rows = await store.candidates("r1")
    assert len(rows) == 1 and rows[0]["status"] == "evaluating" and rows[0]["attempts"] == 2
    assert rows[0]["eval"] == {"ic": None, "n": 3} and rows[0]["gates"][0]["id"] == "G0"

    await store.set_run_status("r1", "failed", "boom")
    assert (await store.run("r1"))["error"] == "boom"
    assert await store.running() == []
    assert (await store.runs())[0]["id"] == "r1"


async def test_fail_pending_marks_only_unfinished(store):
    await store.create_run("r2", 3, "", "zh", None)
    for i, status in enumerate(("evaluating", "rejected_gates", "passed", "rewrite", "observing"), 1):
        await store.upsert_candidate({"id": f"r2-{i}", "run_id": "r2", "seq": i, "name": f"c{i}", "hypothesis": "h",
                                      "status": status})
    changed = await store.fail_pending("r2", "没跑完")
    assert [c["id"] for c in changed] == ["r2-1", "r2-3", "r2-4"] and changed[0]["status"] == "failed"
    rows = {c["id"]: c for c in await store.candidates("r2")}
    assert rows["r2-1"]["status"] == "failed" and rows["r2-1"]["reason"] == "没跑完"
    assert rows["r2-2"]["status"] == "rejected_gates" and rows["r2-5"]["status"] == "observing"


async def test_observation_and_claim_candidate(store):
    await store.create_run("r1", 2, "", "zh", "c1", 1_000)
    await store.create_run("r2", 2, "", "zh", "c1")
    for run_id, seq, status in (("r1", 1, "observing"), ("r1", 2, "rejected_gates"), ("r2", 1, "observing")):
        await store.upsert_candidate({"id": f"{run_id}-{seq}", "run_id": run_id, "seq": seq, "name": "x",
                                      "hypothesis": "h", "status": status})
    rows = await store.observing()
    # 只要 observing 的，带它那一轮的开始时间和切点；没记切点的老轮是 None
    assert {r["id"] for r in rows} == {"r1-1", "r2-1"} and all(r["run_created_at"] > 0 for r in rows)
    assert {r["id"]: r["run_cut_ms"] for r in rows} == {"r1-1": 1_000, "r2-1": None}
    # 入库、丢弃同时点：只有一个抢得到
    assert await store.claim_candidate("r1-1", "observing", "approved") is True
    assert await store.claim_candidate("r1-1", "observing", "declined") is False
    assert (await store.candidate("r1-1"))["status"] == "approved" and await store.candidate("nope") is None
    assert [r["id"] for r in await store.observing()] == ["r2-1"]


async def test_evaluated_candidates_only_count_the_ones_with_a_primary(store):
    """出题的成败表只统计记着主档的（现在的口径）；老结果没有主档，不算"""
    await store.create_run("r1", 2, "", "zh", None)
    base = {"run_id": "r1", "hypothesis": "h", "status": "rejected_gates", "expr": "$ret",
            "gates": [{"id": "G9", "passed": False}]}
    await store.upsert_candidate({**base, "id": "r1-1", "seq": 1, "name": "new", "family": "basis",
                                  "eval": {"primary": 96, "horizons": {}}})
    await store.upsert_candidate({**base, "id": "r1-2", "seq": 2, "name": "old", "family": "动量",
                                  "eval": {"horizons": {}}})
    assert await store.evaluated_candidates() == [{"expr": "$ret", "family": "basis",
                                                   "eval": {"primary": 96, "horizons": {}},
                                                   "gates": [{"id": "G9", "passed": False}]}]


async def test_campaign_lifecycle_and_lessons(store):
    await store.create_campaign("c1", 10, 5, "费率", "zh", None, 123, "gpt-5", "claude-opus")
    camp = await store.campaign("c1")
    assert camp["status"] == "running" and camp["rounds"] == 10 and camp["k"] == 5 and camp["deadline_ms"] == 123
    assert camp["done_rounds"] == 0 and camp["survivors"] == 0 and camp["max_survivors"] is None
    assert (camp["gen_model"], camp["review_model"]) == ("gpt-5", "claude-opus")
    assert (await store.active_campaign())["id"] == "c1"
    await store.create_run("r1", 5, "费率", "zh", "c1")
    await store.create_run("r2", 5, "费率", "zh", "c1")
    await store.create_run("solo", 1, "", "zh", None)
    assert [r["id"] for r in await store.campaign_runs("c1")] == ["r2", "r1"]
    # 点停：running → stopping 只能抢一次
    assert await store.claim_campaign("c1", "running", "stopping") is True
    assert await store.claim_campaign("c1", "running", "stopping") is False
    assert (await store.active_campaign())["status"] == "stopping"
    await store.update_campaign("c1", done_rounds=2, survivors=1, status="stopped", stop_reason="手动停止")
    camp = await store.campaign("c1")
    assert (camp["done_rounds"], camp["survivors"], camp["status"], camp["stop_reason"]) == (2, 1, "stopped", "手动停止")
    assert await store.active_campaign() is None and [c["id"] for c in await store.campaigns()] == ["c1"]

    await store.add_lessons("r1", "c1", [{"kind": "avoid", "text": "a"}, {"kind": "try", "text": "b"}])
    await store.add_lessons("solo", None, [{"kind": "note", "text": "c"}])
    assert [x["text"] for x in await store.lessons()] == ["c", "b", "a"]
    assert [x["text"] for x in await store.lessons(1)] == ["c"]
    assert [(x["text"], x["run_id"]) for x in await store.lessons(20, "c1")] == [("b", "r1"), ("a", "r1")]


async def test_open_adds_new_columns_to_old_tables(tmp_path):
    """线上库的 run 表是加 campaign 之前建的、campaign 表是记模型名之前建的：open 时补上这些列，旧行是空的"""
    path = tmp_path / "old.sqlite"
    conn = sqlite3.connect(path)
    conn.execute("""CREATE TABLE run(id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, status TEXT NOT NULL,
                    k INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', lang TEXT NOT NULL, error TEXT)""")
    conn.execute("INSERT INTO run VALUES ('old', 1, 'done', 3, '', 'zh', NULL)")
    conn.execute("""CREATE TABLE campaign(
        id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, status TEXT NOT NULL, rounds INTEGER NOT NULL,
        k INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', lang TEXT NOT NULL, max_survivors INTEGER,
        deadline_ms INTEGER, done_rounds INTEGER NOT NULL DEFAULT 0, survivors INTEGER NOT NULL DEFAULT 0,
        stop_reason TEXT, error TEXT)""")
    conn.execute("INSERT INTO campaign(id, created_at, status, rounds, k, lang) VALUES ('oldc', 1, 'done', 3, 1, 'zh')")
    # 份额之前建的 carry 开关表，关着
    conn.execute("CREATE TABLE trade_carry(id INTEGER PRIMARY KEY CHECK (id = 1), enabled INTEGER NOT NULL, "
                 "changed_at INTEGER NOT NULL)")
    conn.execute("INSERT INTO trade_carry VALUES (1, 0, 5)")
    conn.commit()
    conn.close()
    for _ in range(2):  # 第二次 open 不再加
        s = await Store.open(str(path))
        columns = [r["name"] for r in await s.db.execute_fetchall("PRAGMA table_info(run)")]
        assert columns.count("campaign_id") == 1 and columns.count("cut_ms") == 1
        # 补上份额列，老行按默认 1/4，开关照旧
        assert await s.carry() == {"enabled": False, "share": 0.25, "changedAt": 5}
        assert (await s.run("old"))["campaign_id"] is None and (await s.run("old"))["cut_ms"] is None
        columns = [r["name"] for r in await s.db.execute_fetchall("PRAGMA table_info(campaign)")]
        assert columns.count("gen_model") == 1 and columns.count("review_model") == 1
        old = await s.campaign("oldc")
        assert old["gen_model"] is None and old["review_model"] is None
        await s.close()
    s = await Store.open(str(path))
    await s.create_run("new", 1, "", "zh", "c1")
    assert (await s.run("new"))["campaign_id"] == "c1" and [c["id"] for c in await s.campaigns()] == ["oldc"]
    await s.create_campaign("c1", 1, 1, "", "zh", None, None, "gpt-5", "claude-opus")
    assert (await s.campaign("c1"))["gen_model"] == "gpt-5"
    await s.close()


async def test_trader_factor_selection_starts_as_the_whole_library_only_once(tmp_path):
    """交易员的勾选表第一次建出来时默认勾上库里现有的全部；之后取消的不会再被勾回去"""
    path = str(tmp_path / "old.sqlite")
    conn = sqlite3.connect(path)
    for statement in SCHEMA:
        if "trade_factor" not in statement:
            conn.execute(statement)
    for fid in ("f1", "f2"):
        conn.execute("INSERT INTO factor(id, name, hypothesis, expr, canonical, columns_json, eval_json, gates_json, "
                     "approved_at, run_id) VALUES (?, 'x', 'h', '$ret', '$ret', '[]', '{}', '[]', 1, 'r')", (fid,))
    conn.commit()
    conn.close()
    s = await Store.open(path)
    assert await s.trade_factor_ids() == {"f1", "f2"}
    await s.set_trade_factor("f2", False)
    await s.set_trade_factor("f3", True)
    await s.close()
    s = await Store.open(path)
    assert await s.trade_factor_ids() == {"f1", "f3"}
    # 最近一次下过单的调仓才算上一期的多空集合；同一天再写就覆盖
    run = {"signal_ms": 0, "started_at": 1, "finished_at": 2, "status": "done", "user_id": 42}
    await s.add_trade_run({**run, "day": "2026-10-20", "legs": {"combo": {"long": ["A"]}}})
    await s.add_trade_run({**run, "day": "2026-10-21", "status": "failed", "user_id": None, "error": "x"})
    assert await s.last_trade_legs() == {"combo": {"long": ["A"]}} and await s.trade_user() == 42
    await s.add_trade_run({**run, "day": "2026-10-21", "legs": {"combo": {"long": ["B"]}}, "orders": []})
    assert await s.last_trade_legs() == {"combo": {"long": ["B"]}}
    assert [(r["day"], r["status"], r["orders"]) for r in await s.trade_runs()] == [("2026-10-21", "done", []),
                                                                                  ("2026-10-20", "done", None)]
    await s.close()


async def test_events_tried_factors_trials(store):
    await store.create_run("r1", 1, "", "en", None)
    first = await store.add_event("r1", "status", {"stage": "proposing"})
    await store.add_event("r1", "log", {"text": "x"})
    events = await store.events("r1")
    assert [e["event"] for e in events] == ["status", "log"] and events[0]["data"] == {"stage": "proposing"}
    assert [e["event"] for e in await store.events("r1", after=first)] == ["log"]

    await store.add_tried("r1", "假设 A", "$ret", "rejected_gates", "G2 不过")
    await store.add_tried("r1", "假设 B", None, "failed", None)
    await store.add_tried("r1", "假设 A", "$ret", "rejected_gates", "重跑一遍")  # 同轮同假设只留一条
    tried = await store.tried()
    assert [t["hypothesis"] for t in tried] == ["假设 A", "假设 B"] and tried[0]["reason"] == "重跑一遍"

    factor = {"id": "f1", "name": "mom", "hypothesis": "h", "expr": "$ret", "canonical": "$ret",
              "columns": ["ret"], "eval": {"x": 1}, "gates": [], "review": None, "approved_at": 5, "run_id": "r1"}
    await store.add_factor(factor)
    await store.add_factor({**factor, "eval": {"x": 2}})  # commit 重跑：覆盖写，不撞主键
    factor = await store.factor("f1")
    assert factor["columns"] == ["ret"] and factor["eval"] == {"x": 2} and factor["recheck"] is None
    assert len(await store.factors()) == 1
    await store.set_recheck("f1", {"ic": 0.1})
    assert (await store.factors())[0]["recheck"] == {"ic": 0.1}
    assert await store.factor("nope") is None

    assert await store.trials() == 0
    assert await store.bump_trials(3) == 3
    assert await store.bump_trials(2) == 5


async def test_universe_is_replaced_whole(store):
    await store.replace_universe([("2026-01", "BTCUSDT", 1, 5e9), ("2026-01", "AAAUSDT", 2, 2e8)])
    rows = await store.universe()
    assert [(r["month"], r["symbol"], r["rank"]) for r in rows] == [("2026-01", "BTCUSDT", 1), ("2026-01", "AAAUSDT", 2)]
    await store.replace_universe([("2026-02", "BTCUSDT", 1, 5e9)])
    assert [r["month"] for r in await store.universe()] == ["2026-02"]


async def test_models_are_one_row_per_role(store):
    assert await store.models() == {}
    await store.set_model("gen", ModelSpec("openai", "https://a", "m1", "k1", ""))
    await store.set_model("gen", ModelSpec("anthropic", "", "claude", "k2", "high"))
    await store.set_model("review", ModelSpec("gemini", "", "g", "k3", ""))
    rows = await store.models()
    assert set(rows) == {"gen", "review"}
    assert (rows["gen"]["protocol"], rows["gen"]["model"], rows["gen"]["api_key"], rows["gen"]["effort"]) == ("anthropic", "claude", "k2", "high")
