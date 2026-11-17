import { Link } from 'react-router-dom';
import { Trans, useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';
import type { LlmEndpointView } from '../types';
import { Select } from './ui/select';

/**
 * 端点下拉：全站需要"选模型"的地方（交易员 / 复盘教练 / 对话用途绑定）都用它，
 * 选项来自 AI 页「模型配置」维护的端点库；value=null 表示"跟随默认端点"。
 * 铺满外框，宽度由 className 定；disabled 时整块淡下去点不动。
 * 端点库为空时不出下拉，给一条去配置的链接——没得选的下拉只会让人困惑。
 */
export function LlmEndpointSelect({
  endpoints, value, onChange, followLabel, className, disabled,
}: {
  endpoints: LlmEndpointView[];
  value: number | null;
  onChange: (id: number | null) => void;
  /** 空值选项的完整文案；不传=「跟随默认（默认端点名 · 模型）」 */
  followLabel?: string;
  className?: string;
  disabled?: boolean;
}) {
  const { t } = useTranslation('ai');
  if (endpoints.length === 0) {
    return (
      <span className={cn('text-[13px] mute', className)}>
        <Trans ns="ai" i18nKey="endpoint.selectEmpty" components={[<Link to="/ai" className="linkb" />]} />
      </span>
    );
  }
  const dft = endpoints.find(e => e.isDefault) ?? endpoints[0];
  return (
    <div className={cn(disabled && 'opacity-40', className)} inert={disabled}>
      <Select className="wide" value={value == null ? '' : String(value)} label={t('model.title')}
              onChange={v => onChange(v === '' ? null : Number(v))}
              options={[
                { value: '', label: followLabel ?? t('endpoint.followDefault', { name: dft.name, model: dft.model }) },
                ...endpoints.map(e => ({ value: String(e.id), label: `${e.name} · ${e.model}${e.isDefault ? t('model.viaDefault') : ''}` })),
              ]} />
    </div>
  );
}
