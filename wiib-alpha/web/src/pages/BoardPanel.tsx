import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Minus, RefreshCw, TrendingDown, TrendingUp } from 'lucide-react';
import { alpha, type Board, type StanceView } from '../api';
import { useFail } from '../toast';
import { Badge, Button, Card, Empty, Spinner, type Tone } from '../components/ui';

const STANCE: Record<StanceView['stance'], { tone: Tone; icon: typeof TrendingUp }> = {
  long: { tone: 'gain', icon: TrendingUp },
  short: { tone: 'loss', icon: TrendingDown },
  neutral: { tone: 'muted', icon: Minus },
};

/** 因子看板：库里在役因子对产品币当天的看法，访客和管理员都能看；访客只看到编号 */
export function BoardPanel({ active, admin }: { active: boolean; admin: boolean }) {
  const { t } = useTranslation();
  const fail = useFail();
  // undefined 是还没拿到，null 是还没算过
  const [board, setBoard] = useState<Board | null | undefined>(undefined);

  const load = useCallback(() => alpha.board().then(setBoard).catch(fail), [fail]);

  useEffect(() => {
    if (active) void load();
  }, [active, load]);

  return (
    <Card title={t('board.title')} hint={board ? t('board.hint', { day: board.day }) : undefined}
          actions={<Button variant="icon" icon={RefreshCw} aria-label={t('common.refresh')} onClick={() => void load()} />}>
      {board === undefined ? <Spinner /> : board === null ? <Empty>{t('board.none')}</Empty> : (
        <div className="space-y-3">
          <div className="tw">
            <table className="tbl">
              <thead>
                <tr>
                  <th>{t('board.factor')}</th>
                  {board.coins.map(c => <th key={c} className="whitespace-nowrap">{c.replace(/USDT$/, '')}</th>)}
                </tr>
              </thead>
              <tbody>
                <tr className="bg-fill">
                  <td className="whitespace-nowrap">
                    <div className="text-[13px] font-semibold">{t('board.combo')}</div>
                    <div className="text-[11.5px] text-muted">{t('board.comboSub')}</div>
                  </td>
                  {board.coins.map(c => <td key={c}><StanceCell v={board.combo[c]} /></td>)}
                </tr>
                {board.factors.map(f => (
                  <tr key={f.no}>
                    <td className="whitespace-nowrap">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="text-[13px] font-semibold">{t('board.factorNo', { no: f.no })}</span>
                        {!f.live && <Badge tone="warn">{t('board.notLive')}</Badge>}
                        {f.live && !f.inCombo && <Badge>{t('board.notInCombo')}</Badge>}
                      </div>
                      {admin && f.name && (
                        <code className="block max-w-[16rem] truncate text-[11.5px] text-muted" title={f.expr}>{f.name}</code>
                      )}
                    </td>
                    {board.coins.map(c => <td key={c}><StanceCell v={f.stances[c]} /></td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="max-w-[90ch] text-[12.5px] leading-relaxed text-muted">{t('board.legend')}</p>
        </div>
      )}
    </Card>
  );
}

/** 一格：看多 / 看空 / 中性，底下一行全池第几、共几个；没有看法是一道横线 */
function StanceCell({ v }: { v: StanceView | undefined }) {
  const { t } = useTranslation();
  if (!v) return <span className="text-muted">—</span>;
  const { tone, icon } = STANCE[v.stance];
  return (
    <div className="space-y-1">
      <Badge tone={tone} icon={icon}>{t(`board.stance.${v.stance}`)}</Badge>
      <div className="num text-[11.5px] text-muted whitespace-nowrap">{t('board.rank', { rank: v.rank, of: v.of })}</div>
    </div>
  );
}
