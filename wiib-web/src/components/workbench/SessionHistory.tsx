import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, SquarePen, Trash2 } from 'lucide-react';
import { SureButton } from '../SureButton';
import { cn, DAY_MS } from '../../lib/utils';
import type { WorkbenchSessionSummary } from '../../types';

type Group = { key: string; items: WorkbenchSessionSummary[] };

/**
 * 按最后活跃时间分四组：今天 / 昨天 / 近 7 天 / 更早。
 * 界线用本机今天零点算（列表只做粗分组，不必跟站内那套新加坡时区读数对齐）；
 * 会话本身已由后端按倒序给出，这里只装桶不排序。
 */
function groupSessions(sessions: WorkbenchSessionSummary[]): Group[] {
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const groups: Group[] = [
    { key: 'groupToday', items: [] },
    { key: 'groupYesterday', items: [] },
    { key: 'groupWeek', items: [] },
    { key: 'groupOlder', items: [] },
  ];
  for (const s of sessions) {
    const at = s.lastAt;
    const bucket = at >= today ? 0 : at >= today - DAY_MS ? 1 : at >= today - 6 * DAY_MS ? 2 : 3;
    groups[bucket].items.push(s);
  }
  return groups.filter(g => g.items.length > 0);
}

/**
 * 历史会话（样式见 ChatDock.css .cside），两种形态共用同一份列表。
 * <p>
 * <b>叠层</b>（浮窗 / 手机）：盖在整个面板上的右滑一层，常驻挂载才有出入动画，关着时移出视野。
 * <p>
 * <b>侧栏</b>（PC 全屏）：钉在左边跟对话并排，没有"返回"这一步——开合靠宽度过渡，开关在面板顶栏。
 * <p>
 * 每行只有一行标题：时间与条数不上屏，分组标题已经交代了新旧。删除键平时藏着，
 * 悬停才露；触屏没有 hover，常显。删会话、清空都要再点一次才生效。
 */
export function SessionHistory({ sidebar = false, open, loading, sessions, currentId, onBack, onOpen, onArmRemove, onRemove, onNew, onClearAll }: {
  /** true=全屏时的左侧常驻栏；false=右滑叠层 */
  sidebar?: boolean;
  open: boolean;
  loading: boolean;
  sessions: WorkbenchSessionSummary[];
  currentId: string | null;
  onBack: () => void;
  onOpen: (s: WorkbenchSessionSummary) => void;
  /** 删除点第一下：说清删的是哪个 */
  onArmRemove: (s: WorkbenchSessionSummary) => void;
  onRemove: (s: WorkbenchSessionSummary) => void;
  onNew: () => void;
  onClearAll: () => void;
}) {
  const { t } = useTranslation('ai');
  const groups = useMemo(() => groupSessions(sessions), [sessions]);

  return (
    <aside className={cn('cside', sidebar && 'side', open && 'on')} inert={!open}>
      <div className="sh">
        <b>{t('history.title')}</b>
        {/* 侧栏里没有"返回"可言——它跟对话是并排的，收起交给面板顶栏那个开关 */}
        {!sidebar && <button type="button" className="linkb" onClick={onBack}>{t('history.back')}</button>}
      </div>
      <button type="button" className="btn new" onClick={onNew}><SquarePen className="ic" />{t('history.new')}</button>

      <div className="sl2">
        {loading ? (
          <div className="empty"><Loader2 className="ic animate-spin" />{t('history.loading')}</div>
        ) : sessions.length === 0 ? (
          <div className="empty"><b>{t('history.empty')}</b></div>
        ) : (
          groups.map(g => (
            <div key={g.key}>
              <h5>{t(`history.${g.key}`)}</h5>
              {g.items.map(s => (
                <div key={s.sessionId} className={cn('csess', s.sessionId === currentId && 'on')}>
                  <button type="button" className="op" onClick={() => onOpen(s)}>{s.title}</button>
                  <SureButton className="del" sureClassName="sure" title={t('history.delete')} ariaLabel={t('history.delete')}
                              sureLabel={<Trash2 className="ic" />} onArm={() => onArmRemove(s)} onConfirm={() => onRemove(s)}>
                    <Trash2 className="ic" />
                  </SureButton>
                </div>
              ))}
            </div>
          ))
        )}
      </div>

      {sessions.length > 0 && (
        <div className="ft">
          <SureButton className="linkb" sureClassName="sure" sureLabel={t('history.clearSure')} onConfirm={onClearAll}>
            {t('history.clearAll')}
          </SureButton>
        </div>
      )}
    </aside>
  );
}
