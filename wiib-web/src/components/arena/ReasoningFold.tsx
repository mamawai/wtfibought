import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Markdown } from '../Markdown';

/** 结论分段标记 [BTCUSDT]（独占一行），与后端 ReviewMaterialAssembler.SEGMENT_TAG 同一形状 */
const SEG_LINE = /^\s*\[([A-Z0-9]{2,20})\]\s*$/;
/** 超过这么多字才收起来、给「展开全文」 */
const LONG_CHARS = 84;

interface Block {
  symbol: string | null;
  body: string;
}

/** 按分段标记行切块：标记前的引子（行情铺垫/总评）归 symbol=null 块；无标记时整篇一块 */
function splitBlocks(text: string): Block[] {
  const blocks: Block[] = [];
  let symbol: string | null = null;
  let buf: string[] = [];
  for (const line of text.split('\n')) {
    const m = SEG_LINE.exec(line);
    if (m) {
      if (buf.join('').trim()) blocks.push({ symbol, body: buf.join('\n') });
      symbol = m[1];
      buf = [];
    } else {
      buf.push(line);
    }
  }
  if (buf.join('').trim()) blocks.push({ symbol, body: buf.join('\n') });
  return blocks;
}

/**
 * 决策推理（样式见 ArenaDetail.css .rs）：长的默认收起，只给「展开全文」，点开是 markdown；短的直接铺。
 * 带币种分段标记的按币分块，每块前面一枚币码。展开/收起和「过程」并排在底下一行链接里
 */
export function ReasoningFold({ reasoning, children }: {
  reasoning: string | null;
  /** 挂在展开/收起旁边的链接（决策卡的「过程」） */
  children?: ReactNode;
}) {
  const { t } = useTranslation(['ai', 'common']);
  const [open, setOpen] = useState(false);
  const text = reasoning?.trim() || '';
  if (!text && !children) return null;
  const long = text.length > LONG_CHARS;
  const blocks = splitBlocks(text);
  return (
    <>
      {text && (!long || open) && (
        <div className="rs">
          {blocks.some(b => b.symbol != null) ? blocks.map((b, i) => (
            <div key={i} className="part">
              {b.symbol != null && <span className="tag sm">{b.symbol}</span>}
              <Markdown content={b.body} />
            </div>
          )) : <Markdown content={text} />}
        </div>
      )}
      {(long || children) && (
        <div className="lk">
          {long && (
            <button type="button" className="linkb" onClick={() => setOpen(!open)}>
              {open ? t('common:collapse') : t('detail.expandFull')}
            </button>
          )}
          {children}
        </div>
      )}
    </>
  );
}
