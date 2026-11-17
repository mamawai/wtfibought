import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { Ban, ChevronLeft, LogIn, MessageCircle, Pencil, RefreshCw, Reply, ThumbsDown, ThumbsUp, Trash2 } from 'lucide-react';
import { commentApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { Avatar } from '../components/Avatar';
import { EmptyState } from '../components/EmptyState';
import { SureButton } from '../components/SureButton';
import { useToast } from '../components/ui/use-toast';
import { Dialog, DialogContent, DialogHeader } from '../components/ui/dialog';
import { cn, fmtDateTime, fmtRelative, spinIcon } from '../lib/utils';
import i18n from '../i18n';
import type { CommentItem } from '../types';
import './Comments.css';

const ADMIN_USER_ID = 1;
const MAX_LEN = 500;
const ROOT_PAGE_SIZE = 20;
const CHILD_PAGE_SIZE = 10;
const ISSUE_URL = 'https://github.com/mamawai/wtfibought/issues/new';

/** 可选禁言天数；永久禁言单独一颗钮（传 -1）。文案在渲染时按天数现查，不写死在数组里 */
const MUTE_DAYS = [1, 7];

/** 正在回复谁：id 是点了「回复」的那条（回复框挂在它底下），rootId 恒指向根评论（回复子评论也是），toUserId 决定"回复 @xxx"挂在谁身上 */
type ReplyTarget = { id: number; rootId: number; toUserId: number; toUsername: string };

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

/** 发帖、回复、编辑三处共用的输入框：右下角字数，超了变红 */
function TextBox({ value, onChange, disabled, placeholder, label, rows = 2, autoFocus }: {
  value: string;
  onChange: (v: string) => void;
  disabled: boolean;
  placeholder: string;
  label: string;
  rows?: number;
  autoFocus?: boolean;
}) {
  return (
    <label className="inp ta">
      <textarea value={value} rows={rows} disabled={disabled} placeholder={placeholder} aria-label={label}
                autoFocus={autoFocus} onChange={e => onChange(e.target.value)} />
      <span className={cn('cnt num', value.length > MAX_LEN && 'over')}>{value.length} / {MAX_LEN}</span>
    </label>
  );
}

/** 回复、编辑的小框：输入框 + 取消 / 提交。ready=false 是 user 还没拉回来，先禁用免得拿不到自己的 id */
function InlineBox({ value, onChange, onSubmit, onCancel, submitting, ready, placeholder, label, submitLabel }: {
  value: string;
  onChange: (v: string) => void;
  onSubmit: () => void;
  onCancel: () => void;
  submitting: boolean;
  ready: boolean;
  placeholder: string;
  label: string;
  submitLabel: string;
}) {
  const { t } = useTranslation('common');
  return (
    <div className="rbox">
      <TextBox value={value} onChange={onChange} disabled={!ready || submitting} placeholder={placeholder} label={label} autoFocus />
      <div className="f">
        <button type="button" className="btn sm" onClick={onCancel}>{t('cancel')}</button>
        <button type="button" className={cn('btn sm ink', submitting && 'busy')} onClick={onSubmit}
                disabled={!ready || submitting || !value.trim() || value.length > MAX_LEN}>
          <span>{submitLabel}</span>
        </button>
      </div>
    </div>
  );
}

/** 单条评论：头像 + 名字时间 + 正文 + 赞踩/回复/编辑/管理动作；replyBox 是挂在它底下的回复框，children 是根评论底下那串回复 */
function CommentRow({ c, focused, currentUserId, isAdmin, guest, busy, onVote, onReply, onEdit, onDelete, onMute, replyBox, children }: {
  c: CommentItem;
  focused: boolean;
  currentUserId: number | null;
  isAdmin: boolean;
  guest: boolean;
  busy: boolean;
  onVote: (c: CommentItem, type: 'like' | 'dislike') => void;
  onReply: (c: CommentItem) => void;
  /** 失败时抛出，本行据此决定不关编辑框；提示由父层统一弹 */
  onEdit: (c: CommentItem, content: string) => Promise<void>;
  onDelete: (c: CommentItem) => void;
  onMute: (c: CommentItem) => void;
  replyBox?: ReactNode;
  children?: ReactNode;
}) {
  const { t } = useTranslation(['community', 'common']);
  const { toast } = useToast();
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
  const voteTitle = c.voted ? t('comments.voteAlready') : undefined;

  /** 游客点赞踩、回复：提示去登录；登录了但 user 还没拉回来就先不动 */
  const act = (fn: () => void) => () => {
    if (guest) toast(t('comments.loginToPost'), 'info');
    else if (ready) fn();
  };

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
    <div className={cn('cm', focused && 'hl')}>
      <Avatar name={c.username} src={c.avatar} />
      <div>
        <div className="cm-h">
          <b>{c.username}</b>
          {isMine && <span className="tag new">{t('ranking.me.badge')}</span>}
          <span className="num" title={fmtDateTime(c.createdAt)}>{fmtRelative(c.createdAt)}</span>
          {c.updatedAt && !c.selfDeleted && <span>{t('comments.edited')}</span>}
        </div>

        {editing ? (
          <InlineBox value={editText} onChange={setEditText} onSubmit={() => void submitEdit()} onCancel={() => setEditing(false)}
                     submitting={saving} ready={ready} placeholder={t('comments.editPlaceholder')}
                     label={t('comments.editPlaceholder')} submitLabel={t('common:save')} />
        ) : (
          <p className={cn('tx', c.selfDeleted && 'del')}>
            {c.replyToUsername && <span className="at">{t('comments.replyToUser', { name: c.replyToUsername })}</span>}
            {/* 自删的正文在后端被中文占位覆盖掉了，这里认 selfDeleted 标出自己的词条，
                存量行照样跟着界面语言走 */}
            {c.selfDeleted ? t('comments.deletedPlaceholder') : c.content}
          </p>
        )}

        {/* 编辑时收起动作区：边改边点赞是个没意义的中间态 */}
        {!editing && (
          <div className="cm-a">
            <button type="button" disabled={c.voted || busy} title={voteTitle} aria-label={t('comments.like')} onClick={act(() => onVote(c, 'like'))}>
              <ThumbsUp className="ic" /><span className="num">{c.likeCount || ''}</span>
            </button>
            <button type="button" disabled={c.voted || busy} title={voteTitle} aria-label={t('comments.dislike')} onClick={act(() => onVote(c, 'dislike'))}>
              <ThumbsDown className="ic" /><span className="num">{c.dislikeCount || ''}</span>
            </button>
            <button type="button" onClick={act(() => onReply(c))}><Reply className="ic" />{t('comments.reply')}</button>
            {canEdit && <button type="button" onClick={startEdit}><Pencil className="ic" />{t('common:edit')}</button>}
            {canDelete && (
              <SureButton className="risk0" sureClassName="sure" onConfirm={() => onDelete(c)}
                          sureLabel={<><Trash2 className="ic" />{t('comments.deleteSure')}</>}>
                <Trash2 className="ic" />{t('common:delete')}
              </SureButton>
            )}
            {isAdmin && !isMine && (
              <button type="button" className="risk0" onClick={() => onMute(c)}><Ban className="ic" />{t('comments.mute')}</button>
            )}
          </div>
        )}
        {replyBox}
        {children}
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
  const { t } = useTranslation(['community', 'common']);
  const { toast } = useToast();
  const [params, setParams] = useSearchParams();
  const user = useUserStore(s => s.user);
  const guest = useUserStore(s => !s.token);

  const currentUserId = user?.id ?? null;
  const isAdmin = currentUserId === ADMIN_USER_ID;
  // 游客恒为 null，只能看；登录了还是 null 是 fetchUser 还没回来
  const ready = currentUserId != null;
  const focusId = params.get('focus');

  const [roots, setRoots] = useState<CommentItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  // 已展开的根评论 → 子评论加载到第几页。子评论本身只挂在 root.children 上，
  // 只有一处持有数据，赞踩/删除的就地更新才不用同时维护两份
  const [childPage, setChildPage] = useState<Record<number, number>>({});
  // 正在展开哪几条根评论
  const [expanding, setExpanding] = useState<Set<number>>(new Set());
  // 丢弃过期响应用。focus 参数变化和刷新按钮都会触发 load，慢的那个后返回会覆盖新结果
  const loadSeq = useRef(0);

  const [text, setText] = useState('');
  // 留言规范默认收着
  const [rulesOpen, setRulesOpen] = useState(false);
  // 发主楼、发回复各自的提交中标志
  const [postingRoot, setPostingRoot] = useState(false);
  const [postingReply, setPostingReply] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [replyTo, setReplyTo] = useState<ReplyTarget | null>(null);
  const [replyText, setReplyText] = useState('');
  const [votingId, setVotingId] = useState<number | null>(null);
  const [muteTarget, setMuteTarget] = useState<CommentItem | null>(null);
  const [muting, setMuting] = useState(false);

  // 不依赖 t：切语言会换新 t，依赖了就会整页重载回第 1 页、收起展开、关掉回复框
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
      if (seq === loadSeq.current) toast((e as Error).message || i18n.t('community:comments.toast.loadFailed'), 'error');
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
      // 按时间倒序分页，期间有新帖会把旧帖往后挤，这一页开头可能已经显示过，按 id 去重
      setRoots(prev => {
        const seen = new Set(prev.map(r => r.id));
        return [...prev, ...list.filter(r => !seen.has(r.id))];
      });
      setPage(next);
      setHasMore(list.length === ROOT_PAGE_SIZE);
    } catch (e) {
      toast((e as Error).message || t('common:loadFailed'), 'error');
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
      toast((e as Error).message || t('comments.toast.loadRepliesFailed'), 'error');
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
      toast(t('comments.toast.posted'), 'success');
      // 聚焦视图下新根评论不属于当前这串，清掉 focus 退回全部列表才看得到（清参数会触发重载）
      if (focusId) setParams({}, { replace: true });
      else await load();
    } catch (e) {
      toast((e as Error).message || t('comments.toast.postFailed'), 'error');
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
      toast((e as Error).message || t('comments.toast.replyFailed'), 'error');
      setPostingReply(false);
      return;
    }

    // 到这儿回复已经入库了，下面单独重拉这一串
    setReplyText('');
    setReplyTo(null);
    toast(t('comments.toast.replyPosted'), 'success');
    try {
      // 用 context 把整串重拉：一次拿到根+全部子评论，比按页往回拼稳，也保证看得见自己刚发的
      const fresh = await commentApi.context(rootId);
      setRoots(prev => prev.map(r => (r.id === rootId ? fresh : r)));
    } catch {
      // 回复本身是成功的，只是没刷出来，提示一下让用户自己刷新，别说成"失败"
      toast(t('comments.toast.replyPostedStale'), 'success');
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
      toast((e as Error).message || t('comments.toast.voteFailed'), 'error');
    } finally {
      setVotingId(null);
    }
  };

  /** 编辑成功后就地换正文并打上"已编辑"，不整页重拉。失败往上抛，由 CommentRow 决定不关编辑框 */
  const handleEdit = async (c: CommentItem, content: string) => {
    try {
      await commentApi.edit(c.id, content);
    } catch (e) {
      toast((e as Error).message || t('comments.toast.saveFailed'), 'error');
      throw e;
    }
    setRoots(prev => patchComment(prev, c.id, x => ({
      ...x,
      content,
      updatedAt: new Date().toISOString(),
    })));
    toast(t('comments.toast.saved'), 'success');
  };

  /**
   * 删除的界面表现跟后端分流一致：删自己的是把正文换成占位文案，评论留在原位；
   * 管理员删别人的才真的从树里摘掉（根评论会连整组子评论一起消失）
   */
  const handleDelete = async (c: CommentItem) => {
    const isMine = c.userId === currentUserId;
    try {
      await commentApi.remove(c.id);
      // 只翻 selfDeleted：正文由渲染处按这个标出词条，不必在这儿造一份占位文案
      setRoots(prev => isMine
        ? patchComment(prev, c.id, x => ({ ...x, selfDeleted: true }))
        : dropComment(prev, c.id));
      toast(t('comments.toast.deleted'), 'success');
    } catch (e) {
      toast((e as Error).message || t('comments.toast.deleteFailed'), 'error');
    }
  };

  const handleMute = async (days: number) => {
    if (!muteTarget) return;
    setMuting(true);
    try {
      await commentApi.mute(muteTarget.userId, days);
      toast(days === -1
        ? t('comments.toast.mutedPermanent', { name: muteTarget.username })
        : t('comments.toast.mutedDays', { name: muteTarget.username, count: days }), 'success');
      setMuteTarget(null);
    } catch (e) {
      toast((e as Error).message || t('comments.toast.muteFailed'), 'error');
    } finally {
      setMuting(false);
    }
  };

  /** 点「回复」：回复框挂到这条底下，再点一次收起。回复子评论时 rootId 仍取它的根：结构只有两层，永不出现三层嵌套 */
  const startReply = (c: CommentItem) => {
    setReplyTo(prev => (prev?.id === c.id ? null : { id: c.id, rootId: c.rootId ?? c.id, toUserId: c.userId, toUsername: c.username }));
    setReplyText('');
  };

  const row = (c: CommentItem, replies?: ReactNode) => (
    <CommentRow
      key={c.id}
      c={c}
      focused={focusId === String(c.id)}
      currentUserId={currentUserId}
      isAdmin={isAdmin}
      guest={guest}
      busy={votingId === c.id}
      onVote={(x, type) => void handleVote(x, type)}
      onReply={startReply}
      onEdit={handleEdit}
      onDelete={x => void handleDelete(x)}
      onMute={setMuteTarget}
      replyBox={replyTo?.id === c.id && (
        <InlineBox value={replyText} onChange={setReplyText} onSubmit={() => void submitReply()}
                   onCancel={() => { setReplyTo(null); setReplyText(''); }}
                   submitting={postingReply} ready={ready} placeholder={t('comments.replyPlaceholder')}
                   label={t('comments.replyToUser', { name: replyTo.toUsername })} submitLabel={t('comments.reply')} />
      )}
    >
      {replies}
    </CommentRow>
  );

  return (
    <div className="page-shell">
      <div className="p-bd">
        <div className="pg-h">
          <div>
            <h1>{t('comments.title')}</h1>
            <p className="d">{t('comments.subtitle')}</p>
          </div>
          <div className="r">
            <button type="button" className="ib" title={t('common:refresh')} aria-label={t('common:refresh')}
                    onClick={e => { spinIcon(e); void load(); }}>
              <RefreshCw className="ic" />
            </button>
          </div>
        </div>

        {/* 发帖框；游客换成去登录 */}
        {guest ? (
          <div className="guest blk">
            <span>{t('comments.loginToPost')}</span>
            <Link to="/login" className="btn ink"><LogIn className="ic" />{t('layout:header.login')}</Link>
          </div>
        ) : (
          <div className="post">
            <TextBox value={text} onChange={setText} disabled={!ready || postingRoot}
                     placeholder={t('comments.composePlaceholder')} label={t('comments.composePlaceholder')} />
            <div className="f">
              <button type="button" className="linkb" aria-expanded={rulesOpen} onClick={() => setRulesOpen(v => !v)}>
                {t('comments.rulesTitle')}
              </button>
              <button type="button" className={cn('btn ink', postingRoot && 'busy')} onClick={() => void submitRoot()}
                      disabled={!ready || postingRoot || !text.trim() || text.length > MAX_LEN}>
                <span>{t('comments.post')}</span>
              </button>
            </div>
            <div className={cn('col', rulesOpen && 'open')}>
              <div>
                <div className="rules blk">
                  <b>{t('comments.rulesTitle')}</b>
                  <p>{t('comments.rulesWelcome')}</p>
                  <p>
                    <Trans ns="community" i18nKey="comments.rulesIssue"
                           components={[<a key="issue" className="linkb" href={ISSUE_URL} target="_blank" rel="noreferrer" />]} />
                  </p>
                  <p>{t('comments.rulesBan')}</p>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* 聚焦视图：从通知跳进来只看那一串 */}
        {focusId && (
          <div className="focus">
            <button type="button" className="btn" onClick={() => setParams({}, { replace: true })}>
              <ChevronLeft className="ic" />{t('comments.backToAll')}
            </button>
          </div>
        )}

        <div className="list">
          {loading && roots.length === 0 ? (
            <div className="empty">{t('comments.loading')}</div>
          ) : roots.length === 0 ? (
            <EmptyState icon={<MessageCircle />} text={t('comments.emptyTitle')}><span>{t('comments.emptyHint')}</span></EmptyState>
          ) : roots.map(root => {
            const children = root.children ?? [];
            const started = childPage[root.id] !== undefined;
            const rest = root.childCount - children.length;
            return row(root, (children.length > 0 || rest > 0) && (
              <div className="rps">
                {children.map(child => row(child))}
                {rest > 0 && (
                  <button type="button" className="linkb more" disabled={expanding.has(root.id)} onClick={() => void loadChildren(root)}>
                    {started
                      ? t('comments.moreReplies', { count: rest })
                      : t('comments.viewAllReplies', { count: root.childCount })}
                  </button>
                )}
              </div>
            ));
          })}
        </div>

        {hasMore && (
          <button type="button" className={cn('btn wide lm', loadingMore && 'busy')} disabled={loadingMore} onClick={() => void loadMoreRoots()}>
            <span>{t('comments.loadMore')}</span>
          </button>
        )}

        {/* 管理员禁言：选时长即生效 */}
        <Dialog open={muteTarget != null} onClose={() => setMuteTarget(null)} className="sm">
          <DialogHeader><h3>{t('comments.muteTitle')}</h3></DialogHeader>
          <DialogContent>
            <p className="txt">
              <Trans ns="community" i18nKey="comments.muteDesc" values={{ name: muteTarget?.username ?? '' }}
                     components={[<strong key="name" />]} />
            </p>
          </DialogContent>
          <div className="mopts">
            {MUTE_DAYS.map(days => (
              <button key={days} type="button" className="btn" disabled={muting} onClick={() => void handleMute(days)}>
                {t('comments.muteDays', { count: days })}
              </button>
            ))}
          </div>
          <button type="button" className="btn risk wide" disabled={muting} onClick={() => void handleMute(-1)}>
            {t('comments.mutePermanent')}
          </button>
        </Dialog>
      </div>
    </div>
  );
}
