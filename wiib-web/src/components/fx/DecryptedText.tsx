import { useEffect, useState } from 'react';

const CHARS = '!<>-_\\/[]{}=+*^?#0123456789';

/**
 * 乱码解密文字：随机字符逐字揭示成目标文案（密码机质感）。
 * 只在挂载/文案变化时播一次；reduced-motion 直接出终值。
 */
export function DecryptedText({ text, className, speed = 35 }: {
  text: string;
  className?: string;
  speed?: number;
}) {
  const [cells, setCells] = useState<{ ch: string; done: boolean }[]>(
    () => text.split('').map(ch => ({ ch, done: true })),
  );

  useEffect(() => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setCells(text.split('').map(ch => ({ ch, done: true })));
      return;
    }
    let frame = 0;
    let timer = 0;
    const tick = () => {
      const solved = frame / 3;
      setCells(text.split('').map((ch, i) =>
        i < solved || ch === ' '
          ? { ch, done: true }
          : { ch: CHARS[Math.floor(Math.random() * CHARS.length)], done: false },
      ));
      frame++;
      if (solved < text.length + 1) timer = window.setTimeout(tick, speed);
    };
    tick();
    return () => clearTimeout(timer);
  }, [text, speed]);

  return (
    <span className={className} aria-label={text}>
      {cells.map((c, i) => (
        <span key={i} className={c.done ? undefined : 'text-primary/70'}>{c.ch}</span>
      ))}
    </span>
  );
}
