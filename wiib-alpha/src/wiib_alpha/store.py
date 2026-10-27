"""SQLite：campaign、运行、候选、因子库、已试假设、轮后经验、事件回放、全局试验计数、两个角色的模型端点，
交易员的调仓记录、净值快照与因子勾选。

评估结果里有 NaN 和 numpy 标量，落库和下发前一律 clean 成纯 JSON。
线上库只加表加列：老库缺的列在 open 时补上。
"""

from __future__ import annotations

import json
import math
import os
import shutil
import sqlite3
import tempfile
import time
from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from typing import Any

import aiosqlite
import numpy as np

from .config import ModelSpec

SCHEMA = [
    # status：running / stopping（这轮跑完就停）/ stopped / done / failed；gen_model、review_model：建的时候两个角色用的模型名；
    # policy_id：建的时候研究方针是哪一版，加这列之前建的是 NULL
    """CREATE TABLE IF NOT EXISTS campaign(
        id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, status TEXT NOT NULL, rounds INTEGER NOT NULL,
        k INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', lang TEXT NOT NULL, max_survivors INTEGER,
        deadline_ms INTEGER, done_rounds INTEGER NOT NULL DEFAULT 0, survivors INTEGER NOT NULL DEFAULT 0,
        stop_reason TEXT, error TEXT, gen_model TEXT, review_model TEXT, policy_id INTEGER)""",
    # cut_ms：这一轮用的数据的 holdout 切点，之后的数据这一轮的候选都没见过；加这列之前跑的轮是 NULL
    """CREATE TABLE IF NOT EXISTS run(
        id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, status TEXT NOT NULL, k INTEGER NOT NULL,
        note TEXT NOT NULL DEFAULT '', lang TEXT NOT NULL, error TEXT, campaign_id TEXT, cut_ms INTEGER)""",
    # direction：实现时按假设声明的方向，1 越大越强、-1 越弱；reeval_json：观察区用当前评估器复评的结果，原来的 eval 不动
    """CREATE TABLE IF NOT EXISTS candidate(
        id TEXT PRIMARY KEY, run_id TEXT NOT NULL, seq INTEGER NOT NULL, name TEXT NOT NULL,
        hypothesis TEXT NOT NULL, family TEXT, expr TEXT, canonical TEXT, note TEXT, status TEXT NOT NULL,
        attempts INTEGER NOT NULL DEFAULT 0, eval_json TEXT, gates_json TEXT, review_json TEXT, reason TEXT,
        updated_at INTEGER NOT NULL, direction INTEGER, reeval_json TEXT)""",
    "CREATE INDEX IF NOT EXISTS idx_candidate_run ON candidate(run_id, seq)",
    """CREATE TABLE IF NOT EXISTS run_event(
        id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, event TEXT NOT NULL, data_json TEXT NOT NULL,
        created_at INTEGER NOT NULL)""",
    "CREATE INDEX IF NOT EXISTS idx_event_run ON run_event(run_id, id)",
    """CREATE TABLE IF NOT EXISTS tried(
        id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, hypothesis TEXT NOT NULL, expr TEXT,
        outcome TEXT NOT NULL, reason TEXT, created_at INTEGER NOT NULL, UNIQUE(run_id, hypothesis))""",
    # 轮后复盘写的经验；kind：avoid / try / note
    """CREATE TABLE IF NOT EXISTS lesson(
        id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, campaign_id TEXT, kind TEXT NOT NULL,
        text TEXT NOT NULL, created_at INTEGER NOT NULL)""",
    # recheck_json：入库后只用新数据算的 IC；reeval_json：用当前评估器复评的结果，入库时那份 eval、gates 不动；
    # retired_at / retire_reason：退役的时刻和原因，NULL 是在役
    """CREATE TABLE IF NOT EXISTS factor(
        id TEXT PRIMARY KEY, name TEXT NOT NULL, hypothesis TEXT NOT NULL, expr TEXT NOT NULL, canonical TEXT NOT NULL,
        columns_json TEXT NOT NULL, eval_json TEXT NOT NULL, gates_json TEXT NOT NULL, review_json TEXT,
        approved_at INTEGER NOT NULL, run_id TEXT NOT NULL, recheck_json TEXT, reeval_json TEXT,
        retired_at INTEGER, retire_reason TEXT)""",
    "CREATE TABLE IF NOT EXISTS trials(id INTEGER PRIMARY KEY CHECK (id = 1), count INTEGER NOT NULL)",
    "INSERT OR IGNORE INTO trials(id, count) VALUES (1, 0)",
    """CREATE TABLE IF NOT EXISTS reference_eval(
        name TEXT PRIMARY KEY, expr TEXT NOT NULL, passed INTEGER NOT NULL, eval_json TEXT NOT NULL,
        gates_json TEXT NOT NULL, updated_at INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS universe(
        month TEXT NOT NULL, symbol TEXT NOT NULL, rank INTEGER NOT NULL, avg_quote_volume REAL NOT NULL,
        PRIMARY KEY(month, symbol))""",
    """CREATE TABLE IF NOT EXISTS model(
        role TEXT PRIMARY KEY, protocol TEXT NOT NULL, base_url TEXT NOT NULL, model TEXT NOT NULL,
        api_key TEXT NOT NULL, effort TEXT NOT NULL, updated_at INTEGER NOT NULL)""",
    # 交易员每天一次调仓，day 是 UTC 日期，signal_ms 是出信号那根的收盘时刻；status：done / partial / failed。
    # factors_json 是这次用的因子与权重，legs_json 是每条腿这一期的多空集合（下一期缓冲用），
    # targets_json 是每币目标与各腿信号，orders_json 是下单与结果，board_json 是这一刻库里在役因子对产品币的看法（因子看板）
    """CREATE TABLE IF NOT EXISTS trade_run(
        day TEXT PRIMARY KEY, signal_ms INTEGER NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER NOT NULL,
        status TEXT NOT NULL, user_id INTEGER, factors_json TEXT, legs_json TEXT, targets_json TEXT, orders_json TEXT,
        equity_before REAL, equity_after REAL, error TEXT, board_json TEXT)""",
    # 交易员账户每小时一次的净值快照；多空名义是持仓的数量 × 标记价
    """CREATE TABLE IF NOT EXISTS trade_equity(
        ts INTEGER PRIMARY KEY, equity REAL NOT NULL, balance REAL NOT NULL, long_notional REAL NOT NULL,
        short_notional REAL NOT NULL)""",
    # 交易员勾选的因子
    "CREATE TABLE IF NOT EXISTS trade_factor(factor_id TEXT PRIMARY KEY, selected_at INTEGER NOT NULL)",
    # carry 腿的开关和占资金的比例，一行；没有这行是开着、占 1/4
    """CREATE TABLE IF NOT EXISTS trade_carry(
        id INTEGER PRIMARY KEY CHECK (id = 1), enabled INTEGER NOT NULL, share REAL NOT NULL DEFAULT 0.25,
        changed_at INTEGER NOT NULL)""",
    # 交易员的杠杆（所有币统一）和保证金比例，一行；没有这行是杠杆 4、保证金一半，名义和回测一样
    """CREATE TABLE IF NOT EXISTS trade_sizing(
        id INTEGER PRIMARY KEY CHECK (id = 1), leverage INTEGER NOT NULL, margin REAL NOT NULL,
        changed_at INTEGER NOT NULL)""",
    # 研究方针，改一次存一版、不覆盖，最新一版是当前的；families_json 是机制清单的有序列表
    """CREATE TABLE IF NOT EXISTS policy(
        id INTEGER PRIMARY KEY AUTOINCREMENT, families_json TEXT NOT NULL, gen_rules TEXT NOT NULL,
        implement_checks TEXT NOT NULL, review_rules TEXT NOT NULL, created_at INTEGER NOT NULL)""",
]

