import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * 空状态：一颗圆图标、一句话，要的话下面再放一个去处（children）。样式见 index.css .empty。
 *
 * i18n 约定（共享组件的样板）：text 收的是**已经翻译好的字符串**，不是 key。
 * 空态文案各页各说各的（"暂无持仓"/"该条件下暂无成交"…），归调用方业务域的词表；
 * 组件收 key 就得同时知道该查哪个 ns，等于把十来个域的命名空间全绑到这一个文件上。
 * 组件自带的兜底文案才归自己查，走 common。
 */
export function EmptyState({ icon, text, children }: { icon: ReactNode; text?: string; children?: ReactNode }) {
  const { t } = useTranslation();
  return (
    <div className="empty">
      <span className="ib0 [&>svg]:size-4">{icon}</span>
      <b>{text ?? t('noData')}</b>
      {children}
    </div>
  );
}
