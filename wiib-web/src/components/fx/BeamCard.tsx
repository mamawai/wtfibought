import { cn } from '../../lib/utils';

/**
 * 边框巡游光卡片：active 时一束橙光沿边框环形巡游（"进行中"状态标注），
 * 关闭时退化为普通边框卡。纯 CSS 实现（index.css .wiib-beam），零依赖。
 */
export function BeamCard({ active = true, className, children }: {
  active?: boolean;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div className={cn('relative rounded-lg overflow-hidden', !active && 'border border-border bg-card', className)}>
      {active && (
        <>
          <span aria-hidden className="absolute -inset-[40%] wiib-beam" />
          <span aria-hidden className="absolute inset-px rounded-[7px] bg-card" />
        </>
      )}
      <div className="relative">{children}</div>
    </div>
  );
}
