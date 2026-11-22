import { useEffect, useState } from 'react';
import { ApiError, traderApi } from '../api';
import type { TraderLiveEvent, WakeCall, WakeTrace } from '../types';

/** 重连退避：2s 起翻倍，封顶 30s */
const BACKOFF_MIN_MS = 2_000;
const BACKOFF_MAX_MS = 30_000;

/** 可中止的等待：signal 一响立刻醒 */
const sleep = (ms: number, signal: AbortSignal) => new Promise<void>(resolve => {
  const timer = setTimeout(resolve, ms);
  signal.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once: true });
});

/**
 * 订阅流的重连循环：connect 返回（服务端关流/网络断）就退避后再连，连稳过一个封顶周期退避归零。
 * 业务拒绝（ApiError：trader 不存在、未登录）重连也没用，直接停；signal 中止即彻底退出
 */
async function reconnectLoop(connect: (signal: AbortSignal) => Promise<void>, signal: AbortSignal) {
  let backoff = BACKOFF_MIN_MS;
  while (!signal.aborted) {
    const connectedAt = Date.now();
    try {
      await connect(signal);
    } catch (e) {
      if (e instanceof ApiError) return;
    }
    if (signal.aborted) return;
    if (Date.now() - connectedAt > BACKOFF_MAX_MS) backoff = BACKOFF_MIN_MS;
    await sleep(backoff, signal);
    backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
  }
}

function startTrace(e: Extract<TraderLiveEvent, { type: 'run_start' }>): WakeTrace {
  return {
    v: 1, kind: e.kind, symbol: e.symbol, wakeTime: e.wakeTime, startedAt: e.startedAt, budgetSeconds: e.budgetSeconds,
    equity: e.equity, positions: e.positions, pendingOrders: e.pendingOrders, calls: [],
  };
}

/** 改第 n 次 call；回放里已结束的 call 只有 model_end 没 model_start，没有就补建 */
function withCall(trace: WakeTrace, n: number, patch: (c: WakeCall) => WakeCall): WakeTrace {
  const calls = trace.calls.slice();
  calls[n - 1] = patch(calls[n - 1] ?? { n, text: '', toolCalls: [], results: [] });
  return { ...trace, calls };
}

/** 一轮开始和结束之间的帧逐个归约进它的轨迹 */
function applyFrame(trace: WakeTrace, e: Exclude<TraderLiveEvent, { type: 'run_start' | 'run_end' }>): WakeTrace {
  switch (e.type) {
    case 'prompt':
      return { ...trace, prompt: { system: e.system, instruction: e.instruction } };
    case 'stage':
      return { ...trace, stages: [...(trace.stages ?? []), { n: e.n, call: e.call, instruction: e.instruction }] };
    case 'model_start':
      return withCall(trace, e.call, c => c);
    case 'token':
      return withCall(trace, e.call, c => ({ ...c, text: c.text + e.text }));
    case 'model_end':
      return withCall(trace, e.call, c => ({ ...c, text: e.text, toolCalls: e.toolCalls }));
    case 'tool_result':
      return withCall(trace, e.call, c => ({
        ...c, results: [...c.results, { id: e.id, name: e.name, status: e.status, preview: e.preview }],
      }));
  }
}

/** 正在跑的一轮：id 是它在现场流里的编号 */
export interface LiveRun {
  id: string;
  trace: WakeTrace;
}

export interface TraderLive {
  /** 正在跑的几轮，按开始的先后：看整盘的一轮，和各自只处理一个币的几轮。一轮结束就从这里拿掉 */
  runs: LiveRun[];
  /** 最近一轮结束的本地时刻，0=还没结束过；页面据此重拉净值/时间线 */
  endedAt: number;
}

/**
 * 一只 trader 的现场：帧按 run 归到各自那一轮的 WakeTrace，run_start 建、run_end 拿掉。
 * 断线时还在跑的都按结束算（结束帧可能在断线期间错过了）；重连后服务端回放，每一轮从 run_start 整个重建
 */
export function useTraderLive(traderId: number): TraderLive {
  const [state, setState] = useState<TraderLive>({ runs: [], endedAt: 0 });

  useEffect(() => {
    const ctrl = new AbortController();
    // 在跑的几轮：每帧改它那一轮再整份下发；Map 记着开始的先后
    const traces = new Map<string, WakeTrace>();
    const publish = (endedAt?: number) => setState(s => ({
      runs: [...traces].map(([id, trace]) => ({ id, trace })),
      endedAt: endedAt ?? s.endedAt,
    }));
    const onFrame = (e: TraderLiveEvent) => {
      if (e.type === 'run_start') {
        traces.set(e.run, startTrace(e));
        publish();
        return;
      }
      const trace = traces.get(e.run);
      // 没见过开场就来了过程帧（订阅正好卡在 run_start 扇出之后）：等它的回放从 run_start 重来
      if (!trace) return;
      if (e.type === 'run_end') {
        traces.delete(e.run);
        publish(Date.now());
        return;
      }
      traces.set(e.run, applyFrame(trace, e));
      publish();
    };
    const dropped = () => {
      if (!traces.size) return;
      traces.clear();
      publish(Date.now());
    };
    void reconnectLoop(signal => traderApi.live(traderId, onFrame, signal).finally(dropped), ctrl.signal);
    return () => ctrl.abort();
  }, [traderId]);

  return state;
}
