import { useState, useCallback, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { aiAgentApi } from '../api';
import { parseRiskTags, translateRiskTag } from '../lib/utils';
import { useToast } from '../components/ui/use-toast';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Badge } from '../components/ui/badge';
import { Loader2, Brain, TrendingUp, TrendingDown, User, AlertTriangle, CheckCircle2, BarChart3, Zap, ArrowUpCircle, ArrowDownCircle, MinusCircle, Target, ShieldAlert, ChevronDown, ChevronUp, FileText, Radio, Clock, History } from 'lucide-react';
import type { BehaviorAnalysisReport, CryptoAnalysisReport, QuantLatestSignal, QuantForecastCycle } from '../types';

type Tab = 'behavior' | 'crypto';

const HORIZON_LABELS: Record<string, string> = { '0_10': '0-10min', '10_20': '10-20min', '20_30': '20-30min' };

function normalizeQuantSymbolInput(value?: string | null) {
  const raw = (value || '').trim().toUpperCase();
  if (!raw) return 'BTCUSDT';
  if (raw.endsWith('USDT')) return raw;
  if (raw.endsWith('USDC')) return `${raw.slice(0, -4)}USDT`;
  return `${raw}USDT`;
}

function ConfidenceBar({ confidence }: { confidence: number }) {
  const color = confidence >= 80 ? 'bg-green-500' : confidence >= 60 ? 'bg-amber-500' : 'bg-red-500';
  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 h-2 rounded-full bg-muted overflow-hidden">
        <div className={`h-full rounded-full ${color} transition-all duration-500`} style={{ width: `${confidence}%` }} />
      </div>
      <span className="text-xs font-mono font-bold tabular-nums min-w-[3rem] text-right">{confidence}%</span>
    </div>
  );
}

function ImportantNewsCard({ news }: { news: { title: string; sentiment: string; summary: string } }) {
  const sentimentColor = news.sentiment === 'POSITIVE' ? 'text-green-500' : news.sentiment === 'NEGATIVE' ? 'text-red-500' : 'text-muted-foreground';
  return (
    <div className="p-3 rounded-lg border bg-card">
      <div className="flex items-start gap-2">
        <FileText className="w-4 h-4 shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium leading-tight mb-1">{news.title}</p>
          <p className={`text-xs ${sentimentColor} mb-1`}>{news.sentiment}</p>
          <p className="text-xs text-muted-foreground leading-relaxed">{news.summary}</p>
        </div>
      </div>
    </div>
  );
}

function DirectionBadge({ direction }: { direction: string }) {
  const isLong = direction === 'LONG';
  const isShort = direction === 'SHORT';
  return (
    <Badge variant={isLong ? 'default' : isShort ? 'destructive' : 'outline'} className="text-xs">
      {isLong ? '做多' : isShort ? '做空' : '观望'}
    </Badge>
  );
}

