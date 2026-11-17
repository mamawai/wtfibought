import { Trans, useTranslation } from 'react-i18next';
import { ChevronLeft, ChevronRight } from 'lucide-react';

/** 翻页：‹ 第 x / y 页 ›，只有一页就不出（样式见 index.css .pgn） */
export function Pager({ page, pages, onChange }: { page: number; pages: number; onChange: (p: number) => void }) {
  const { t } = useTranslation('common');
  if (pages <= 1) return null;
  return (
    <div className="pgn">
      <button type="button" className="ib sm" aria-label={t('pager.prev')} disabled={page <= 1} onClick={() => onChange(page - 1)}>
        <ChevronLeft className="ic" />
      </button>
      <span><Trans t={t} i18nKey="pager.page" values={{ page, pages }} components={[<b key="p" />, <b key="n" />]} /></span>
      <button type="button" className="ib sm" aria-label={t('pager.next')} disabled={page >= pages} onClick={() => onChange(page + 1)}>
        <ChevronRight className="ic" />
      </button>
    </div>
  );
}
