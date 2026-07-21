import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  ArrowLeft, ChevronDown, CornerDownRight, Loader2, MessageSquare, Pencil,
  RefreshCcw, Send, ShieldAlert, ThumbsDown, ThumbsUp, Trash2, VolumeX,
} from 'lucide-react';
import { commentApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useToast } from '../components/ui/use-toast';
import { Button } from '../components/ui/button';
import { Dialog, DialogHeader, DialogContent, DialogFooter } from '../components/ui/dialog';
import { cn, fmtDateTime } from '../lib/utils';
import type { CommentItem } from '../types';

const ADMIN_USER_ID = 1;
const MAX_LEN = 500;
const ROOT_PAGE_SIZE = 20;
const CHILD_PAGE_SIZE = 10;
const ISSUE_URL = 'https://github.com/mamawai/wtfibought/issues/new';

const MUTE_OPTIONS = [
  { days: 1, label: '禁言 1 天' },
  { days: 7, label: '禁言 7 天' },
  { days: -1, label: '永久禁言' },
];

/** 正在回复谁：rootId 恒指向根评论（回复子评论也是），toUserId 决定"回复 @xxx"挂在谁身上 */
type ReplyTarget = { rootId: number; toUserId: number; toUsername: string };

/**
 * 自删后顶替原文的占位文案，须与后端 CommentService.DELETED_PLACEHOLDER 一致。
 * 这里只用于删除后的乐观更新，刷新页面拿到的仍以后端为准
 */
const DELETED_PLACEHOLDER = '该留言已删除，无法查看';

/** 就地改一条评论（根或子），赞踩后不必整页重拉。结构固定两层，不做递归 */
function patchComment(roots: CommentItem[], id: number, fn: (c: CommentItem) => CommentItem): CommentItem[] {
  return roots.map(r => {
    if (r.id === id) return fn(r);
    if (!r.children?.some(c => c.id === id)) return r;
    return { ...r, children: r.children.map(c => (c.id === id ? fn(c) : c)) };
  });
}

/** 删除后从树里摘掉：根评论整组消失（后端级联软删子评论），子评论顺带把父的 childCount 减 1 */
function dropComment(roots: CommentItem[], id: number): CommentItem[] {
  return roots
    .filter(r => r.id !== id)
    .map(r => (r.children?.some(c => c.id === id)
      ? { ...r, children: r.children.filter(c => c.id !== id), childCount: Math.max(0, r.childCount - 1) }
      : r));
}

/** 头像：有图用图，没图退化成用户名首字圆盘 */
function Avatar({ username, avatar, small }: { username: string; avatar?: string; small?: boolean }) {
  const dim = small ? 'w-7 h-7' : 'w-9 h-9';
  if (avatar) {
    return <img src={avatar} alt="" className={cn(dim, 'rounded-full object-cover shrink-0 neu-flat')} />;
  }
  return (
    <div className={cn(dim, 'rounded-full shrink-0 neu-flat bg-primary/10 text-primary flex items-center justify-center font-black',
      small ? 'text-[10px]' : 'text-xs')}>
      {username.slice(0, 1).toUpperCase()}
    </div>
  );
}

/** 评论行上的小动作按钮：拟物小凸起；禁用时压暗且不再变色 */
function ActionButton({ disabled, title, onClick, hoverClass, children }: {
  disabled?: boolean;
  title?: string;
  onClick: () => void;
  hoverClass?: string;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      title={title}
      onClick={onClick}
      className={cn(
        'neu-btn-sm px-2 py-1 rounded-lg text-[11px] font-bold tabular-nums flex items-center gap-1 transition-colors text-muted-foreground',
        disabled ? 'opacity-45 cursor-not-allowed' : cn('cursor-pointer', hoverClass),
      )}
    >
      {children}
    </button>
  );
}

