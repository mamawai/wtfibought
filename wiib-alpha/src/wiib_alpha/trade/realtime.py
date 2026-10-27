"""交易员页的实时数：有人开着页面时每 INTERVAL_S 秒问一次 sim，净值、余额、可用、今天的盈亏和持仓推给所有在看的人；
没人看就不问。多少人看都只问这一次：每人一个只留最新一帧的队列，后来的人一订上先拿到最近那帧。
今天 = 最近一次调仓前的净值算起（调仓日，UTC 00:15 起），和 /trader/daily 的一天同口径。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import FastAPI

from ..store import Store, now_ms
from . import attrib, executor, scheduler

logger = logging.getLogger(__name__)

INTERVAL_S = 3.0


class Realtime:
    def __init__(self, app: FastAPI) -> None:
        self.app = app
        self.queues: set[asyncio.Queue] = set()
        self.task: asyncio.Task | None = None
        self.latest: dict[str, Any] | None = None

    def subscribe(self) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=1)
        if self.task is None:
            self.task = asyncio.create_task(self._loop())
        elif self.latest is not None:
            queue.put_nowait(self.latest)
        self.queues.add(queue)
        return queue

    def unsubscribe(self, queue: asyncio.Queue) -> None:
        """最后一个人走了就停，下次有人来重新问"""
        self.queues.discard(queue)
        if not self.queues and self.task is not None:
            self.task.cancel()
            self.task = None
            self.latest = None

    async def _loop(self) -> None:
        while True:
            try:
                latest = await frame(self.app)
            except Exception:
                # sim 不通时每一帧都会失败：只在从好变坏那一下记日志
                if self.latest is None or self.latest["ok"]:
                    logger.exception("交易员实时数问 sim 失败")
                latest = {"ts": now_ms(), "ok": False}
            self.latest = latest
            for queue in self.queues:
                if queue.full():
                    queue.get_nowait()
                queue.put_nowait(latest)
            await asyncio.sleep(INTERVAL_S)


async def frame(app: FastAPI) -> dict[str, Any]:
    """一帧；还没建账户的只有 userId: None。持仓的 legs 是最近一次出了目标的调仓里两条腿各给这个币的目标名义"""
    store: Store = app.state.store
    user_id = await store.trade_user()
    if user_id is None:
        return {"ts": now_ms(), "ok": True, "userId": None}
    balance, positions = await asyncio.to_thread(scheduler.account, app.state.sim, user_id)
    equity = executor.equity(balance, positions)
    starts = await store.rebalances()
    start = starts[-1] if starts else None
    last = await store.last_targets()
    legs = {}
    if last is not None:
        unit = last["equity_before"] * attrib.multiple(last["targets"])
        legs = {c["symbol"]: {k: c[k] * unit for k in attrib.LEGS} for c in last["targets"]["coins"]}
    return {
        "ts": now_ms(), "ok": True, "userId": user_id, "equity": equity, "balance": float(balance["balance"]),
        "available": float(balance["available"]),
        "day": start["day"] if start else None,
        "dayStart": start["equity_before"] if start else None,
        "dayPnl": equity - start["equity_before"] if start else None,
        "positions": [{"symbol": p["symbol"], "side": p["side"], "quantity": p["quantity"], "notional": p["positionValue"],
                       "entryPrice": p["entryPrice"], "markPrice": p["markPrice"], "unrealizedPnl": p["unrealizedPnl"],
                       "legs": legs.get(p["symbol"])} for p in positions],
        "positionsDay": last["day"] if last else None,
    }