function SignalCard({ signal }: { signal: QuantLatestSignal }) {
  const decisionText = signal.overallDecision === 'FLAT' ? '观望' : signal.overallDecision;
  return (
    <Card className="border-primary/30 bg-primary/5">
      <CardHeader className="pb-2">
        <CardTitle className="text-base flex items-center gap-2">
          <Radio className="w-4 h-4 text-primary" />
          最新信号
          <span className="text-xs text-muted-foreground font-normal ml-auto flex items-center gap-1">
            <Clock className="w-3 h-3" />
            {new Date(signal.forecastTime).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex items-center gap-2 flex-wrap">
          <Badge variant="outline" className="text-xs">{signal.symbol}</Badge>
          {signal.riskStatus === 'NORMAL' ? (
            <Badge variant="default" className="text-xs">正常</Badge>
          ) : (
            parseRiskTags(signal.riskStatus).map(tag => (
              <Badge key={tag} variant="destructive" className="text-[10px]">{translateRiskTag(tag)}</Badge>
            ))
          )}
          <span className="text-xs text-muted-foreground">{decisionText}</span>
        </div>
        <div className="grid grid-cols-3 gap-2">
          {signal.signals.map((s) => (
            <div key={s.horizon} className="p-2.5 rounded-lg bg-background border text-center">
              <div className="text-[10px] text-muted-foreground mb-1">{HORIZON_LABELS[s.horizon] || s.horizon}</div>
              <DirectionBadge direction={s.direction} />
              <div className="mt-1.5 text-xs text-muted-foreground">
                置信 {(s.confidence * 100).toFixed(0)}%
              </div>
              {s.direction !== 'NO_TRADE' && (
                <div className="text-[10px] text-muted-foreground mt-0.5">
                  ≤{s.maxLeverage}x · {(s.maxPositionPct * 100).toFixed(1)}%
                </div>
              )}
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

function HistoryList({ items, onSelect }: { items: QuantForecastCycle[]; onSelect: (item: QuantForecastCycle) => void }) {
  if (items.length === 0) return <p className="text-xs text-muted-foreground text-center py-4">暂无历史记录</p>;
  return (
    <div className="space-y-1.5 max-h-64 overflow-y-auto pr-1">
      {items.map((item) => {
        const decisionText = item.overallDecision === 'FLAT' ? '观望' : item.overallDecision;
        return (
          <button
            key={item.cycleId}
            onClick={() => onSelect(item)}
            className="w-full flex items-center gap-3 p-2.5 rounded-lg border hover:bg-muted/50 transition-colors text-left"
          >
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-1 flex-wrap">
                <span className="text-xs font-bold">{item.symbol}</span>
                {item.riskStatus === 'NORMAL' ? (
                  <Badge variant="default" className="text-[10px] h-4">正常</Badge>
                ) : (
                  parseRiskTags(item.riskStatus).map(tag => (
                    <Badge key={tag} variant="destructive" className="text-[10px] h-4">{translateRiskTag(tag)}</Badge>
                  ))
                )}
              </div>
              <div className="text-[10px] text-muted-foreground mt-0.5">{decisionText}</div>
            </div>
            <span className="text-[10px] text-muted-foreground whitespace-nowrap">
              {new Date(item.forecastTime).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}
            </span>
          </button>
        );
      })}
    </div>
  );
}

export function AiAgent() {
  const { toast } = useToast();
  const [tab, setTab] = useState<Tab>('behavior');
  const [loading, setLoading] = useState(false);
  const [steps, setSteps] = useState<string[]>([]);
  const [behaviorReport, setBehaviorReport] = useState<BehaviorAnalysisReport | null>(null);
  const [cryptoReport, setCryptoReport] = useState<CryptoAnalysisReport | null>(null);
  const [cryptoForecastTime, setCryptoForecastTime] = useState<string | null>(null);
  const [reportSymbol, setReportSymbol] = useState('BTCUSDT');
  const [cryptoPending, setCryptoPending] = useState(false);
  const [cryptoPendingMsg, setCryptoPendingMsg] = useState('');
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [symbol, setSymbol] = useState('');
  const [chatMessages, setChatMessages] = useState<{ role: string; content: string }[]>([]);
  const [chatInput, setChatInput] = useState('');
  const [chatLoading, setChatLoading] = useState(false);
  const chatAbortRef = useRef<AbortController | null>(null);

  // 量化信号
  const [latestSignal, setLatestSignal] = useState<QuantLatestSignal | null>(null);
  const [history, setHistory] = useState<QuantForecastCycle[]>([]);
  const [showHistory, setShowHistory] = useState(false);
  const [signalLoading, setSignalLoading] = useState(false);
  const navigate = useNavigate();

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const stopChatStream = useCallback(() => {
    if (chatAbortRef.current) {
      chatAbortRef.current.abort();
      chatAbortRef.current = null;
    }
  }, []);

  // 加载最新信号 + 历史
  const loadSignalData = useCallback(async (sym?: string) => {
    setSignalLoading(true);
    try {
      const [signal, forecasts] = await Promise.allSettled([
        aiAgentApi.latestSignals(sym || undefined),
        aiAgentApi.forecasts(sym || undefined, 10),
      ]);
      if (signal.status === 'fulfilled') setLatestSignal(signal.value);
      if (forecasts.status === 'fulfilled') setHistory(forecasts.value);
    } catch { /* ignore */ }
    setSignalLoading(false);
  }, []);

  // 进入crypto tab时加载
  useEffect(() => {
    if (tab === 'crypto') loadSignalData();
  }, [tab, loadSignalData]);

  // WebSocket订阅量化信号推送
  useEffect(() => {
    if (tab !== 'crypto') return;
    const watchSymbol = symbol?.toUpperCase().replace(/USDT$/, '').trim() || 'BTC';
    const wsSymbol = watchSymbol + 'USDT';

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws/quotes'),
      reconnectDelay: 5000,
      onConnect: () => {
        client.subscribe(`/topic/quant/${wsSymbol}`, (msg) => {
          try {
            const report: CryptoAnalysisReport = JSON.parse(msg.body);
            setCryptoReport(report);
            setCryptoForecastTime(new Date().toISOString());
            setReportSymbol(wsSymbol);
            setCryptoPending(false);
            stopPolling();
            toast('收到新的量化预测', 'success');
          } catch { /* skip */ }
          // 同时刷新信号数据
          loadSignalData(wsSymbol);
        });
      },
    });
    client.activate();
    return () => { client.deactivate(); };
  }, [tab, symbol, toast, loadSignalData, stopPolling]);

  const handleAnalyzeBehavior = useCallback(async () => {
    setLoading(true);
    setSteps([]);
    try {
      const report = await aiAgentApi.analyzeBehavior((step) => {
        setSteps(prev => [...prev, step]);
      }) as unknown as BehaviorAnalysisReport;
      setBehaviorReport(report);
      toast('分析完成', 'success');
    } catch (e: unknown) {
      toast((e as Error).message || '分析失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  const fetchLatestReport = useCallback(async (sym?: string) => {
    try {
      const result = await aiAgentApi.latestCryptoReport(sym || undefined);
      if (result.status === 'ready' && result.report) {
        setCryptoReport(result.report);
        setCryptoForecastTime(result.forecastTime || null);
        setReportSymbol(normalizeQuantSymbolInput(sym));
        setCryptoPending(false);
        setCryptoPendingMsg('');
        stopPolling();
        loadSignalData(sym || undefined);
      } else {
        setCryptoPending(true);
        setCryptoPendingMsg(result.message || '分析进行中，请稍候');
      }
    } catch (e) {
      setCryptoPending(false);
      toast((e as Error).message || '获取失败', 'error');
      stopPolling();
    }
  }, [stopPolling, loadSignalData, toast]);

  const handleAnalyzeCrypto = useCallback(async () => {
    setLoading(true);
    stopChatStream();
    setChatMessages([]);
    setCryptoReport(null);
    setCryptoForecastTime(null);
    setCryptoPending(false);
    stopPolling();

    try {
      const result = await aiAgentApi.latestCryptoReport(symbol || undefined);
      if (result.status === 'ready' && result.report) {
        setCryptoReport(result.report);
        setCryptoForecastTime(result.forecastTime || null);
        setReportSymbol(normalizeQuantSymbolInput(symbol));
        loadSignalData(symbol || undefined);
      } else {
        setCryptoPending(true);
        setCryptoPendingMsg(result.message || '分析进行中，请稍候');
        pollRef.current = setInterval(() => fetchLatestReport(symbol || undefined), 5000);
      }
    } catch (e) {
      toast((e as Error).message || '获取失败', 'error');
    }

    setLoading(false);
  }, [symbol, fetchLatestReport, stopPolling, loadSignalData, stopChatStream, toast]);

  // 清理轮询
  useEffect(() => {
    return () => {
      stopPolling();
      stopChatStream();
    };
  }, [stopPolling, stopChatStream]);

  const handleSelectHistory = useCallback((item: QuantForecastCycle) => {
    if (item.reportJson) {
      try {
        const report: CryptoAnalysisReport = JSON.parse(item.reportJson);
        stopChatStream();
        setCryptoReport(report);
        setCryptoForecastTime(item.forecastTime);
        setReportSymbol(item.symbol);
        setChatMessages([]);
        setShowHistory(false);
      } catch { /* skip */ }
    }
  }, [stopChatStream]);

  const handleChat = useCallback(async () => {
    if (!chatInput.trim() || !cryptoReport || chatLoading) return;
    const userMsg = chatInput.trim();
    setChatInput('');
    const newHistory = [...chatMessages, { role: 'user', content: userMsg }];
    setChatMessages(newHistory);
    setChatLoading(true);
    const abortController = new AbortController();
    chatAbortRef.current = abortController;
    try {
      const summaryContext = JSON.stringify({
        direction: cryptoReport.direction,
        keyLevels: cryptoReport.keyLevels,
        summary: cryptoReport.summary,
        confidence: cryptoReport.confidence,
      });
      await aiAgentApi.chat(userMsg, summaryContext, chatMessages, (chunk) => {
        setChatMessages(prev => {
          const next = [...prev];
          const last = next[next.length - 1];
          if (!last || last.role !== 'assistant') {
            next.push({ role: 'assistant', content: chunk });
          } else {
            next[next.length - 1] = { ...last, content: last.content + chunk };
          }
          return next;
        });
      }, abortController.signal);
    } catch (e) {
      if (abortController.signal.aborted) {
        return;
      }
      const message = e instanceof Error ? e.message : '回答失败，请重试';
      setChatMessages(prev => {
        const next = [...prev];
        const last = next[next.length - 1];
        if (last?.role === 'assistant') {
          next[next.length - 1] = {
            ...last,
            content: last.content || message,
          };
        } else {
          next.push({ role: 'assistant', content: message });
        }
        return next;
      });
    } finally {
      if (chatAbortRef.current === abortController) {
        chatAbortRef.current = null;
      }
      setChatLoading(false);
    }
  }, [chatInput, chatLoading, chatMessages, cryptoReport]);

  return (
    <div className="max-w-5xl mx-auto p-4 md:p-6 space-y-4">
      <div className="flex items-center gap-3 px-3 py-2 rounded-lg bg-primary/10 border border-primary/20 text-primary text-xs font-bold">
        <Zap className="w-4 h-4" />
        投资有风险，当前分析结果仅供参考不构成任何建议
      </div>

      {/* Tab Switcher */}
      <div className="flex bg-muted rounded-lg p-0.5">
        <button
          onClick={() => setTab('behavior')}
          className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-md text-sm font-bold transition-all ${tab === 'behavior' ? 'bg-background shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}
        >
          <User className="w-4 h-4" />
          行为分析
        </button>
        <button
          onClick={() => setTab('crypto')}
          className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-md text-sm font-bold transition-all ${tab === 'crypto' ? 'bg-background shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}
        >
          <TrendingUp className="w-4 h-4" />
          量化分析
        </button>
      </div>

      {/* 行为分析 */}
      {tab === 'behavior' && (
        <div className="space-y-4">
          {!behaviorReport || !behaviorReport.overview ? (
            <Card>
              <CardContent className="p-8 text-center">
                {!loading ? (
                  <>
                    <Brain className="w-12 h-12 mx-auto text-muted-foreground mb-4" />
                    <h2 className="text-lg font-bold mb-2">用户行为分析</h2>
                    <p className="text-sm text-muted-foreground mb-6 max-w-md mx-auto">
                      基于你的全部历史交易数据、游戏记录、风险偏好等维度，全面分析你的投资行为特征
                    </p>
                    <Button onClick={handleAnalyzeBehavior} size="lg">开始分析</Button>
                  </>
                ) : (
                  <>
                    <Brain className="w-12 h-12 mx-auto text-primary mb-4 animate-pulse" />
                    <h2 className="text-lg font-bold mb-4">正在分析中...</h2>
                    {steps.length > 0 && (
                      <div className="text-left max-w-xs mx-auto space-y-2">
                        {steps.map((step, i) => (
                          <div key={i} className="flex items-center gap-2 text-sm">
                            {i === steps.length - 1 ? (
                              <Loader2 className="w-3.5 h-3.5 animate-spin text-primary shrink-0" />
                            ) : (
                              <CheckCircle2 className="w-3.5 h-3.5 text-green-500 shrink-0" />
                            )}
                            <span className={i === steps.length - 1 ? 'text-foreground' : 'text-muted-foreground'}>
                              {step}
                            </span>
                          </div>
                        ))}
                      </div>
                    )}
                  </>
                )}
              </CardContent>
            </Card>
          ) : (
            <>
              {/* 概览 */}
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-base flex items-center gap-2">
                    <BarChart3 className="w-4 h-4" /> 资产概览
                  </CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  <div className="grid grid-cols-2 gap-4">
                    <div className="p-3 rounded-lg bg-muted/50">
                      <div className="text-2xl font-black tabular-nums">${behaviorReport.overview.totalAssets.toLocaleString()}</div>
                      <div className="text-xs text-muted-foreground">总资产</div>
                    </div>
                    <div className="p-3 rounded-lg bg-muted/50">
                      <div className={`text-2xl font-black tabular-nums ${behaviorReport.overview.totalProfitPct >= 0 ? 'text-green-500' : 'text-red-500'}`}>
                        {behaviorReport.overview.totalProfitPct >= 0 ? '+' : ''}{behaviorReport.overview.totalProfitPct.toFixed(2)}%
                      </div>
                      <div className="text-xs text-muted-foreground">总收益率</div>
                    </div>
                  </div>

                  {behaviorReport.overview.distribution.length > 0 && (
                    <div>
                      <div className="text-xs text-muted-foreground mb-2">资产分布</div>
                      <div className="flex flex-wrap gap-2">
                        {behaviorReport.overview.distribution.map((d, i) => (
                          <Badge key={i} variant="outline">{d.category}: ${d.value.toLocaleString()}</Badge>
                        ))}
                      </div>
                    </div>
                  )}
                </CardContent>
              </Card>

              {/* 交易行为 */}
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-base">交易行为分析</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  {behaviorReport.tradeBehavior.stock.positionCount > 0 && (
                    <div className="p-3 rounded-lg bg-muted/50">
                      <div className="text-xs text-muted-foreground mb-1">股票</div>
                      <div className="grid grid-cols-2 gap-2 text-xs">
                        <span>持仓: <span className="font-bold">{behaviorReport.tradeBehavior.stock.positionCount}</span></span>
                        <span>订单: <span className="font-bold">{behaviorReport.tradeBehavior.stock.orderCount}</span></span>
                        <span>买入额: <span className="font-bold">${behaviorReport.tradeBehavior.stock.totalBuyAmount.toLocaleString()}</span></span>
                        <span>偏好: <span className="font-bold">{behaviorReport.tradeBehavior.stock.preference}</span></span>
                      </div>
                    </div>
                  )}
                  {behaviorReport.tradeBehavior.crypto.positionCount > 0 && (
                    <div className="p-3 rounded-lg bg-muted/50">
                      <div className="text-xs text-muted-foreground mb-1">加密货币</div>
                      <div className="grid grid-cols-2 gap-2 text-xs">
                        <span>持仓: <span className="font-bold">{behaviorReport.tradeBehavior.crypto.positionCount}</span></span>
                        <span>杠杆: <span className="font-bold">{behaviorReport.tradeBehavior.crypto.leverageUsage}</span></span>
                        <span>买入: <span className="font-bold">${behaviorReport.tradeBehavior.crypto.totalBuyAmount.toLocaleString()}</span></span>
                        <span>卖出: <span className="font-bold">${behaviorReport.tradeBehavior.crypto.totalSellAmount.toLocaleString()}</span></span>
                      </div>
                    </div>
                  )}
                  {behaviorReport.tradeBehavior.futures.orderCount > 0 && (
                    <div className="p-3 rounded-lg bg-muted/50">
                      <div className="text-xs text-muted-foreground mb-1">永续合约</div>
                      <div className="grid grid-cols-2 gap-2 text-xs">
                        <span>订单: <span className="font-bold">{behaviorReport.tradeBehavior.futures.orderCount}</span></span>
                        <span>方向: <span className="font-bold">{behaviorReport.tradeBehavior.futures.direction}</span></span>
                        <span>平仓盈亏: <span className="font-bold">{behaviorReport.tradeBehavior.futures.realizedPnl >= 0 ? '+' : ''}{behaviorReport.tradeBehavior.futures.realizedPnl.toFixed(2)}</span></span>
                        <span>平均杠杆: <span className="font-bold">{behaviorReport.tradeBehavior.futures.avgLeverage}x</span></span>
                      </div>
                    </div>
                  )}
                  {behaviorReport.tradeBehavior.prediction.frequency > 0 && (
                    <div className="p-3 rounded-lg bg-muted/50">
                      <div className="text-xs text-muted-foreground mb-1">预测交易</div>
                      <div className="grid grid-cols-2 gap-2 text-xs">
                        <span>频率: <span className="font-bold">{behaviorReport.tradeBehavior.prediction.frequency}次</span></span>
                        <span>胜率: <span className="font-bold">{behaviorReport.tradeBehavior.prediction.winRate}%</span></span>
                        <span>净盈亏: <span className="font-bold">{behaviorReport.tradeBehavior.prediction.netProfit >= 0 ? '+' : ''}{behaviorReport.tradeBehavior.prediction.netProfit.toFixed(2)}</span></span>
                        <span>偏好: <span className="font-bold">{behaviorReport.tradeBehavior.prediction.directionPreference}</span></span>
                      </div>
                    </div>
                  )}
                </CardContent>
              </Card>

              {/* 游戏行为 */}
              {behaviorReport.gameBehavior && (
                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="text-base">游戏行为分析</CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    {behaviorReport.gameBehavior.blackjack.totalHands > 0 && (
                      <div className="p-3 rounded-lg bg-muted/50">
                        <div className="text-xs text-muted-foreground mb-1">Blackjack</div>
                        <div className="grid grid-cols-2 gap-2 text-xs">
                          <span>局数: <span className="font-bold">{behaviorReport.gameBehavior.blackjack.totalHands}</span></span>
                          <span>胜: <span className="font-bold text-green-500">{behaviorReport.gameBehavior.blackjack.totalWon}</span></span>
                          <span>负: <span className="font-bold text-red-500">{behaviorReport.gameBehavior.blackjack.totalLost}</span></span>
                          <span>最大赢: <span className="font-bold">${behaviorReport.gameBehavior.blackjack.biggestWin}</span></span>
                        </div>
                      </div>
                    )}
                    {behaviorReport.gameBehavior.mines.frequency > 0 && (
                      <div className="p-3 rounded-lg bg-muted/50">
                        <div className="text-xs text-muted-foreground mb-1">矿工游戏</div>
                        <div className="grid grid-cols-2 gap-2 text-xs">
                          <span>频率: <span className="font-bold">{behaviorReport.gameBehavior.mines.frequency}次</span></span>
                          <span>净盈亏: <span className="font-bold">{behaviorReport.gameBehavior.mines.netProfit >= 0 ? '+' : ''}{behaviorReport.gameBehavior.mines.netProfit.toFixed(2)}</span></span>
                        </div>
                      </div>
                    )}
                    {behaviorReport.gameBehavior.videoPoker.frequency > 0 && (
                      <div className="p-3 rounded-lg bg-muted/50">
                        <div className="text-xs text-muted-foreground mb-1">视频扑克</div>
                        <div className="grid grid-cols-2 gap-2 text-xs">
                          <span>频率: <span className="font-bold">{behaviorReport.gameBehavior.videoPoker.frequency}次</span></span>
                          <span>净盈亏: <span className="font-bold">{behaviorReport.gameBehavior.videoPoker.netProfit >= 0 ? '+' : ''}{behaviorReport.gameBehavior.videoPoker.netProfit.toFixed(2)}</span></span>
                        </div>
                      </div>
                    )}
                  </CardContent>
                </Card>
              )}

              {/* 风险画像 */}
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-base">风险画像</CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="flex items-center gap-3">
                    <Badge variant={behaviorReport.riskProfile.riskLevel === 'HIGH' ? 'destructive' : behaviorReport.riskProfile.riskLevel === 'MEDIUM' ? 'default' : 'outline'}>
                      {behaviorReport.riskProfile.riskLevel}
                    </Badge>
                    <span className="text-xs">爆仓次数: <span className="font-bold">{behaviorReport.riskProfile.bankruptCount}</span></span>
                    <span className="text-xs">最大回撤: <span className="font-bold">{behaviorReport.riskProfile.maxDrawdown}</span></span>
                  </div>
                </CardContent>
              </Card>

              {/* 建议 */}
              {behaviorReport.suggestions.length > 0 && (
                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="text-base">个性化建议</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <ul className="space-y-2">
                      {behaviorReport.suggestions.map((s, i) => (
                        <li key={i} className="flex items-start gap-2 text-sm">
                          <CheckCircle2 className="w-4 h-4 text-green-500 shrink-0 mt-0.5" />
                          {s}
                        </li>
                      ))}
                    </ul>
                  </CardContent>
                </Card>
              )}

              <Button variant="outline" onClick={() => setBehaviorReport(null)}>重新分析</Button>
            </>
          )}
        </div>
      )}

      {/* 量化分析 */}
      {tab === 'crypto' && (
        <div className="space-y-4">
          {/* 最新信号卡片 */}
          {latestSignal && !signalLoading && (
            <SignalCard signal={latestSignal} />
          )}
          {signalLoading && (
            <div className="flex items-center justify-center py-4 gap-2 text-sm text-muted-foreground">
              <Loader2 className="w-4 h-4 animate-spin" /> 加载信号...
            </div>
          )}

          <Button
            variant="outline"
            className="w-full justify-start gap-2"
            onClick={() => navigate(`/verifications?symbol=${reportSymbol}`)}
          >
            <CheckCircle2 className="w-4 h-4" />
            预测验证
            <span className="text-xs text-muted-foreground ml-auto">查看已验证的预测结果 →</span>
          </Button>

          {!cryptoReport || !cryptoReport.keyLevels ? (
            <Card>
              <CardContent className="p-8 text-center">
                {!loading && !cryptoPending ? (
                  <>
                    <TrendingUp className="w-12 h-12 mx-auto text-muted-foreground mb-4" />
                    <h2 className="text-lg font-bold mb-2">加密货币量化分析</h2>
                    <p className="text-sm text-muted-foreground mb-6 max-w-md mx-auto">
                      后台每30分钟自动生成分析报告，点击获取最新结果
                    </p>
                    <div className="flex items-center justify-center gap-3 max-w-sm mx-auto">
                      <Input
                        placeholder="输入币种，如 BTC、ETH（可选）"
                        value={symbol}
                        onChange={e => setSymbol(e.target.value.toUpperCase())}
                        className="flex-1"
                      />
                      <Button onClick={handleAnalyzeCrypto} disabled={loading}>
                        获取分析
                      </Button>
                    </div>
                    <p className="text-xs text-muted-foreground mt-3">不输入则默认分析 BTC</p>
                  </>
                ) : (
                  <>
                    <TrendingUp className="w-12 h-12 mx-auto text-primary mb-4 animate-pulse" />
                    <h2 className="text-lg font-bold mb-2">分析进行中</h2>
                    <p className="text-sm text-muted-foreground mb-2">{cryptoPendingMsg || '等待后台生成报告...'}</p>
                    <Loader2 className="w-5 h-5 animate-spin mx-auto text-primary" />
                    <p className="text-xs text-muted-foreground mt-4">系统每30分钟生成一次，就快好了</p>
                  </>
                )}
              </CardContent>
            </Card>
          ) : (
            <>
              {/* 报告时间 */}
              {cryptoForecastTime && (
                <div className="flex items-center gap-1.5 text-xs text-muted-foreground px-1">
                  <Clock className="w-3.5 h-3.5" />
                  报告时间：{new Date(cryptoForecastTime).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                </div>
              )}

              {/* 总结 + 置信度 */}
              <Card className="border-l-4 border-l-primary">
                <CardContent className="p-4">
                  <div className="flex items-start gap-3">
                    <div className="shrink-0 mt-0.5">
                      {/^(做多|偏多)/.test(cryptoReport.direction.shortTerm || '')
                        ? <ArrowUpCircle className="w-6 h-6 text-green-500" />
                        : /^(做空|偏空)/.test(cryptoReport.direction.shortTerm || '')
                        ? <ArrowDownCircle className="w-6 h-6 text-red-500" />
                        : <MinusCircle className="w-6 h-6 text-muted-foreground" />}
                    </div>
                    <div className="flex-1">
                      <p className="text-sm font-bold leading-relaxed">{cryptoReport.summary}</p>
                      <div className="mt-2">
                        <ConfidenceBar confidence={cryptoReport.confidence} />
                      </div>
                    </div>
                  </div>
                </CardContent>
              </Card>

              {/* 趋势 */}
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-base">方向判断</CardTitle>
                </CardHeader>
                <CardContent className="flex flex-wrap gap-2">
                  <Badge variant="outline" className="text-xs">
                    超短线: {cryptoReport.direction.ultraShort}
                  </Badge>
                  <Badge variant="outline" className="text-xs">
                    短线: {cryptoReport.direction.shortTerm}
                  </Badge>
                  <Badge variant="outline" className="text-xs">
                    中线: {cryptoReport.direction.mid}
                  </Badge>
                  <Badge variant="outline" className="text-xs">
                    长线: {cryptoReport.direction.longTerm}
                  </Badge>
                </CardContent>
              </Card>

              {/* 预测依据 */}
              {cryptoReport.analysisBasis && (
                <Card className="border-blue-500/30 bg-blue-500/5">
                  <CardHeader className="pb-2">
                    <CardTitle className="text-base flex items-center gap-2 text-blue-600">
                      <Brain className="w-4 h-4" /> AI分析思路
                    </CardTitle>
                  </CardHeader>
                  <CardContent>
                    <p className="text-sm whitespace-pre-wrap leading-relaxed text-muted-foreground">{cryptoReport.analysisBasis}</p>
                  </CardContent>
                </Card>
              )}

              {/* Bull vs Bear 辩论 */}
              {cryptoReport.debateSummary && (
                <Card className="border-purple-500/30 bg-purple-500/5">
                  <CardHeader className="pb-2">
                    <CardTitle className="text-base flex items-center gap-2 text-purple-600">
                      <Zap className="w-4 h-4" /> 多空辩论
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-3">
                    <div className="p-3 rounded-lg border border-green-500/30 bg-green-500/5">
                      <div className="flex items-center gap-1.5 mb-1.5">
                        <TrendingUp className="w-3.5 h-3.5 text-green-500" />
                        <span className="text-xs font-bold text-green-600">Bull 做多方</span>
                      </div>
                      <p className="text-sm leading-relaxed text-muted-foreground">{cryptoReport.debateSummary.bullArgument}</p>
                    </div>
                    <div className="p-3 rounded-lg border border-red-500/30 bg-red-500/5">
                      <div className="flex items-center gap-1.5 mb-1.5">
                        <TrendingDown className="w-3.5 h-3.5 text-red-500" />
                        <span className="text-xs font-bold text-red-600">Bear 做空方</span>
                      </div>
                      <p className="text-sm leading-relaxed text-muted-foreground">{cryptoReport.debateSummary.bearArgument}</p>
                    </div>
                    <div className="p-3 rounded-lg border border-amber-500/30 bg-amber-500/5">
                      <div className="flex items-center gap-1.5 mb-1.5">
                        <ShieldAlert className="w-3.5 h-3.5 text-amber-500" />
                        <span className="text-xs font-bold text-amber-600">Judge 裁判裁决</span>
                      </div>
                      <p className="text-sm leading-relaxed text-muted-foreground">{cryptoReport.debateSummary.judgeReasoning}</p>
                    </div>
                  </CardContent>
                </Card>
              )}

              {/* 关键价位 */}
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-base flex items-center gap-2">
                    <Target className="w-4 h-4" /> 关键价位
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  {(() => {
                    const allLevels = [
                      ...cryptoReport.keyLevels.support.map(p => ({ price: parseFloat(p), type: 'support' as const })),
                      ...cryptoReport.keyLevels.resistance.map(p => ({ price: parseFloat(p), type: 'resistance' as const })),
                    ].sort((a, b) => b.price - a.price);
                    const max = Math.max(...allLevels.map(l => l.price));
                    const min = Math.min(...allLevels.map(l => l.price));
                    const range = max - min || 1;
                    return (
                      <div className="space-y-1.5">
                        {allLevels.map((level, i) => {
                          const pct = ((level.price - min) / range) * 100;
                          const isSupport = level.type === 'support';
                          return (
                            <div key={i} className="flex items-center gap-3 text-xs">
                              <span className={`w-8 font-bold ${isSupport ? 'text-green-500' : 'text-red-500'}`}>
                                {isSupport ? '支撑' : '阻力'}
                              </span>
                              <div className="flex-1 h-2 rounded-full bg-muted relative">
                                <div
                                  className={`absolute top-0 h-full rounded-full ${isSupport ? 'bg-green-500/40' : 'bg-red-500/40'}`}
                                  style={{ width: `${pct}%` }}
                                />
                              </div>
                              <span className="font-mono font-bold tabular-nums min-w-[5rem] text-right">
                                ${level.price.toLocaleString()}
                              </span>
                            </div>
                          );
                        })}
                      </div>
                    );
                  })()}
                </CardContent>
              </Card>

              {/* 指标 */}
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-base flex items-center gap-2">
                    <BarChart3 className="w-4 h-4" /> 技术指标
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="text-sm whitespace-pre-wrap leading-relaxed">{cryptoReport.indicators}</p>
                </CardContent>
              </Card>

              {/* 重要新闻 */}
              {cryptoReport.importantNews && cryptoReport.importantNews.length > 0 && (
                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="text-base flex items-center gap-2">
                      <FileText className="w-4 h-4" /> 重要新闻
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-2">
                    {cryptoReport.importantNews.map((news, i) => (
                      <ImportantNewsCard key={i} news={news} />
                    ))}
                  </CardContent>
                </Card>
              )}

              {/* 交易方案 */}
              {cryptoReport.positionAdvice && cryptoReport.positionAdvice.length > 0 && (
                <Card>
                  <CardHeader className="pb-2">
                    <CardTitle className="text-base flex items-center gap-2">
                      <Zap className="w-4 h-4" /> 交易方案
                    </CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-3">
                    {cryptoReport.positionAdvice.map((advice, i) => {
                      const isLong = advice.type === 'LONG';
                      return (
                        <div key={i} className={`p-3 rounded-lg border ${isLong ? 'border-green-500/30 bg-green-500/5' : 'border-red-500/30 bg-red-500/5'}`}>
                          <div className="flex items-center gap-2 mb-2">
                            {isLong
                              ? <TrendingUp className="w-4 h-4 text-green-500" />
                              : <TrendingDown className="w-4 h-4 text-red-500" />}
                            <span className={`text-sm font-black ${isLong ? 'text-green-500' : 'text-red-500'}`}>
                              {advice.period} {advice.type}
                            </span>
                          </div>
                          <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
                            <div>入场: <span className="font-mono font-bold">{advice.entry}</span></div>
                            <div>止损: <span className="font-mono font-bold text-red-500">{advice.stopLoss}</span></div>
                            <div>止盈: <span className="font-mono font-bold text-green-500">{advice.takeProfit}</span></div>
                            <div>盈亏比: <span className="font-bold">{advice.riskReward}</span></div>
                          </div>
                        </div>
                      );
                    })}
                  </CardContent>
                </Card>
              )}

              {/* 风险 */}
              {cryptoReport.riskWarnings && cryptoReport.riskWarnings.length > 0 && (
                <Card className="border-amber-500/30">
                  <CardHeader className="pb-2">
                    <CardTitle className="text-base flex items-center gap-2 text-amber-600">
                      <ShieldAlert className="w-4 h-4" /> 风险提示
                    </CardTitle>
                  </CardHeader>
                  <CardContent>
                    <ul className="space-y-2">
                      {cryptoReport.riskWarnings.map((warning, i) => (
                        <li key={i} className="flex items-start gap-2 text-sm">
                          <AlertTriangle className="w-3.5 h-3.5 text-amber-500 shrink-0 mt-0.5" />
                          <span>{warning}</span>
                        </li>
                      ))}
                    </ul>
                  </CardContent>
                </Card>
              )}

              {/* 追问对话 */}
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-base flex items-center gap-2">
                    <Brain className="w-4 h-4" /> 追问分析师
                  </CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                  {chatMessages.length > 0 && (
                    <div className="space-y-3 max-h-80 overflow-y-auto pr-1">
                      {chatMessages.map((msg, i) => (
                        <div key={i} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                          {msg.role === 'user' ? (
                            <div className="max-w-[80%] px-3 py-2 rounded-2xl rounded-tr-sm bg-primary text-primary-foreground text-sm">
                              {msg.content}
                            </div>
                          ) : (
                            <div className="max-w-[90%] p-3 rounded-2xl rounded-tl-sm bg-muted space-y-1.5">
                              {msg.content.split('\n').filter(Boolean).map((line, j) => {
                                const boldMatch = line.match(/^\*\*(.+?)\*\*[:：]?\s*(.*)/);
                                if (boldMatch) {
                                  return (
                                    <p key={j} className="text-sm">
                                      <span className="font-bold">{boldMatch[1]}</span>
                                      {boldMatch[2] && <span className="text-muted-foreground">: {boldMatch[2]}</span>}
                                    </p>
                                  );
                                }
                                return <p key={j} className="text-sm text-muted-foreground">{line}</p>;
                              })}
                            </div>
                          )}
                        </div>
                      ))}
                      {chatLoading && (
                        <div className="flex justify-start">
                          <div className="bg-muted px-4 py-2.5 rounded-2xl rounded-tl-sm flex items-center gap-1.5">
                            <span className="w-1.5 h-1.5 rounded-full bg-muted-foreground/40 animate-bounce" style={{ animationDelay: '0ms' }} />
                            <span className="w-1.5 h-1.5 rounded-full bg-muted-foreground/40 animate-bounce" style={{ animationDelay: '150ms' }} />
                            <span className="w-1.5 h-1.5 rounded-full bg-muted-foreground/40 animate-bounce" style={{ animationDelay: '300ms' }} />
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                  {chatMessages.length === 0 && (
                    <p className="text-xs text-muted-foreground text-center py-2">基于当前分析报告追问，AI 会结合上下文回答</p>
                  )}
                  <div className="flex gap-2">
                    <Input
                      placeholder="如：现在适合做空吗？止损设在哪？"
                      value={chatInput}
                      onChange={e => setChatInput(e.target.value)}
                      onKeyDown={e => e.key === 'Enter' && !e.nativeEvent.isComposing && handleChat()}
                      disabled={chatLoading}
                      className="flex-1"
                    />
                    <Button onClick={handleChat} disabled={chatLoading || !chatInput.trim()} size="sm">
                      {chatLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : '发送'}
                    </Button>
                  </div>
                </CardContent>
              </Card>

              {/* 历史预测 */}
              <Card>
                <CardHeader className="pb-2">
                  <button
                    onClick={() => setShowHistory(!showHistory)}
                    className="w-full flex items-center gap-2 text-base font-semibold"
                  >
                    <History className="w-4 h-4" />
                    历史预测
                    <span className="text-xs text-muted-foreground font-normal">({history.length}条)</span>
                    {showHistory ? <ChevronUp className="w-4 h-4 ml-auto" /> : <ChevronDown className="w-4 h-4 ml-auto" />}
                  </button>
                </CardHeader>
                {showHistory && (
                  <CardContent>
                    <HistoryList items={history} onSelect={handleSelectHistory} />
                  </CardContent>
                )}
              </Card>

              <Button variant="outline" onClick={() => { stopChatStream(); setCryptoReport(null); setCryptoForecastTime(null); setChatMessages([]); stopPolling(); }}>重新获取</Button>
            </>
          )}
        </div>
      )}

    </div>
  );
}
