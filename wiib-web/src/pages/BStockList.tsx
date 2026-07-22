import { useEffect, useMemo, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { bstockApi } from '../api';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Skeleton } from '../components/ui/skeleton';
import { useToast } from '../components/ui/use-toast';
import { cn, fmtNum } from '../lib/utils';
import { Landmark, RefreshCcw, Search, X, ArrowUpDown, ChevronRight } from 'lucide-react';
import type { BStock } from '../types';

type SortField = 'default' | 'price' | 'change' | 'cap';
type SortOrder = 'asc' | 'desc';

const fmtCap = (v?: number) => {
  if (v == null) return '-';
  if (v >= 1e12) return `${(v / 1e12).toFixed(2)}T`;
  if (v >= 1e9) return `${(v / 1e9).toFixed(1)}B`;
  if (v >= 1e6) return `${(v / 1e6).toFixed(1)}M`;
  return fmtNum(v);
};

export function BStockList() {
  const navigate = useNavigate();
  const { toast } = useToast();
  const [stocks, setStocks] = useState<BStock[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [query, setQuery] = useState('');
  const [sortField, setSortField] = useState<SortField>('cap');
  const [sortOrder, setSortOrder] = useState<SortOrder>('desc');

  const load = useCallback((silent = false) => {
    bstockApi.list()
      .then(setStocks)
      .catch(() => { if (!silent) toast('获取 bStock 列表失败', 'error', { description: '请稍后重试' }); })
      .finally(() => setLoaded(true));
  }, [toast]);

  useEffect(() => {
    load();
    const t = setInterval(() => load(true), 8000); // 8s 静默刷新，价格保持鲜活
    return () => clearInterval(t);
  }, [load]);

  const processed = useMemo(() => {
    let r = [...stocks];
    const q = query.trim().toLowerCase();
    if (q) r = r.filter(s =>
      s.name?.toLowerCase().includes(q) ||
      s.ticker?.toLowerCase().includes(q) ||
      s.industry?.toLowerCase().includes(q));
    if (sortField !== 'default') {
      r.sort((a, b) => {
        const pick = (s: BStock) => sortField === 'price' ? (s.price ?? 0) : sortField === 'change' ? (s.changePct ?? 0) : (s.marketCap ?? 0);
        return sortOrder === 'asc' ? pick(a) - pick(b) : pick(b) - pick(a);
      });
    }
    return r;
  }, [stocks, query, sortField, sortOrder]);

  const toggleSort = (f: SortField) => {
    if (sortField === f) setSortOrder(o => o === 'asc' ? 'desc' : 'asc');
    else { setSortField(f); setSortOrder('desc'); }
  };

  return (
    <div className="page-shell px-4 md:px-6 py-5 space-y-4">
      <Card className="overflow-hidden">
        <CardHeader className="pb-3">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <CardTitle className="flex items-center gap-2 text-base">
              <div className="p-1.5 rounded-lg bg-primary/10">
                <Landmark className="w-4 h-4 text-primary" />
              </div>
              股票 · bStock
              <span className="text-xs text-muted-foreground font-normal ml-2">代币化美股 · 共 {stocks.length} 支</span>
            </CardTitle>

            <div className="flex items-center gap-2">
              <div className="relative flex-1 sm:w-64">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
                <Input value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索名称 / 代码 / 行业" className="pl-9 pr-9 h-9" />
                {query.trim() && (
                  <button type="button" onClick={() => setQuery('')} className="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded-md hover:bg-surface-hover transition-colors" aria-label="清空搜索">
                    <X className="w-4 h-4 text-muted-foreground" />
                  </button>
                )}
              </div>
              <Button variant="outline" size="icon" className="h-9 w-9 shrink-0" onClick={() => { load(); toast('已刷新', 'info'); }}>
                <RefreshCcw className="w-4 h-4" />
              </Button>
            </div>
          </div>

          <div className="flex items-center gap-2 overflow-x-auto pb-1 mt-4">
            <span className="text-sm text-muted-foreground whitespace-nowrap shrink-0">排序：</span>
            {([['cap', '市值'], ['price', '价格'], ['change', '涨跌幅'], ['default', '默认']] as [SortField, string][]).map(([f, label]) => (
              <Button key={f} variant={sortField === f ? 'secondary' : 'ghost'} size="sm" className="h-8 text-xs gap-1 shrink-0" onClick={() => f === 'default' ? setSortField('default') : toggleSort(f)}>
                {label}
                {sortField === f && f !== 'default' && <ArrowUpDown className="w-3 h-3" />}
              </Button>
            ))}
          </div>
        </CardHeader>

        <CardContent className="p-0 divide-y divide-border/30">
          {!loaded ? (
            Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="px-4 py-3.5 flex items-center justify-between gap-3">
                <div className="flex items-center gap-2.5 flex-1">
                  <Skeleton className="w-9 h-9 rounded-lg" />
                  <div className="space-y-1.5"><Skeleton className="h-3.5 w-24" /><Skeleton className="h-3 w-32" /></div>
                </div>
                <div className="space-y-1.5 text-right"><Skeleton className="h-3.5 w-16 ml-auto" /><Skeleton className="h-3 w-12 ml-auto" /></div>
              </div>
            ))
          ) : processed.length > 0 ? (
            processed.map(s => {
              const chg = s.changePct ?? 0;
              const up = chg >= 0;
              return (
                <button
                  type="button"
                  key={s.id}
                  onClick={() => navigate(`/bstock/${s.symbol}`)}
                  className="w-full text-left px-4 py-3.5 cursor-pointer hover:bg-surface-hover active:bg-secondary transition-colors group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                >
                  <div className="flex items-center justify-between gap-3">
                    <div className="flex items-center gap-3 min-w-0 flex-1">
                      <div className="w-10 h-10 rounded-md border border-border bg-card-2 flex items-center justify-center shrink-0 text-[10px] font-bold text-muted-foreground tracking-tight">
                        {s.ticker?.slice(0, 4)}
                      </div>
                      <div className="min-w-0">
                        <div className="flex items-center gap-2 mb-1">
                          <span className="font-semibold text-sm truncate group-hover:text-primary transition-colors">{s.name}</span>
                          <span className="text-xs text-muted-foreground/70 shrink-0">{s.ticker}</span>
                        </div>
                        <div className="flex items-center gap-2 text-xs text-muted-foreground">
                          {s.industry && <><span>{s.industry}</span><span className="text-border">·</span></>}
                          <span>市值 {fmtCap(s.marketCap)}</span>
                          {s.peRatio != null && <><span className="text-border">·</span><span>PE {s.peRatio}</span></>}
                        </div>
                      </div>
                    </div>
                    <div className="text-right shrink-0 flex items-center gap-2.5">
                      <div>
                        <div className="num text-sm font-semibold tracking-tight">{s.price != null ? fmtNum(s.price) : '-'}</div>
                        <div className={cn("num text-xs font-semibold mt-0.5", up ? "text-gain" : "text-loss")}>
                          {up ? '+' : ''}{chg.toFixed(2)}%
                        </div>
                      </div>
                      <ChevronRight className="w-4 h-4 text-muted-foreground/40 group-hover:text-primary/60 transition-colors" />
                    </div>
                  </div>
                </button>
              );
            })
          ) : (
            <div className="p-12 text-center text-muted-foreground flex flex-col items-center gap-2">
              <Search className="w-8 h-8 opacity-20" />
              <p>{query.trim() ? '未找到相关股票' : '暂无数据'}</p>
              {query.trim() && <Button variant="link" size="sm" onClick={() => setQuery('')}>清除搜索条件</Button>}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
