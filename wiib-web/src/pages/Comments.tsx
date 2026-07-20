import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  ArrowLeft, ChevronDown, CornerDownRight, Loader2, MessageSquare,
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
const ISSUE_URL = 'https://github.com/mamawai/whatifibought/issues/new';

const MUTE_OPTIONS = [
  { days: 1, label: '禁言 1 天' },
  { days: 7, label: '禁言 7 天' },
  { days: -1, label: '永久禁言' },
];

/** 正在回复谁：rootId 恒指向根评论（回复子评论也是），toUserId 决定"回复 @xxx"挂在谁身上 */
type ReplyTarget = { rootId: number; toUserId: number; toUsername: string };

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

/** 发帖与回复共用的输入框。未登录时禁用并把提示写进 placeholder，不额外弹东西 */
function ComposeBox({ value, onChange, onSubmit, submitting, canPost, placeholder, rows = 3, onCancel }: {
  value: string;
  onChange: (v: string) => void;
  onSubmit: () => void;
  submitting: boolean;
  canPost: boolean;
  placeholder: string;
  rows?: number;
  onCancel?: () => void;
}) {
  const over = value.length > MAX_LEN;
  return (
    <div className="space-y-2">
      <textarea
        value={value}
        rows={rows}
        disabled={!canPost || submitting}
        placeholder={canPost ? placeholder : '登录后才能发言'}
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
        <Button size="sm" disabled={!canPost || submitting || !value.trim() || over} onClick={onSubmit}>
          {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
          发布
        </Button>
      </div>
    </div>
  );
}

/** 单条评论：头像 + 昵称/回复对象 + 正文 + 赞踩/回复/管理动作 */
function CommentRow({ c, isChild, focused, currentUserId, isAdmin, busy, onVote, onReply, onDelete, onMute }: {
  c: CommentItem;
  isChild?: boolean;
  focused?: boolean;
  currentUserId: number | null;
  isAdmin: boolean;
  busy: boolean;
  onVote: (c: CommentItem, type: 'like' | 'dislike') => void;
  onReply: (c: CommentItem) => void;
  onDelete: (c: CommentItem) => void;
  onMute: (c: CommentItem) => void;
}) {
  const loggedIn = currentUserId != null;
  const canDelete = isAdmin || currentUserId === c.userId;
  // voted 一置就同时锁死赞和踩：一人对一条评论只有一次表态机会
  const voteTitle = !loggedIn ? '登录后才能表态' : c.voted ? '你已经表过态了' : undefined;

  return (
    <div className={cn('flex gap-2.5', focused && 'rounded-xl neu-inset px-3 py-2.5')}>
      <Avatar username={c.username} avatar={c.avatar} small={isChild} />
      <div className="flex-1 min-w-0 space-y-1">
        <div className="flex items-baseline gap-2">
          <span className="text-xs font-black truncate">{c.username}</span>
          {c.replyToUsername && (
            <span className="text-[11px] text-muted-foreground shrink-0">回复 @{c.replyToUsername}</span>
          )}
          <span className="ml-auto text-[10px] text-muted-foreground shrink-0">{fmtDateTime(c.createdAt)}</span>
        </div>

        <p className="text-[13px] leading-relaxed whitespace-pre-wrap break-words">{c.content}</p>

        <div className="flex items-center gap-1.5 flex-wrap pt-0.5">
          <ActionButton
            disabled={!loggedIn || c.voted || busy}
            title={voteTitle}
            hoverClass="hover:text-gain"
            onClick={() => onVote(c, 'like')}
          >
            <ThumbsUp className="w-3 h-3" /> {c.likeCount}
          </ActionButton>
          <ActionButton
            disabled={!loggedIn || c.voted || busy}
            title={voteTitle}
            hoverClass="hover:text-loss"
            onClick={() => onVote(c, 'dislike')}
          >
            <ThumbsDown className="w-3 h-3" /> {c.dislikeCount}
          </ActionButton>
          <ActionButton
            disabled={!loggedIn}
            title={loggedIn ? undefined : '登录后才能回复'}
            hoverClass="hover:text-primary"
            onClick={() => onReply(c)}
          >
            <CornerDownRight className="w-3 h-3" /> 回复
          </ActionButton>

          {canDelete && (
            <ActionButton hoverClass="hover:text-destructive" onClick={() => onDelete(c)}>
              <Trash2 className="w-3 h-3" /> 删除
            </ActionButton>
          )}
          {isAdmin && c.userId !== currentUserId && (
            <ActionButton hoverClass="hover:text-destructive" onClick={() => onMute(c)}>
              <VolumeX className="w-3 h-3" /> 禁言
            </ActionButton>
          )}
        </div>
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
  const loggedIn = currentUserId != null;
  const focusId = params.get('focus');

  const [roots, setRoots] = useState<CommentItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  // 已展开的根评论 → 子评论加载到第几页。子评论本身只挂在 root.children 上，
  // 只有一处持有数据，赞踩/删除的就地更新才不用同时维护两份
  const [childPage, setChildPage] = useState<Record<number, number>>({});
  const [expanding, setExpanding] = useState<number | null>(null);

  const [text, setText] = useState('');
  const [posting, setPosting] = useState(false);
  const [replyTo, setReplyTo] = useState<ReplyTarget | null>(null);
  const [replyText, setReplyText] = useState('');
  const [votingId, setVotingId] = useState<number | null>(null);
  const [muteTarget, setMuteTarget] = useState<CommentItem | null>(null);
  const [muting, setMuting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      if (focusId) {
        const root = await commentApi.context(Number(focusId));
        setRoots([root]);
        setHasMore(false);
      } else {
        const list = await commentApi.list(1, ROOT_PAGE_SIZE);
        setRoots(list);
        setHasMore(list.length === ROOT_PAGE_SIZE);
      }
      setPage(1);
      setChildPage({});
      setReplyTo(null);
    } catch (e) {
      toast((e as Error).message || '加载留言失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [focusId, toast]);

  useEffect(() => { void load(); }, [load]);

  const loadMoreRoots = async () => {
    const next = page + 1;
    try {
      const list = await commentApi.list(next, ROOT_PAGE_SIZE);
      setRoots(prev => [...prev, ...list]);
      setPage(next);
      setHasMore(list.length === ROOT_PAGE_SIZE);
    } catch (e) {
      toast((e as Error).message || '加载失败', 'error');
    }
  };

  /** 展开或继续翻子评论。第 1 页直接顶掉列表里那 2 条预览，之后追加 */
  const loadChildren = async (root: CommentItem) => {
    const next = (childPage[root.id] ?? 0) + 1;
    setExpanding(root.id);
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
      setExpanding(null);
    }
  };

  const submitRoot = async () => {
    setPosting(true);
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
      setPosting(false);
    }
  };

  const submitReply = async () => {
    if (!replyTo) return;
    const rootId = replyTo.rootId;
    setPosting(true);
    try {
      await commentApi.post(replyText.trim(), rootId, replyTo.toUserId);
      setReplyText('');
      setReplyTo(null);
      toast('回复已发布', 'success');
      // 用 context 把整串重拉：一次拿到根+全部子评论，比按页往回拼稳，也保证看得见自己刚发的
      const fresh = await commentApi.context(rootId);
      setRoots(prev => prev.map(r => (r.id === rootId ? fresh : r)));
    } catch (e) {
      toast((e as Error).message || '回复失败', 'error');
    } finally {
      setPosting(false);
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

  const handleDelete = async (c: CommentItem) => {
    try {
      await commentApi.remove(c.id);
      setRoots(prev => dropComment(prev, c.id));
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
          submitting={posting && !replyTo}
          canPost={loggedIn}
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
                        onDelete={c => void handleDelete(c)}
                        onMute={setMuteTarget}
                      />
                    ))}
                  </div>
                )}

                {rest > 0 && (
                  <button
                    disabled={expanding === root.id}
                    onClick={() => void loadChildren(root)}
                    className="ml-5 text-[11px] font-bold text-primary hover:underline disabled:opacity-50 cursor-pointer flex items-center gap-1"
                  >
                    {expanding === root.id && <Loader2 className="w-3 h-3 animate-spin" />}
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
                      submitting={posting}
                      canPost={loggedIn}
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
            <Button variant="outline" className="w-full" onClick={() => void loadMoreRoots()}>
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
