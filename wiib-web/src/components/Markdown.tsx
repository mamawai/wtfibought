import { memo, type ComponentProps } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkCjkFriendly from 'remark-cjk-friendly';

/**
 * LLM 输出的 markdown 统一渲染：工作台对话与竞技场决策日志共用。
 * 模型爱用 #/##/**——裸文本一屏井号没法看。
 * 这里不定正文字号，跟随调用方（对话答案是 text-sm，竞技场决策日志是 text-xs）；
 * 标题一律用 em 相对正文分档，最深一级与正文齐平——写死档位的话换个调用方就会冒出「标题比正文还小」。
 */

/* plugins / components 必须是模块级常量：写成内联字面量则每次渲染都是新引用，
   而 react-markdown 直接拿 components 里的函数当 JSX 元素 type，type 一换 React 就整段卸载重建而不是 diff。
   流式逐 token 时的表现就是正文选中被打断、长回答越往后越卡。 */
/* remarkCjkFriendly：`**加息的理由：**9月` 这种收尾 ** 前是全角标点、后紧跟字的，标准 markdown 不认加粗，它让这种也认 */
const REMARK_PLUGINS = [remarkGfm, remarkCjkFriendly];

/** react-markdown 给每个覆写组件都多塞一个 node（hast 节点），跟着 {...props} 摊进 DOM 会渲染成
 *  node="[object Object]"，所有覆写统一从这里过一道剥掉 */
function omitNode<P extends object>(props: P): Omit<P, 'node'> {
  const rest = { ...props };
  delete (rest as { node?: unknown }).node;
  return rest;
}

/* 覆写还有两条通用约定：
   1. className 一律写在 {...props} 之后。react-markdown 会把 hast 上的 class
      （language-xxx / task-list-item / contains-task-list）当普通 prop 传进来，
      写在前面会被它整条顶掉。
   2. 块级元素统一 last:mb-0：对话答案是无框裸正文，尾部多一截空白很显眼。 */

/** 标题公用：短句居多，行高收紧一档；首个标题不留上边距 */
const HEADING = 'leading-snug break-words first:mt-0';

/** h4 及更深：字号已经压到正文底线，再往下砸就比正文还小，改用字重 + 字距 + 灰度继续分层 */
function MinorHeading(props: ComponentProps<'h6'> & { node?: unknown }) {
  return <h6 {...omitNode(props)} className={`${HEADING} text-[1em] font-bold tracking-wide text-muted-foreground mt-2.5 mb-1`} />;
}

const COMPONENTS: Components = {
  p: (props) => <p {...omitNode(props)} className="leading-relaxed break-words mb-1.5 last:mb-0" />,
  /* 标题一律降级到 h4~h6 标签：这是页面局部的内容，不该往文档大纲里塞 h1。
     视觉上 base → sm → xs(=正文) 三级递减，到底后由 MinorHeading 接力 */
  h1: (props) => <h4 {...omitNode(props)} className={`${HEADING} text-[1.2em] font-black mt-4 mb-1.5`} />,
  h2: (props) => <h4 {...omitNode(props)} className={`${HEADING} text-[1.08em] font-black mt-3.5 mb-1.5`} />,
  h3: (props) => <h5 {...omitNode(props)} className={`${HEADING} text-[1em] font-black mt-3 mb-1`} />,
  h4: MinorHeading,
  h5: MinorHeading,
  h6: MinorHeading,
  /* 正文加粗压在 bold(700)，标题吃 black：不拉开的话满屏 ** 会和小标题糊成一片 */
  strong: (props) => <strong {...omitNode(props)} className="font-bold" />,
  ul: (props) => <ul {...omitNode(props)} className="list-disc pl-4 space-y-0.5 mb-1.5 last:mb-0" />,
  ol: (props) => <ol {...omitNode(props)} className="list-decimal pl-4 space-y-0.5 mb-1.5 last:mb-0" />,
  /* task-list-item 是 remark-gfm 给 `- [ ]` 打的标：这类条目的记号是复选框，再挂圆点就成了双记号。
     混排列表里普通条目照常出圆点，所以关记号只能落在 li 上，不能整个 ul 关掉 */
  li: (props) => (
    <li {...omitNode(props)} className={props.className?.includes('task-list-item') ? 'break-words list-none' : 'break-words'} />
  ),
  /* markdown 里只有任务列表复选框会走到 input。模型输出是只读结论，不能变成可点的控件 */
  input: (props) => <input {...omitNode(props)} disabled className="mr-1.5 align-middle accent-primary" />,
  blockquote: (props) => (
    <blockquote {...omitNode(props)} className="border-l-2 border-border pl-2.5 text-muted-foreground mb-1.5 last:mb-0" />
  ),
  hr: () => <hr className="border-border my-2" />,
  /* 行内 code 的底色和内边距落到围栏块里就成了 pre 底色上再叠一层：
     由 pre 用 [&>code] 把子层压平——缩进式代码块没有 language-xxx 类，靠类名认块会漏，选择器不会 */
  code: (props) => (
    <code {...omitNode(props)} className="px-1 py-0.5 rounded-sm bg-muted text-[11px] font-mono break-words" />
  ),
  pre: (props) => (
    <pre {...omitNode(props)} className="p-2.5 rounded-lg bg-muted text-[11px] font-mono overflow-x-auto mb-1.5 last:mb-0 [&>code]:bg-transparent [&>code]:p-0" />
  ),
  table: (props) => (
    <div className="overflow-x-auto mb-1.5 last:mb-0">
      <table {...omitNode(props)} className="text-[11px] border-collapse [&_th]:border [&_th]:px-2 [&_th]:py-1 [&_td]:border [&_td]:px-2 [&_td]:py-1" />
    </div>
  ),
  /* 只有真外链才新开标签页：脚注和回跳是页内锚点 #user-content-fn-*，被 target=_blank 接走会开出一个空白页 */
  a: (props) => {
    const external = props.href?.startsWith('http') ?? false;
    return (
      <a
        {...omitNode(props)}
        target={external ? '_blank' : undefined}
        rel={external ? 'noopener noreferrer' : undefined}
        className="text-primary underline break-words"
      />
    );
  },
};

/* memo：面板里同屏挂着多条消息，任一条流式刷新都会带动整个列表重渲染，
   content 没变的那几条没必要把 markdown 重新解析一遍 */
export const Markdown = memo(function Markdown({ content }: { content: string }) {
  return (
    <ReactMarkdown remarkPlugins={REMARK_PLUGINS} components={COMPONENTS}>
      {content}
    </ReactMarkdown>
  );
});