/** 发帖、回复、编辑三处共用的输入框。ready=false 是 user 还没拉回来，先禁用免得拿不到自己的 id */
function ComposeBox({ value, onChange, onSubmit, submitting, ready, placeholder, rows = 3, onCancel, submitLabel = '发布' }: {
  value: string;
  onChange: (v: string) => void;
  onSubmit: () => void;
  submitting: boolean;
  ready: boolean;
  placeholder: string;
  rows?: number;
  onCancel?: () => void;
  submitLabel?: string;
}) {
  const over = value.length > MAX_LEN;
  return (
    <div className="space-y-2">
      <textarea
        value={value}
        rows={rows}
        disabled={!ready || submitting}
        placeholder={placeholder}
        onChange={e => onChange(e.target.value)}
        className={cn(
          'w-full rounded-xl bg-background px-3.5 py-2.5 text-sm neu-inset resize-none',
          'placeholder:text-muted-foreground transition-all duration-150',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40',
          'disabled:cursor-not-allowed disabled:opacity-50',
        )}
      />
      <div className="flex items-center gap-2">
        <span className={cn('text-[11px] tabular-nums mr-auto', over ? 'text-destructive' : 'text-muted-foreground')}>
          {value.length} / {MAX_LEN}
        </span>
        {onCancel && <Button variant="ghost" size="sm" onClick={onCancel}>取消</Button>}
        <Button size="sm" disabled={!ready || submitting || !value.trim() || over} onClick={onSubmit}>
          {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
          {submitLabel}
        </Button>
      </div>
    </div>
  );
}

/** 单条评论：头像 + 昵称/回复对象 + 正文 + 赞踩/回复/编辑/管理动作 */
function CommentRow({ c, isChild, focused, currentUserId, isAdmin, busy, onVote, onReply, onEdit, onDelete, onMute }: {
  c: CommentItem;
  isChild?: boolean;
  focused?: boolean;
  currentUserId: number | null;
  isAdmin: boolean;
  busy: boolean;
  onVote: (c: CommentItem, type: 'like' | 'dislike') => void;
  onReply: (c: CommentItem) => void;
  /** 失败时抛出，本行据此决定不关编辑框；提示由父层统一弹 */
  onEdit: (c: CommentItem, content: string) => Promise<void>;
  onDelete: (c: CommentItem) => void;
  onMute: (c: CommentItem) => void;
}) {
  // 编辑态就地自管：提上去要往每一行传 4 个 props，而它跟别的行毫无关系
  const [editing, setEditing] = useState(false);
  const [editText, setEditText] = useState('');
  const [saving, setSaving] = useState(false);

  const ready = currentUserId != null;
  const isMine = currentUserId === c.userId;
  const canEdit = isMine && !c.selfDeleted;
  // 自己的占位符没什么可再删的，但管理员对别人的占位符照样要能真删——
  // 自删只换掉正文，底下挂的一串回复还在，管理员得有办法整组清掉
  const canDelete = isMine ? !c.selfDeleted : isAdmin;
  // voted 一置就同时锁死赞和踩：一人对一条评论只有一次表态机会
  const voteTitle = c.voted ? '你已经表过态了' : undefined;

  const startEdit = () => {
    setEditText(c.content);   // 每次打开都取当下正文，免得留着上一轮的草稿
    setEditing(true);
  };

  const submitEdit = async () => {
    setSaving(true);
    try {
      await onEdit(c, editText.trim());
      setEditing(false);
    } catch {
      // 父层已经弹过提示，这里保持编辑框开着，内容不丢
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className={cn('flex gap-2.5', focused && 'rounded-xl neu-inset px-3 py-2.5')}>
      <Avatar username={c.username} avatar={c.avatar} small={isChild} />
      <div className="flex-1 min-w-0 space-y-1">
        <div className="flex items-baseline gap-2">
          <span className="text-xs font-black truncate">{c.username}</span>
          {c.replyToUsername && (
            <span className="text-[11px] text-muted-foreground shrink-0">回复 @{c.replyToUsername}</span>
          )}
          {c.updatedAt && !c.selfDeleted && (
            <span className="text-[10px] text-muted-foreground shrink-0">已编辑</span>
          )}
          <span className="ml-auto text-[10px] text-muted-foreground shrink-0">{fmtDateTime(c.createdAt)}</span>
        </div>

        {editing ? (
          <ComposeBox
            value={editText}
            onChange={setEditText}
            onSubmit={() => void submitEdit()}
            submitting={saving}
            ready={ready}
            placeholder="改点什么…"
            rows={2}
            submitLabel="保存"
            onCancel={() => setEditing(false)}
          />
        ) : (
          <p className={cn(
            'text-[13px] leading-relaxed whitespace-pre-wrap break-words',
            c.selfDeleted && 'italic text-muted-foreground',
          )}>
            {c.content}
          </p>
        )}

        {/* 编辑时收起动作区：边改边点赞是个没意义的中间态 */}
        {!editing && (
          <div className="flex items-center gap-1.5 flex-wrap pt-0.5">
            <ActionButton
              disabled={!ready || c.voted || busy}
              title={voteTitle}
              hoverClass="hover:text-gain"
              onClick={() => onVote(c, 'like')}
            >
              <ThumbsUp className="w-3 h-3" /> {c.likeCount}
            </ActionButton>
            <ActionButton
              disabled={!ready || c.voted || busy}
              title={voteTitle}
              hoverClass="hover:text-loss"
              onClick={() => onVote(c, 'dislike')}
            >
              <ThumbsDown className="w-3 h-3" /> {c.dislikeCount}
            </ActionButton>
            <ActionButton
              disabled={!ready}
              hoverClass="hover:text-primary"
              onClick={() => onReply(c)}
            >
              <CornerDownRight className="w-3 h-3" /> 回复
            </ActionButton>

            {canEdit && (
              <ActionButton hoverClass="hover:text-primary" onClick={startEdit}>
                <Pencil className="w-3 h-3" /> 编辑
              </ActionButton>
            )}
            {canDelete && (
              <ActionButton hoverClass="hover:text-destructive" onClick={() => onDelete(c)}>
                <Trash2 className="w-3 h-3" /> 删除
              </ActionButton>
            )}
            {isAdmin && !isMine && (
              <ActionButton hoverClass="hover:text-destructive" onClick={() => onMute(c)}>
                <VolumeX className="w-3 h-3" /> 禁言
              </ActionButton>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * 全站留言板。根评论 + 子评论两层，赞踩一人一次，管理员可删可禁言。
 * URL 带 ?focus={commentId} 时切成聚焦视图：一次 context 查询直接命中那条评论所在的整串，
 * 不用去算"它在第几页第几条"（通知跳转就走这条路）。
 */
export function Comments() {
  const { toast } = useToast();
  const [params, setParams] = useSearchParams();
  const user = useUserStore(s => s.user);

  const currentUserId = user?.id ?? null;
  const isAdmin = currentUserId === ADMIN_USER_ID;
  // 路由已挡住未登录，这里为 null 只可能是 fetchUser 还没回来
  const ready = currentUserId != null;
  const focusId = params.get('focus');

  const [roots, setRoots] = useState<CommentItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  // 已展开的根评论 → 子评论加载到第几页。子评论本身只挂在 root.children 上，
  // 只有一处持有数据，赞踩/删除的就地更新才不用同时维护两份
  const [childPage, setChildPage] = useState<Record<number, number>>({});
  // 正在展开哪几条根评论。早先是单个 number：A 请求飞行中去点 B，A 的按钮就被解禁了，
  // 再点一次 A 会用同一个页码再拉一遍 → 子评论重复 → rest 变负 → 按钮消失 → 卡死
  const [expanding, setExpanding] = useState<Set<number>>(new Set());
  // 丢弃过期响应用。focus 参数变化和刷新按钮都会触发 load，慢的那个后返回会覆盖新结果
  const loadSeq = useRef(0);

  const [text, setText] = useState('');
  // 发主楼和发回复各一个 in-flight 标志。早先共用一个 posting，再靠 `posting && !replyTo`
  // 反推是谁在提交——只要回复框开着，主发帖框就永远算不出"正在提交"，双击能发两条
  const [postingRoot, setPostingRoot] = useState(false);
  const [postingReply, setPostingReply] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [replyTo, setReplyTo] = useState<ReplyTarget | null>(null);
  const [replyText, setReplyText] = useState('');
  const [votingId, setVotingId] = useState<number | null>(null);
  const [muteTarget, setMuteTarget] = useState<CommentItem | null>(null);
  const [muting, setMuting] = useState(false);

  const load = useCallback(async () => {
    const seq = ++loadSeq.current;
    setLoading(true);
    try {
      if (focusId) {
        const root = await commentApi.context(Number(focusId));
        if (seq !== loadSeq.current) return;   // 期间又发起了新的加载，这份结果已过期
        setRoots([root]);
        setHasMore(false);
      } else {
        const list = await commentApi.list(1, ROOT_PAGE_SIZE);
        if (seq !== loadSeq.current) return;
        setRoots(list);
        setHasMore(list.length === ROOT_PAGE_SIZE);
      }
      setPage(1);
      setChildPage({});
      setReplyTo(null);
    } catch (e) {
      if (seq === loadSeq.current) toast((e as Error).message || '加载留言失败', 'error');
    } finally {
      if (seq === loadSeq.current) setLoading(false);
    }
  }, [focusId, toast]);

  useEffect(() => { void load(); }, [load]);

  const loadMoreRoots = async () => {
    if (loadingMore) return;   // 页码要等响应回来才推进，不挡住的话双击会把同一页追加两遍
    const next = page + 1;
    setLoadingMore(true);
    try {
      const list = await commentApi.list(next, ROOT_PAGE_SIZE);
      setRoots(prev => [...prev, ...list]);
      setPage(next);
      setHasMore(list.length === ROOT_PAGE_SIZE);
    } catch (e) {
      toast((e as Error).message || '加载失败', 'error');
    } finally {
      setLoadingMore(false);
    }
  };

  /** 展开或继续翻子评论。第 1 页直接顶掉列表里那 2 条预览，之后追加 */
  const loadChildren = async (root: CommentItem) => {
    if (expanding.has(root.id)) return;
    const next = (childPage[root.id] ?? 0) + 1;
    setExpanding(prev => new Set(prev).add(root.id));
    try {
      const list = await commentApi.children(root.id, next, CHILD_PAGE_SIZE);
      setRoots(prev => patchComment(prev, root.id, c => ({
        ...c,
        children: next === 1 ? list : [...(c.children ?? []), ...list],
      })));
      setChildPage(prev => ({ ...prev, [root.id]: next }));
    } catch (e) {
      toast((e as Error).message || '加载回复失败', 'error');
    } finally {
      setExpanding(prev => {
        const s = new Set(prev);
        s.delete(root.id);
        return s;
      });
    }
  };

  const submitRoot = async () => {
    if (postingRoot) return;
    setPostingRoot(true);
    try {
      await commentApi.post(text.trim());
      setText('');
      toast('留言已发布', 'success');
      // 聚焦视图下新根评论不属于当前这串，清掉 focus 退回全部列表才看得到（清参数会触发重载）
      if (focusId) setParams({}, { replace: true });
      else await load();
    } catch (e) {
      toast((e as Error).message || '发布失败', 'error');
    } finally {
      setPostingRoot(false);
    }
  };

  const submitReply = async () => {
    if (!replyTo || postingReply) return;
    const rootId = replyTo.rootId;
    setPostingReply(true);
    try {
      await commentApi.post(replyText.trim(), rootId, replyTo.toUserId);
    } catch (e) {
      toast((e as Error).message || '回复失败', 'error');
      setPostingReply(false);
      return;
    }

    // 到这儿回复已经入库了。刷新那步必须单独 try——早先它跟发布共用一个，
    // 刷新一失败就在"回复已发布"后面再弹一个"回复失败"，用户以为没发出去会再发一遍
    setReplyText('');
    setReplyTo(null);
    toast('回复已发布', 'success');
    try {
      // 用 context 把整串重拉：一次拿到根+全部子评论，比按页往回拼稳，也保证看得见自己刚发的
      const fresh = await commentApi.context(rootId);
      setRoots(prev => prev.map(r => (r.id === rootId ? fresh : r)));
    } catch {
      // 回复本身是成功的，只是没刷出来，提示一下让用户自己刷新，别说成"失败"
      toast('回复已发布，下拉刷新可看到', 'success');
    } finally {
      setPostingReply(false);
    }
  };

  const handleVote = async (c: CommentItem, type: 'like' | 'dislike') => {
    setVotingId(c.id);
    try {
      await commentApi.vote(c.id, type);
      setRoots(prev => patchComment(prev, c.id, x => ({
        ...x,
        likeCount: x.likeCount + (type === 'like' ? 1 : 0),
        dislikeCount: x.dislikeCount + (type === 'dislike' ? 1 : 0),
        voted: true,
      })));
    } catch (e) {
      toast((e as Error).message || '操作失败', 'error');
    } finally {
      setVotingId(null);
    }
  };

  /** 编辑成功后就地换正文并打上"已编辑"，不整页重拉。失败往上抛，由 CommentRow 决定不关编辑框 */
  const handleEdit = async (c: CommentItem, content: string) => {
    try {
      await commentApi.edit(c.id, content);
    } catch (e) {
      toast((e as Error).message || '保存失败', 'error');
      throw e;
    }
    setRoots(prev => patchComment(prev, c.id, x => ({
      ...x,
      content,
      updatedAt: new Date().toISOString(),
    })));
    toast('已保存', 'success');
  };

  /**
   * 删除的界面表现跟后端分流一致：删自己的是把正文换成占位文案，评论留在原位；
   * 管理员删别人的才真的从树里摘掉（根评论会连整组子评论一起消失）
   */
  const handleDelete = async (c: CommentItem) => {
    const isMine = c.userId === currentUserId;
    try {
      await commentApi.remove(c.id);
      setRoots(prev => isMine
        ? patchComment(prev, c.id, x => ({ ...x, content: DELETED_PLACEHOLDER, selfDeleted: true }))
        : dropComment(prev, c.id));
      toast('已删除', 'success');
    } catch (e) {
      toast((e as Error).message || '删除失败', 'error');
    }
  };

  const handleMute = async (days: number) => {
    if (!muteTarget) return;
    setMuting(true);
    try {
      await commentApi.mute(muteTarget.userId, days);
      toast(days === -1 ? `已永久禁言 ${muteTarget.username}` : `已禁言 ${muteTarget.username} ${days} 天`, 'success');
      setMuteTarget(null);
    } catch (e) {
      toast((e as Error).message || '禁言失败', 'error');
    } finally {
      setMuting(false);
    }
  };

  const startReply = (c: CommentItem) => {
    // 回复子评论时 rootId 仍取它的根：结构只有两层，永不出现三层嵌套
    setReplyTo({ rootId: c.rootId ?? c.id, toUserId: c.userId, toUsername: c.username });
    setReplyText('');
  };

  return (
    <div className="max-w-4xl mx-auto px-4 py-6 space-y-4">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-xl neu-raised-sm flex items-center justify-center bg-primary/10 shrink-0">
          <MessageSquare className="w-5.5 h-5.5 text-primary" />
        </div>
        <div className="min-w-0">
          <h1 className="text-xl font-black tracking-tight">留言板</h1>
          <p className="text-[11px] text-muted-foreground">评价 · 建议 · 涨跌看法</p>
        </div>
        <button
          onClick={() => { void load(); }}
          className="ml-auto neu-btn-sm w-9 h-9 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary cursor-pointer"
          aria-label="刷新"
        >
          <RefreshCcw className={cn('w-4 h-4', loading && 'animate-spin')} />
        </button>
      </div>

      {/* 留言规范：默认折叠，不占地方但随手能看 */}
      <details className="group rounded-2xl bg-card neu-raised px-4 py-3">
        <summary className="flex items-center gap-2 cursor-pointer text-sm font-bold list-none [&::-webkit-details-marker]:hidden">
          <ShieldAlert className="w-4 h-4 text-primary" />
          留言规范
          <ChevronDown className="w-4 h-4 ml-auto text-muted-foreground transition-transform group-open:rotate-180" />
        </summary>
        <div className="mt-3 pt-3 border-t border-border/50 space-y-2 text-xs leading-relaxed text-muted-foreground">
          <p>欢迎发表对本项目的评价、建议和修改意见，也可以聊聊涨跌看法，畅所欲言。</p>
          <p>
            有具体的功能建议或 bug，欢迎直接提 issue：
            <a href={ISSUE_URL} target="_blank" rel="noreferrer" className="text-primary font-bold underline underline-offset-2 ml-1">
              提交 issue
            </a>
          </p>
          <p className="text-destructive font-bold">
            禁止辱骂攻击性言论、带单信息、QQ/微信群等引流信息，违者禁言封号。
          </p>
        </div>
      </details>

      {/* 发帖框 */}
      <div className="rounded-2xl bg-card neu-raised p-4">
        <ComposeBox
          value={text}
          onChange={setText}
          onSubmit={() => void submitRoot()}
          submitting={postingRoot}
          ready={ready}
          placeholder="说点什么…"
        />
      </div>

      {/* 聚焦视图提示条 */}
      {focusId && (
        <button
          onClick={() => setParams({}, { replace: true })}
          className="flex items-center gap-2 text-xs font-bold text-primary neu-btn-sm rounded-xl px-3 py-2 cursor-pointer"
        >
          <ArrowLeft className="w-3.5 h-3.5" />
          返回全部评论
        </button>
      )}

      {/* 列表 */}
      {loading && roots.length === 0 ? (
        <div className="flex items-center justify-center py-16 gap-2 text-sm text-muted-foreground">
          <Loader2 className="w-4 h-4 animate-spin" /> 加载留言…
        </div>
      ) : roots.length === 0 ? (
        <div className="rounded-2xl neu-inset flex flex-col items-center justify-center gap-2.5 py-14 px-4 text-center">
          <div className="w-11 h-11 rounded-full neu-flat bg-background flex items-center justify-center text-muted-foreground/70">
            <MessageSquare className="w-5 h-5" />
          </div>
          <div className="text-xs font-bold text-muted-foreground">还没有人留言</div>
          <div className="text-[10px] text-muted-foreground/70">来说第一句吧</div>
        </div>
      ) : (
        <div className="space-y-3">
          {roots.map(root => {
            const children = root.children ?? [];
            const started = childPage[root.id] !== undefined;
            const rest = root.childCount - children.length;
            const replying = replyTo?.rootId === root.id;

            return (
              <div key={root.id} className="rounded-2xl bg-card neu-raised p-4 space-y-3">
                <CommentRow
                  c={root}
                  focused={focusId === String(root.id)}
                  currentUserId={currentUserId}
                  isAdmin={isAdmin}
                  busy={votingId === root.id}
                  onVote={(c, t) => void handleVote(c, t)}
                  onReply={startReply}
                  onEdit={handleEdit}
                  onDelete={c => void handleDelete(c)}
                  onMute={setMuteTarget}
                />

                {children.length > 0 && (
                  <div className="pl-4 ml-1 border-l border-border/60 space-y-3">
                    {children.map(child => (
                      <CommentRow
                        key={child.id}
                        c={child}
                        isChild
                        focused={focusId === String(child.id)}
                        currentUserId={currentUserId}
                        isAdmin={isAdmin}
                        busy={votingId === child.id}
                        onVote={(c, t) => void handleVote(c, t)}
                        onReply={startReply}
                        onEdit={handleEdit}
                        onDelete={c => void handleDelete(c)}
                        onMute={setMuteTarget}
                      />
                    ))}
                  </div>
                )}

                {rest > 0 && (
                  <button
                    disabled={expanding.has(root.id)}
                    onClick={() => void loadChildren(root)}
                    className="ml-5 text-[11px] font-bold text-primary hover:underline disabled:opacity-50 cursor-pointer flex items-center gap-1"
                  >
                    {expanding.has(root.id) && <Loader2 className="w-3 h-3 animate-spin" />}
                    {started ? `加载更多回复（还有 ${rest} 条）` : `查看全部 ${root.childCount} 条回复`}
                  </button>
                )}

                {replying && (
                  <div className="pl-4 ml-1 border-l border-primary/40">
                    <div className="text-[11px] font-bold text-muted-foreground mb-1.5">
                      回复 @{replyTo.toUsername}
                    </div>
                    <ComposeBox
                      value={replyText}
                      onChange={setReplyText}
                      onSubmit={() => void submitReply()}
                      submitting={postingReply}
                      ready={ready}
                      placeholder="写下你的回复…"
                      rows={2}
                      onCancel={() => { setReplyTo(null); setReplyText(''); }}
                    />
                  </div>
                )}
              </div>
            );
          })}

          {hasMore && (
            <Button variant="outline" className="w-full" disabled={loadingMore} onClick={() => void loadMoreRoots()}>
              {loadingMore && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
              加载更多留言
            </Button>
          )}
        </div>
      )}

      {/* 管理员禁言：选时长即生效，-1 为永久 */}
      <Dialog open={muteTarget != null} onClose={() => setMuteTarget(null)}>
        <DialogHeader>
          <h2 className="text-lg font-bold">禁言用户</h2>
        </DialogHeader>
        <DialogContent>
          <p className="text-xs text-muted-foreground leading-relaxed">
            禁言期间 <strong className="text-foreground">{muteTarget?.username}</strong> 无法发表任何留言，
            到期自动解除。重置账户不会清除禁言。
          </p>
          <div className="grid grid-cols-3 gap-2 mt-4">
            {MUTE_OPTIONS.map(o => (
              <Button
                key={o.days}
                variant="outline"
                size="sm"
                disabled={muting}
                onClick={() => void handleMute(o.days)}
              >
                {o.label}
              </Button>
            ))}
          </div>
        </DialogContent>
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={() => setMuteTarget(null)}>取消</Button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}
