import { useRef } from 'react';
import { cn } from '../lib/utils';
import { useStreamReveal } from '../hooks/useStreamReveal';
import { Markdown } from './Markdown';

/**
 * 流式的 markdown 正文（聊天回答、交易员的现场思考、指令生成里 agent 说的话共用）：
 * streaming 时字是攒够一段整段弹出来的，卡住了文末有呼吸点（见 useStreamReveal）；不在流式就是普通的 markdown。
 */
export function StreamText({ content, streaming, className }: {
  content: string;
  streaming: boolean;
  className?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const { text, chunks } = useStreamReveal(ref, content, streaming);
  return (
    <div ref={ref} className={cn('sx', className)}>
      <Markdown content={text} pop={chunks} />
    </div>
  );
}
