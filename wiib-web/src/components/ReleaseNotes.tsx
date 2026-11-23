import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowUpRight, ChevronLeft, ChevronRight, Megaphone } from 'lucide-react';
import { Dialog, DialogContent, DialogHeader } from './ui/dialog';
import { currentLang } from '../i18n';
import { LATEST_VERSION, RELEASES, releaseUrl, type ReleaseItem } from '../lib/releases';
import { useHasUpdate, useReleaseStore } from '../stores/releaseStore';
import './ReleaseNotes.css';

/** 新增橙、优化蓝、修复灰 */
const KIND_TAG: Record<ReleaseItem['kind'], string> = { new: 'o', better: 'b', fix: '' };

/** 弹窗里能翻到的最近几版，再早的去 GitHub 看 */
const SHOWN = RELEASES.slice(0, 5);

/** 顶栏图标右边那颗（仓库入口）：图标关上弹窗就没了，焦点改还给它 */
let afterIcon: HTMLElement | null = null;

/** 顶栏更新图标：有没看过的新版本才出现，点开更新弹窗；看过就不显示 */
export function ReleaseButton() {
  const { t } = useTranslation('layout');
  const fresh = useHasUpdate();
  const openNotes = useReleaseStore(s => s.openNotes);
  if (!fresh) return null;
  return (
    <button type="button" className="upd" aria-label={t('release.title')}
            onClick={e => { afterIcon = e.currentTarget.nextElementSibling as HTMLElement | null; openNotes(); }}>
      <Megaphone className="ic" />
    </button>
  );
}

/** 页脚的版本号：点开更新弹窗，顶栏图标没了以后从这里还能再看 */
export function ReleaseVersion() {
  const { t } = useTranslation('layout');
  const openNotes = useReleaseStore(s => s.openNotes);
  return (
    <button type="button" className="linkb ver nf" onClick={openNotes} title={t('release.title')}>
      v{LATEST_VERSION}
    </button>
  );
}

/** 更新弹窗：一次只看一版，头上两颗小钮翻到更早、更新的版本，内容不往下加长；全站只挂一份 */
export function ReleaseDialog() {
  const { t } = useTranslation('layout');
  const open = useReleaseStore(s => s.open);
  const closeNotes = useReleaseStore(s => s.closeNotes);
  // 正在看第几版，0 是最新
  const [idx, setIdx] = useState(0);
  const lang = currentLang();
  const release = SHOWN[idx];

  const close = () => {
    closeNotes();
    setIdx(0);
  };

  // 翻到第 to 版；翻到头这颗钮会禁用，焦点先交回面板
  const step = (e: React.MouseEvent<HTMLButtonElement>, to: number) => {
    setIdx(to);
    if (to === 0 || to === SHOWN.length - 1) e.currentTarget.closest<HTMLElement>('[role="dialog"]')?.focus();
  };

  // 关上后焦点掉到页面上（顶栏图标没了），交给图标右边那颗
  useEffect(() => {
    if (!open && document.activeElement === document.body) afterIcon?.focus();
  }, [open]);

  return (
    <Dialog open={open} onClose={close}>
      <DialogHeader actions={
        <>
          <button type="button" className="xb" disabled={idx === SHOWN.length - 1} onClick={e => step(e, idx + 1)}
                  title={t('release.older')} aria-label={t('release.older')}>
            <ChevronLeft className="ic" />
          </button>
          <button type="button" className="xb" disabled={idx === 0} onClick={e => step(e, idx - 1)}
                  title={t('release.newer')} aria-label={t('release.newer')}>
            <ChevronRight className="ic" />
          </button>
        </>
      }>
        <h3>{t('release.title')}</h3>
        <p className="nf">v{release.version} · {release.date}</p>
      </DialogHeader>
      <DialogContent>
        {/* 几版叠在同一格里，只显当前这版：弹窗高度按最长那版定，翻版时头上的钮不跟着跳 */}
        <div className="rels">
          {SHOWN.map((r, i) => (
            <ul key={r.version} className="rel" aria-hidden={i !== idx}>
              {r.items.map(item => (
                <li key={item.title.zh}>
                  <span className={`tag sm ${KIND_TAG[item.kind]}`}>{t(`release.kind.${item.kind}`)}</span>
                  <div>
                    <b>{item.title[lang]}</b>
                    <p>{item.desc[lang]}</p>
                  </div>
                </li>
              ))}
            </ul>
          ))}
        </div>
      </DialogContent>
      <div className="df rel-f">
        <a className="go" href={releaseUrl(release.version)} target="_blank" rel="noopener noreferrer">
          {t('release.fullNotes')}<ArrowUpRight className="ic" />
        </a>
        <button type="button" className="btn ink" onClick={close}>{t('release.gotIt')}</button>
      </div>
    </Dialog>
  );
}
