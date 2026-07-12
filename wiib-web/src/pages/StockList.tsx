import { useEffect, useMemo, useState } from 'react';
import { StockCardSkeleton } from '../components/StockCardSkeleton';
import { useNavigate } from 'react-router-dom';
import { stockApi } from '../api';
import { StockCard } from '../components/StockCard';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { useToast } from '../components/ui/use-toast';
import { List, ChevronLeft, ChevronRight, RefreshCcw, Search, X, ArrowUpDown } from 'lucide-react';
import type { Stock } from '../types';

type SortField = 'default' | 'price' | 'change';
type SortOrder = 'asc' | 'desc';

export function StockList() {
  const navigate = useNavigate();
  const { toast } = useToast();
  const [allStocks, setAllStocks] = useState<Stock[]>([]);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState('');
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [sortField, setSortField] = useState<SortField>('default');
  const [sortOrder, setSortOrder] = useState<SortOrder>('desc');
  
  const pageSize = 10;

  // loading 由"已加载 key 是否追上请求 key"派生，不在 effect 里同步 setState
  const requestKey = `stock-list:all:refresh=${refreshNonce}`;
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const loading = loadedKey !== requestKey;
  useEffect(() => {
      let cancelled = false;

      stockApi
        .list()
        .then((res: Stock[]) => {
          if (cancelled) return;
          setAllStocks(res);
        })
        .catch(() => {
          if (cancelled) return;
          setAllStocks([]);
          toast('获取股票列表失败', 'error', { description: '请稍后重试' });
        })
        .finally(() => {
          if (cancelled) return;
          setLoadedKey(requestKey);
        });

      return () => {
        cancelled = true;
      };
    }, [requestKey, toast]);

  const processedStocks = useMemo(() => {
    let result = [...allStocks];
    const q = query.trim().toLowerCase();

    // Filter
    if (q) {
      result = result.filter((s) => 
        s.name?.toLowerCase().includes(q) || 
        s.code?.toLowerCase().includes(q) ||
        s.industry?.toLowerCase().includes(q)
      );
    }

    // Sort
    if (sortField !== 'default') {
      result.sort((a, b) => {
        let valA = 0;
        let valB = 0;

        switch (sortField) {
          case 'price':
            valA = a.price ?? 0;
            valB = b.price ?? 0;
            break;
          case 'change':
            valA = a.changePct ?? 0;
            valB = b.changePct ?? 0;
            break;
        }

        return sortOrder === 'asc' ? valA - valB : valB - valA;
      });
    }

    return result;
  }, [allStocks, query, sortField, sortOrder]);

  // 筛选/排序变化时在 render 期回到第一页（React 文档 prev 比较模式）
  const filterKey = `${query}|${sortField}|${sortOrder}`;
  const [prevFilterKey, setPrevFilterKey] = useState(filterKey);
  if (prevFilterKey !== filterKey) {
    setPrevFilterKey(filterKey);
    setPage(1);
  }

  const total = processedStocks.length;
  const totalPages = Math.ceil(total / pageSize) || 1;
  const currentStocks = processedStocks.slice((page - 1) * pageSize, page * pageSize);

  const toggleSort = (field: SortField) => {
    if (sortField === field) {
      setSortOrder(prev => prev === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortOrder('desc');
    }
  };

  return (
    <div className="max-w-4xl mx-auto p-4 space-y-4">
      <Card>
        <CardHeader className="pb-3">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <CardTitle className="flex items-center gap-2 text-base">
              <div className="p-1.5 rounded-lg bg-primary/10">
                <List className="w-4 h-4 text-primary" />
              </div>
              全部股票
              <span className="text-xs text-muted-foreground font-normal ml-2">
                共 {allStocks.length} 支
              </span>
            </CardTitle>

            <div className="flex items-center gap-2">
              <div className="relative flex-1 sm:w-64">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
                <Input
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="搜索股票名称 / 代码"
                  className="pl-9 pr-9 h-9"
                />
                {query.trim() && (
                  <button
                    type="button"
                    onClick={() => setQuery('')}
                    className="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded-md hover:bg-accent transition-colors"
                    aria-label="清空搜索"
                  >
                    <X className="w-4 h-4 text-muted-foreground" />
                  </button>
                )}
              </div>
              
              <Button
                variant="outline"
                size="icon"
                className="h-9 w-9 shrink-0"
                onClick={() => {
                  setRefreshNonce((n) => n + 1);
                  toast('已刷新', 'info');
                }}
              >
                <RefreshCcw className="w-4 h-4" />
              </Button>
            </div>
          </div>

          <div className="relative mt-4">
            <div className="flex items-center gap-2 overflow-x-auto pb-2 sm:pb-0 snap-x snap-mandatory">
              <span className="text-sm text-muted-foreground whitespace-nowrap shrink-0">排序：</span>
              <Button
                variant={sortField === 'default' ? "secondary" : "ghost"}
                size="sm"
                className="h-8 text-xs shrink-0 snap-start"
                onClick={() => setSortField('default')}
              >
                默认
              </Button>
              <Button
                variant={sortField === 'price' ? "secondary" : "ghost"}
                size="sm"
                className="h-8 text-xs gap-1 shrink-0 snap-start"
                onClick={() => toggleSort('price')}
              >
                价格
                {sortField === 'price' && <ArrowUpDown className="w-3 h-3" />}
              </Button>
              <Button
                variant={sortField === 'change' ? "secondary" : "ghost"}
                size="sm"
                className="h-8 text-xs gap-1 shrink-0 snap-start"
                onClick={() => toggleSort('change')}
              >
                涨跌幅
                {sortField === 'change' && <ArrowUpDown className="w-3 h-3" />}
              </Button>
            </div>
            {/* Gradient hint for scrollable content on mobile */}
            <div className="absolute right-0 top-0 bottom-0 w-8 bg-linear-to-l from-card to-transparent pointer-events-none sm:hidden" />
          </div>
        </CardHeader>
        <CardContent className="p-0">
          {loading ? (
            <>
              {Array.from({ length: 5 }).map((_, i) => (
                <StockCardSkeleton key={i} />
              ))}
            </>
          ) : currentStocks.length > 0 ? (
            currentStocks.map((stock) => (
              <StockCard
                key={stock.id}
                stock={stock}
                onClick={() => navigate(`/stock/${stock.id}`)}
              />
            ))
          ) : (
            <div className="p-12 text-center text-muted-foreground flex flex-col items-center gap-2">
              <Search className="w-8 h-8 opacity-20" />
              <p>{query.trim() ? '未找到相关股票' : '暂无股票数据'}</p>
              {query.trim() && (
                <Button variant="link" size="sm" onClick={() => setQuery('')}>
                  清除搜索条件
                </Button>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex justify-center items-center gap-4 py-4">
          <Button
            variant="outline"
            size="sm"
            disabled={page <= 1}
            onClick={() => setPage(page - 1)}
          >
            <ChevronLeft className="w-4 h-4 mr-1" />
            上一页
          </Button>
          <span className="text-sm text-muted-foreground tabular-nums">
            {page} / {totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={page >= totalPages}
            onClick={() => setPage(page + 1)}
          >
            下一页
            <ChevronRight className="w-4 h-4 ml-1" />
          </Button>
        </div>
      )}
    </div>
  );
}
