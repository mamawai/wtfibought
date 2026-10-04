import { useEffect, useRef, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';
import { MARKETS } from '../lib/markets';

/**
 * 市场列表页顶上的四个市场切换（股票 / 币种 / 大宗 / TradFi），当前这个就是页面标题，底下压一道橙线；
 * sub 是标题下那行灰字说明。手机底部 Tab 的"市场"只有一格，四个市场靠这一排来回切；窄屏放不下就横滑
 */
export function MarketTabs({ sub }: { sub?: ReactNode }) {
  const { t } = useTranslation('layout');
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const onRef = useRef<HTMLButtonElement>(null);

  // 英文名长，窄屏上当前项可能在右边看不见，进页面先横向滚到它
  useEffect(() => { onRef.current?.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }, [pathname]);

  return (
    <div className="mb-2.5">
      <nav className="flex gap-5 overflow-x-auto -mx-5 px-5">
        {MARKETS.map(m => {
          const on = pathname === m.to;
          return (
            <button key={m.to} ref={on ? onRef : undefined} type="button" aria-current={on ? 'page' : undefined}
                    onClick={() => navigate(m.to)}
                    className={cn('relative shrink-0 pb-2 text-[18px] font-extrabold tracking-[-.01em] whitespace-nowrap cursor-pointer transition-colors',
                      on ? 'text-foreground' : 'text-muted-foreground hover:text-foreground')}>
              {t(m.labelKey)}
              {on && <span className="absolute left-0 right-0 bottom-0 h-[3px] bg-primary" />}
            </button>
          );
        })}
      </nav>
      {sub && <p className="mt-2 text-[13px] text-muted-foreground">{sub}</p>}
    </div>
  );
}
