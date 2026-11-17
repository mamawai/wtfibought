import { cn } from '../lib/utils';

/** 头像：有图用图，没有就一个圆里放名字首字母（样式见 index.css .ava） */
export function Avatar({ name, src, small, className }: { name: string; src?: string; small?: boolean; className?: string }) {
  const cls = cn('ava', small && 'sm', className);
  return src
    ? <img className={cn(cls, 'object-cover')} src={src} alt="" referrerPolicy="no-referrer" />
    : <span className={cls} aria-hidden>{name.charAt(0).toUpperCase()}</span>;
}