_JSON_COLUMNS = {"eval_json": "eval", "gates_json": "gates", "review_json": "review", "data_json": "data",
                 "columns_json": "columns", "recheck_json": "recheck", "reeval_json": "reeval",
                 "factors_json": "factors", "legs_json": "legs", "targets_json": "targets", "orders_json": "orders",
                 "board_json": "board", "attrib_json": "attrib", "families_json": "families"}
# 建表之后才加的列：老库启动时补上
_ADDED_COLUMNS = {"campaign": {"gen_model": "TEXT", "review_model": "TEXT", "policy_id": "INTEGER"},
                  "run": {"campaign_id": "TEXT", "cut_ms": "INTEGER"},
                  "candidate": {"direction": "INTEGER", "reeval_json": "TEXT"},
                  "factor": {"reeval_json": "TEXT", "retired_at": "INTEGER", "retire_reason": "TEXT"},
                  "trade_run": {"board_json": "TEXT", "attrib_json": "TEXT"},
                  "trade_carry": {"share": "REAL NOT NULL DEFAULT 0.25"}}


def clean(obj: Any) -> Any:
    """NaN/inf → None，numpy 标量 → Python，递归"""
    if isinstance(obj, dict):
        return {str(k): clean(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [clean(v) for v in obj]
    if isinstance(obj, np.generic):
        obj = obj.item()
    if isinstance(obj, float) and (math.isnan(obj) or math.isinf(obj)):
        return None
    return obj


def dumps(obj: Any) -> str:
    return json.dumps(clean(obj), ensure_ascii=False, allow_nan=False)


def now_ms() -> int:
    return int(time.time() * 1000)


@contextmanager
def snapshot(path: str) -> Iterator[sqlite3.Connection]:
    """库拷到临时目录再开，命令行脚本读库用：WAL 模式的库只读挂载打不开，也不和服务抢锁"""
    with tempfile.TemporaryDirectory() as tmp:
        copy = os.path.join(tmp, "alpha.sqlite")
        for ext in ("", "-wal", "-shm"):
            if os.path.exists(path + ext):
                shutil.copy(path + ext, copy + ext)
        db = sqlite3.connect(copy)
        try:
            yield db
        finally:
            db.close()


class Store:
    def __init__(self, db: aiosqlite.Connection):
        self.db = db

    @classmethod
    async def open(cls, path: str) -> "Store":
        db = await aiosqlite.connect(path)
        db.row_factory = aiosqlite.Row
        await db.execute("PRAGMA journal_mode=WAL")
        fresh = not await db.execute_fetchall("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'trade_factor'")
        for statement in SCHEMA:
            await db.execute(statement)
        # 交易员的因子勾选第一次建表时默认勾上库里现有的全部
        if fresh:
            await db.execute("INSERT INTO trade_factor(factor_id, selected_at) SELECT id, ? FROM factor", (now_ms(),))
        for table, columns in _ADDED_COLUMNS.items():
            have = {r["name"] for r in await db.execute_fetchall(f"PRAGMA table_info({table})")}
            for name, kind in columns.items():
                if name not in have:
                    await db.execute(f"ALTER TABLE {table} ADD COLUMN {name} {kind}")
        await db.commit()
        return cls(db)

    async def close(self) -> None:
        await self.db.close()

    # ========== campaign ==========

    async def create_campaign(self, campaign_id: str, rounds: int, k: int, note: str, lang: str,
                              max_survivors: int | None, deadline_ms: int | None, gen_model: str,
                              review_model: str, policy_id: int) -> None:
        await self.db.execute(
            """INSERT INTO campaign(id, created_at, status, rounds, k, note, lang, max_survivors, deadline_ms,
                                    gen_model, review_model, policy_id)
               VALUES (?, ?, 'running', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (campaign_id, now_ms(), rounds, k, note, lang, max_survivors, deadline_ms, gen_model, review_model,
             policy_id))
        await self.db.commit()

    async def update_campaign(self, campaign_id: str, **fields: Any) -> None:
        """fields 的键就是列名，只从代码里传"""
        sets = ", ".join(f"{k} = ?" for k in fields)
        await self.db.execute(f"UPDATE campaign SET {sets} WHERE id = ?", (*fields.values(), campaign_id))
        await self.db.commit()

    async def claim_campaign(self, campaign_id: str, expect: str, new: str) -> bool:
        """状态从 expect 改成 new，一条 UPDATE 完成检查与置位"""
        cur = await self.db.execute("UPDATE campaign SET status = ? WHERE id = ? AND status = ?",
                                    (new, campaign_id, expect))
        await self.db.commit()
        return cur.rowcount == 1

    async def campaign(self, campaign_id: str) -> dict[str, Any] | None:
        return await self._one("SELECT * FROM campaign WHERE id = ?", (campaign_id,))

    async def campaigns(self, limit: int = 50) -> list[dict[str, Any]]:
        return await self._all("SELECT * FROM campaign ORDER BY created_at DESC LIMIT ?", (limit,))

    async def active_campaign(self) -> dict[str, Any] | None:
        """在跑的那个（同时只有一个）：running 或 stopping"""
        return await self._one("SELECT * FROM campaign WHERE status IN ('running', 'stopping')", ())

    # ========== 运行 ==========

    async def create_run(self, run_id: str, k: int, note: str, lang: str, campaign_id: str | None,
                         cut_ms: int | None = None) -> None:
        await self.db.execute(
            "INSERT INTO run(id, created_at, status, k, note, lang, campaign_id, cut_ms) "
            "VALUES (?, ?, 'running', ?, ?, ?, ?, ?)",
            (run_id, now_ms(), k, note, lang, campaign_id, cut_ms))
        await self.db.commit()

    async def set_run_status(self, run_id: str, status: str, error: str | None = None) -> None:
        await self.db.execute("UPDATE run SET status = ?, error = ? WHERE id = ?", (status, error, run_id))
        await self.db.commit()

    async def run(self, run_id: str) -> dict[str, Any] | None:
        return await self._one("SELECT * FROM run WHERE id = ?", (run_id,))

    async def runs(self, limit: int = 50) -> list[dict[str, Any]]:
        return await self._all("SELECT * FROM run ORDER BY created_at DESC, rowid DESC LIMIT ?", (limit,))

    async def campaign_runs(self, campaign_id: str) -> list[dict[str, Any]]:
        """一个 campaign 的各轮，最新在前"""
        return await self._all("SELECT * FROM run WHERE campaign_id = ? ORDER BY created_at DESC, rowid DESC",
                               (campaign_id,))

    async def running(self) -> list[dict[str, Any]]:
        return await self._all("SELECT * FROM run WHERE status = 'running'", ())

    # ========== 候选 ==========

    async def upsert_candidate(self, c: dict[str, Any]) -> None:
        await self.db.execute(
            """INSERT INTO candidate(id, run_id, seq, name, hypothesis, family, expr, canonical, note, status, attempts,
                                     eval_json, gates_json, review_json, reason, updated_at, direction)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET expr = excluded.expr, canonical = excluded.canonical, note = excluded.note,
                   status = excluded.status, attempts = excluded.attempts, eval_json = excluded.eval_json,
                   gates_json = excluded.gates_json, review_json = excluded.review_json, reason = excluded.reason,
                   updated_at = excluded.updated_at, direction = excluded.direction""",
            (c["id"], c["run_id"], c["seq"], c["name"], c["hypothesis"], c.get("family"), c.get("expr"),
             c.get("canonical"), c.get("note"), c["status"], c.get("attempts", 0),
             dumps(c["eval"]) if c.get("eval") else None, dumps(c["gates"]) if c.get("gates") else None,
             dumps(c["review"]) if c.get("review") else None, c.get("reason"), now_ms(), c.get("direction")))
        await self.db.commit()

    async def set_reeval(self, candidate_id: str, reeval: Any) -> None:
        """只写复评结果，状态、原来的 eval 与 updated_at 都不动"""
        await self.db.execute("UPDATE candidate SET reeval_json = ? WHERE id = ?", (dumps(reeval), candidate_id))
        await self.db.commit()

    async def candidates(self, run_id: str) -> list[dict[str, Any]]:
        return await self._all("SELECT * FROM candidate WHERE run_id = ? ORDER BY seq", (run_id,))

    async def candidate(self, candidate_id: str) -> dict[str, Any] | None:
        return await self._one("SELECT * FROM candidate WHERE id = ?", (candidate_id,))

    async def observing(self) -> list[dict[str, Any]]:
        """观察区：全部 observing 的候选，带它那一轮的开始时间、切点和语言，新的在前"""
        return await self._all(
            """SELECT c.*, r.created_at AS run_created_at, r.cut_ms AS run_cut_ms, r.lang AS run_lang
               FROM candidate c JOIN run r ON r.id = c.run_id
               WHERE c.status = 'observing' ORDER BY r.created_at DESC, c.seq""", ())

    async def claim_candidate(self, candidate_id: str, expect: str, new: str) -> bool:
        """状态从 expect 改成 new，一条 UPDATE 完成检查与置位：入库和丢弃同时点只有一个能抢到"""
        cur = await self.db.execute("UPDATE candidate SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                                    (new, now_ms(), candidate_id, expect))
        await self.db.commit()
        return cur.rowcount == 1

    async def evaluated_candidates(self) -> list[dict[str, Any]]:
        """评估过、记着主档的候选（现在的口径），出题时统计成败用"""
        return await self._all("SELECT expr, family, eval_json, gates_json FROM candidate "
                               "WHERE json_extract(eval_json, '$.primary') IS NOT NULL", ())

    async def candidate_counts(self) -> dict[str, dict[str, int]]:
        """每轮各状态的候选数：run_id → {status: 条数}；公开概览只给数字"""
        rows = await self._all("SELECT run_id, status, COUNT(*) AS n FROM candidate GROUP BY run_id, status", ())
        out: dict[str, dict[str, int]] = {}
        for r in rows:
            out.setdefault(r["run_id"], {})[r["status"]] = int(r["n"])
        return out

    async def fail_pending(self, run_id: str, reason: str) -> list[dict[str, Any]]:
        """这一轮没跑完：还没到终态的候选标成 failed，返回改了的那些"""
        pending = "('proposed', 'evaluating', 'rewrite', 'reviewing', 'revise', 'passed')"
        rows = await self._all(f"SELECT * FROM candidate WHERE run_id = ? AND status IN {pending} ORDER BY seq", (run_id,))
        await self.db.execute(
            f"UPDATE candidate SET status = 'failed', reason = ?, updated_at = ? WHERE run_id = ? AND status IN {pending}",
            (reason, now_ms(), run_id))
        await self.db.commit()
        for r in rows:
            r["status"], r["reason"] = "failed", reason
        return rows

    # ========== 事件 ==========

    async def add_event(self, run_id: str, event: str, data: Any) -> int:
        cur = await self.db.execute("INSERT INTO run_event(run_id, event, data_json, created_at) VALUES (?, ?, ?, ?)",
                                    (run_id, event, dumps(data), now_ms()))
        await self.db.commit()
        return int(cur.lastrowid or 0)

    async def events(self, run_id: str, after: int = 0) -> list[dict[str, Any]]:
        return await self._all("SELECT * FROM run_event WHERE run_id = ? AND id > ? ORDER BY id", (run_id, after))

    # ========== 已试假设 ==========

    async def add_tried(self, run_id: str, hypothesis: str, expr: str | None, outcome: str, reason: str | None) -> None:
        """同一轮同一条假设只记一次，commit 重跑不留重复"""
        await self.db.execute(
            "INSERT OR REPLACE INTO tried(run_id, hypothesis, expr, outcome, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            (run_id, hypothesis, expr, outcome, reason, now_ms()))
        await self.db.commit()

    async def tried(self, limit: int = 200) -> list[dict[str, Any]]:
        return await self._all("SELECT * FROM tried ORDER BY id DESC LIMIT ?", (limit,))

    # ========== 轮后经验 ==========

    async def add_lessons(self, run_id: str, campaign_id: str | None, items: list[dict[str, str]]) -> None:
        now = now_ms()
        await self.db.executemany(
            "INSERT INTO lesson(run_id, campaign_id, kind, text, created_at) VALUES (?, ?, ?, ?, ?)",
            [(run_id, campaign_id, x["kind"], x["text"], now) for x in items])
        await self.db.commit()

    async def lessons(self, limit: int = 20, campaign_id: str | None = None) -> list[dict[str, Any]]:
        """最新在前；给了 campaign_id 只要这个 campaign 的"""
        if campaign_id is None:
            return await self._all("SELECT * FROM lesson ORDER BY id DESC LIMIT ?", (limit,))
        return await self._all("SELECT * FROM lesson WHERE campaign_id = ? ORDER BY id DESC LIMIT ?",
                               (campaign_id, limit))

    # ========== 因子库 ==========

    async def add_factor(self, f: dict[str, Any]) -> None:
        """按 id 覆盖写：commit 节点重跑不会撞主键"""
        await self.db.execute(
            """INSERT INTO factor(id, name, hypothesis, expr, canonical, columns_json, eval_json, gates_json, review_json,
                                  approved_at, run_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET name = excluded.name, hypothesis = excluded.hypothesis, expr = excluded.expr,
                   canonical = excluded.canonical, columns_json = excluded.columns_json, eval_json = excluded.eval_json,
                   gates_json = excluded.gates_json, review_json = excluded.review_json, run_id = excluded.run_id""",
            (f["id"], f["name"], f["hypothesis"], f["expr"], f["canonical"], dumps(f["columns"]), dumps(f["eval"]),
             dumps(f["gates"]), dumps(f.get("review")), f["approved_at"], f["run_id"]))
        await self.db.commit()

    async def factors(self) -> list[dict[str, Any]]:
        """在役的"""
        return await self._all("SELECT * FROM factor WHERE retired_at IS NULL ORDER BY approved_at DESC", ())

    async def retired_factors(self) -> list[dict[str, Any]]:
        """退役的，最近退役的在前"""
        return await self._all("SELECT * FROM factor WHERE retired_at IS NOT NULL ORDER BY retired_at DESC", ())

    async def factor(self, factor_id: str) -> dict[str, Any] | None:
        """在役、退役都查得到"""
        return await self._one("SELECT * FROM factor WHERE id = ?", (factor_id,))

    async def retire_factor(self, factor_id: str, reason: str) -> bool:
        """在役的才退得了，同时取消交易员的勾选；已经退役的返回 False"""
        cur = await self.db.execute("UPDATE factor SET retired_at = ?, retire_reason = ? WHERE id = ? AND retired_at IS NULL",
                                    (now_ms(), reason, factor_id))
        done = cur.rowcount == 1
        if done:
            await self.db.execute("DELETE FROM trade_factor WHERE factor_id = ?", (factor_id,))
        await self.db.commit()
        return done

    async def restore_factor(self, factor_id: str) -> bool:
        """退役的才恢复得了；交易员的勾选不跟着恢复"""
        cur = await self.db.execute(
            "UPDATE factor SET retired_at = NULL, retire_reason = NULL WHERE id = ? AND retired_at IS NOT NULL", (factor_id,))
        await self.db.commit()
        return cur.rowcount == 1

    async def set_recheck(self, factor_id: str, recheck: Any) -> None:
        await self.db.execute("UPDATE factor SET recheck_json = ? WHERE id = ?", (dumps(recheck), factor_id))
        await self.db.commit()

    async def set_factor_reeval(self, factor_id: str, reeval: Any) -> None:
        """只写复评结果，入库时那份 eval、gates 不动"""
        await self.db.execute("UPDATE factor SET reeval_json = ? WHERE id = ?", (dumps(reeval), factor_id))
        await self.db.commit()

    # ========== 参照集评估 ==========

    async def set_reference_eval(self, name: str, expr: str, ev: Any, gates: Any, passed: bool) -> None:
        await self.db.execute(
            """INSERT INTO reference_eval(name, expr, passed, eval_json, gates_json, updated_at) VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT(name) DO UPDATE SET expr = excluded.expr, passed = excluded.passed,
                   eval_json = excluded.eval_json, gates_json = excluded.gates_json, updated_at = excluded.updated_at""",
            (name, expr, int(passed), dumps(ev), dumps(gates), now_ms()))
        await self.db.commit()

    async def reference_evals(self) -> list[dict[str, Any]]:
        return await self._all("SELECT * FROM reference_eval ORDER BY name", ())

    # ========== 币池 ==========

    async def replace_universe(self, rows: list[tuple[str, str, int, float]]) -> None:
        """每次刷新整张重算，整表换掉；行是 (month, symbol, rank, avg_quote_volume)"""
        await self.db.execute("DELETE FROM universe")
        await self.db.executemany("INSERT INTO universe(month, symbol, rank, avg_quote_volume) VALUES (?, ?, ?, ?)", rows)
        await self.db.commit()

    async def universe(self) -> list[dict[str, Any]]:
        return await self._all("SELECT * FROM universe ORDER BY month, rank", ())

    async def has_universe(self, month: str) -> bool:
        """这个月（YYYY-MM）的榜单排出来没有"""
        return await self._one("SELECT 1 FROM universe WHERE month = ? LIMIT 1", (month,)) is not None

    # ========== 模型端点 ==========

    async def set_model(self, role: str, spec: ModelSpec) -> None:
        await self.db.execute(
            """INSERT INTO model(role, protocol, base_url, model, api_key, effort, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(role) DO UPDATE SET protocol = excluded.protocol, base_url = excluded.base_url,
                   model = excluded.model, api_key = excluded.api_key, effort = excluded.effort, updated_at = excluded.updated_at""",
            (role, spec.protocol, spec.base_url, spec.model, spec.api_key, spec.effort, now_ms()))
        await self.db.commit()

    async def models(self) -> dict[str, dict[str, Any]]:
        """按角色；api_key 是明文，只在服务端用，下发前截尾"""
        return {r["role"]: r for r in await self._all("SELECT * FROM model", ())}

    # ========== 交易员 ==========

    async def add_trade_run(self, r: dict[str, Any]) -> None:
        """一天一条，同一天再写就覆盖"""
        await self.db.execute(
            """INSERT OR REPLACE INTO trade_run(day, signal_ms, started_at, finished_at, status, user_id, factors_json,
                   legs_json, targets_json, orders_json, equity_before, equity_after, error, board_json)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (r["day"], r["signal_ms"], r["started_at"], r["finished_at"], r["status"], r.get("user_id"),
             *(dumps(r[k]) if r.get(k) is not None else None for k in ("factors", "legs", "targets", "orders")),
             r.get("equity_before"), r.get("equity_after"), r.get("error"),
             dumps(r["board"]) if r.get("board") is not None else None))
        await self.db.commit()

    async def trade_run(self, day: str) -> dict[str, Any] | None:
        return await self._one("SELECT * FROM trade_run WHERE day = ?", (day,))

    async def latest_board(self) -> dict[str, Any] | None:
        """最近一次算出因子看板的调仓：day、signal_ms、board"""
        return await self._one("SELECT day, signal_ms, board_json FROM trade_run WHERE board_json IS NOT NULL "
                               "ORDER BY day DESC LIMIT 1", ())

    async def trade_runs(self, limit: int = 200) -> list[dict[str, Any]]:
        """新的在前"""
        return await self._all("SELECT * FROM trade_run ORDER BY day DESC LIMIT ?", (limit,))

    async def last_trade_legs(self) -> dict[str, Any] | None:
        """最近一次出了目标的调仓里每条腿的多空集合：下一期的缓冲按它算"""
        row = await self._one("SELECT legs_json FROM trade_run WHERE legs_json IS NOT NULL ORDER BY day DESC LIMIT 1", ())
        return row["legs"] if row else None

    async def trade_user(self) -> int | None:
        """交易员账户的 userId：调仓第一步建号时记下的"""
        row = await self._one("SELECT user_id FROM trade_run WHERE user_id IS NOT NULL ORDER BY day DESC LIMIT 1", ())
        return row["user_id"] if row else None

    async def rebalances(self) -> list[dict[str, Any]]:
        """每次调仓前的净值，旧的在前：一天的盈亏就是这一次到下一次之间的变化"""
        return await self._all("SELECT day, equity_before FROM trade_run WHERE equity_before IS NOT NULL ORDER BY day", ())

    async def last_targets(self) -> dict[str, Any] | None:
        """最近一次出了目标的调仓：day、equity_before、targets"""
        return await self._one("SELECT day, equity_before, targets_json FROM trade_run WHERE targets_json IS NOT NULL "
                               "ORDER BY day DESC LIMIT 1", ())

    async def add_trade_equity(self, ts: int, equity: float, balance: float, long_notional: float,
                               short_notional: float) -> None:
        await self.db.execute(
            "INSERT OR REPLACE INTO trade_equity(ts, equity, balance, long_notional, short_notional) VALUES (?, ?, ?, ?, ?)",
            (ts, equity, balance, long_notional, short_notional))
        await self.db.commit()

    async def trade_equity(self) -> list[dict[str, Any]]:
        return await self._all("SELECT * FROM trade_equity ORDER BY ts", ())

    async def last_equity_ts(self) -> int | None:
        row = await self._one("SELECT MAX(ts) AS ts FROM trade_equity", ())
        return row["ts"] if row else None

    async def trade_factor_ids(self) -> set[str]:
        """勾选的因子 id，可能有已经不在库里的"""
        return {r["factor_id"] for r in await self._all("SELECT factor_id FROM trade_factor", ())}

    async def set_trade_factor(self, factor_id: str, selected: bool) -> None:
        if selected:
            await self.db.execute("INSERT OR IGNORE INTO trade_factor(factor_id, selected_at) VALUES (?, ?)",
                                  (factor_id, now_ms()))
        else:
            await self.db.execute("DELETE FROM trade_factor WHERE factor_id = ?", (factor_id,))
        await self.db.commit()

    async def policy(self) -> dict[str, Any] | None:
        """研究方针的当前版，一版都没有是 None"""
        return await self._one("SELECT * FROM policy ORDER BY id DESC LIMIT 1", ())

    async def policies(self) -> list[dict[str, Any]]:
        """各版的编号和时间，最新在前"""
        return await self._all("SELECT id, created_at FROM policy ORDER BY id DESC", ())

    async def add_policy(self, row: Mapping[str, Any]) -> dict[str, Any]:
        """存成新的一版，返回它"""
        await self.db.execute(
            "INSERT INTO policy(families_json, gen_rules, implement_checks, review_rules, created_at) VALUES (?, ?, ?, ?, ?)",
            (json.dumps(row["families"], ensure_ascii=False), row["gen_rules"], row["implement_checks"],
             row["review_rules"], now_ms()))
        await self.db.commit()
        return await self.policy()

    async def carry(self) -> dict[str, Any]:
        """carry 腿开没开、占多少资金、什么时候改的；没设过是开着、占 1/4（和建表的默认值一样）"""
        row = await self._one("SELECT enabled, share, changed_at FROM trade_carry WHERE id = 1", ())
        if row is None:
            return {"enabled": True, "share": 0.25, "changedAt": None}
        return {"enabled": bool(row["enabled"]), "share": float(row["share"]), "changedAt": row["changed_at"]}

    async def set_carry(self, enabled: bool | None = None, share: float | None = None) -> None:
        """没给的那项保持原样"""
        cur = await self.carry()
        await self.db.execute("INSERT OR REPLACE INTO trade_carry(id, enabled, share, changed_at) VALUES (1, ?, ?, ?)",
                              (int(cur["enabled"] if enabled is None else enabled), cur["share"] if share is None else share,
                               now_ms()))
        await self.db.commit()

    async def sizing(self) -> dict[str, Any]:
        """交易员的杠杆、保证金比例、什么时候改的；没设过是杠杆 4、保证金一半（和建表的注释一样）"""
        row = await self._one("SELECT leverage, margin, changed_at FROM trade_sizing WHERE id = 1", ())
        if row is None:
            return {"leverage": 4, "margin": 0.5, "changedAt": None}
        return {"leverage": int(row["leverage"]), "margin": float(row["margin"]), "changedAt": row["changed_at"]}

    async def set_sizing(self, leverage: int, margin: float) -> None:
        await self.db.execute("INSERT OR REPLACE INTO trade_sizing(id, leverage, margin, changed_at) VALUES (1, ?, ?, ?)",
                              (leverage, margin, now_ms()))
        await self.db.commit()

    async def trade_days(self) -> list[dict[str, Any]]:
        """给调度找要归因的日子：每次调仓的 day、signal_ms、调仓前净值、出没出目标、归因过没有，旧的在前"""
        return await self._all("SELECT day, signal_ms, equity_before, targets_json IS NOT NULL AS has_targets, "
                               "attrib_json IS NOT NULL AS has_attrib "
                               "FROM trade_run WHERE equity_before IS NOT NULL ORDER BY day", ())

    async def trade_daily(self) -> list[dict[str, Any]]:
        """给 /trader/daily：每次调仓的 day、调仓前净值、按腿归因（还没算是 None），旧的在前"""
        return await self._all("SELECT day, equity_before, attrib_json FROM trade_run WHERE equity_before IS NOT NULL "
                               "ORDER BY day", ())

    async def prev_targets(self, day: str) -> dict[str, Any] | None:
        """day 之前最近一次出了目标的调仓的目标：换仓成本按它算"""
        row = await self._one("SELECT targets_json FROM trade_run WHERE targets_json IS NOT NULL AND day < ? "
                              "ORDER BY day DESC LIMIT 1", (day,))
        return row["targets"] if row else None

    async def set_trade_attrib(self, day: str, attrib: dict[str, Any]) -> None:
        await self.db.execute("UPDATE trade_run SET attrib_json = ? WHERE day = ?", (dumps(attrib), day))
        await self.db.commit()

    # ========== 全局试验计数 ==========

    async def bump_trials(self, n: int) -> int:
        await self.db.execute("UPDATE trials SET count = count + ? WHERE id = 1", (n,))
        await self.db.commit()
        return await self.trials()

    async def trials(self) -> int:
        row = await self._one("SELECT count FROM trials WHERE id = 1", ())
        return int(row["count"]) if row else 0

    # ========== 内部 ==========

    async def _one(self, sql: str, args: tuple) -> dict[str, Any] | None:
        async with self.db.execute(sql, args) as cur:
            row = await cur.fetchone()
        return _decode(row) if row else None

    async def _all(self, sql: str, args: tuple) -> list[dict[str, Any]]:
        async with self.db.execute(sql, args) as cur:
            rows = await cur.fetchall()
        return [_decode(r) for r in rows]


def _decode(row: aiosqlite.Row) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key in row.keys():
        value = row[key]
        if key in _JSON_COLUMNS:
            out[_JSON_COLUMNS[key]] = json.loads(value) if value else None
        else:
            out[key] = value
    return out
