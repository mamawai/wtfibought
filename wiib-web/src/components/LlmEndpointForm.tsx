import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Globe, KeyRound, Loader2, PlugZap, ScanSearch } from 'lucide-react';
import { cn } from '../lib/utils';
import { EFFORT_PRESETS } from '../lib/llmEffort';
import { useToast } from './ui/use-toast';

/** 一条端点的表单值（与后端 LlmEndpointSaveRequest 同形） */
export interface LlmEndpointValue {
  /** 用户给的名字，下拉框里认它 */
  name: string;
  apiProtocol: string;
  baseUrl: string;
  model: string;
  /** 思考档位，空串=不传给上游走模型默认 */
  reasoningEffort: string;
  apiKey: string;
  /** 服务端联网搜索（responses / anthropic / gemini 协议可勾；openai 协议下后端归一 false） */
  webSearch: boolean;
}

const PROTOCOLS = ['openai', 'responses', 'anthropic', 'gemini'];
/** 各协议的 Base URL 示例，只是占位不是白名单 */
const BASE_URL_PH: Record<string, string> = {
  openai: 'https://api.deepseek.com',
  responses: 'https://api.deepseek.com',
  anthropic: 'https://api.anthropic.com',
  gemini: 'https://generativelanguage.googleapis.com',
};
/** 能在请求里声明服务端搜索工具的协议，与后端 AiProtocols.supportsServerSearch 同口径 */
const SEARCHABLE = new Set(['responses', 'anthropic', 'gemini']);

export interface LlmEndpointFormProps {
  value: LlmEndpointValue;
  onChange: (patch: Partial<LlmEndpointValue>) => void;
  /** 编辑已有端点：决定 key 是否必填、是否显示"留空=不换"。独立于 keyTail，见组件注释 */
  exists?: boolean;
  /** 已存在时显示的 key 尾号（纯展示） */
  keyTail?: string;
  /** 拉模型清单 */
  onDetect: () => Promise<string[]>;
  /** 连通性探测 */
  onTest: () => Promise<void>;
  /** 不显示联网搜索那块（Admin 平台位没有搜索这回事） */
  noWebSearch?: boolean;
}

/**
 * LLM 端点表单（BYOK 端点库里的一条：名称 + 协议 + Base URL + 模型 + 思考档位 + key）。Admin 平台 LLM 配置也用它。
 * detectModels 那段有个踩过的坑（见下）。
 *
 * exists 是独立 prop 而不是从 keyTail 推导：调用方常写
 * keyTail={exists ? mine?.apiKeyTail : undefined}，一旦 apiKeyTail 恰好是 undefined，
 * 推导出的 exists 就翻成 false，key 突然变必填、"留空=不换"提示消失。
 */
export function LlmEndpointForm({ value, onChange, exists, keyTail, onDetect, onTest, noWebSearch }: LlmEndpointFormProps) {
  const { toast } = useToast();
  const { t } = useTranslation('ai');
  const [models, setModels] = useState<string[]>([]);
  const [detecting, setDetecting] = useState(false);
  const [testing, setTesting] = useState(false);

  // 不走调用方的通用 run() 包装：检测不能触发页面重载，
  // 否则会用库里旧配置冲掉表单里未保存的 baseUrl/key
  const detect = async () => {
    setDetecting(true);
    try {
      const list = await onDetect();
      setModels(list);
      toast(list.length ? t('endpoint.detected', { count: list.length }) : t('endpoint.noModels'),
            list.length ? 'success' : 'error');
    } catch (e) {
      toast((e as Error).message || t('endpoint.detectFailed'), 'error');
    } finally {
      setDetecting(false);
    }
  };

  // 连通性探测独立成按钮：保存时不再顺带发一次真实 LLM 请求，
  // 想验通不通就点这里，不想验就直接存
  const test = async () => {
    setTesting(true);
    try {
      await onTest();
      toast(t('endpoint.testOk'), 'success');
    } catch (e) {
      toast((e as Error).message || t('endpoint.testFailed'), 'error');
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="space-y-3">
      <div className="grid sm:grid-cols-3 gap-3">
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold">{t('endpoint.name')}</span>
          <input value={value.name} onChange={e => onChange({ name: e.target.value })}
                 placeholder={t('endpoint.namePh')} maxLength={32}
                 className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs" />
        </label>
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold">{t('endpoint.protocol')}</span>
          <select value={PROTOCOLS.includes(value.apiProtocol) ? value.apiProtocol : 'openai'}
                  onChange={e => onChange({ apiProtocol: e.target.value })}
                  className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs font-bold num">
            {PROTOCOLS.map(p => <option key={p} value={p}>{p}</option>)}
          </select>
        </label>
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold">{t('endpoint.baseUrl')}</span>
          <input value={value.baseUrl} onChange={e => onChange({ baseUrl: e.target.value })}
                 placeholder={BASE_URL_PH[value.apiProtocol] ?? BASE_URL_PH.openai}
                 className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
        </label>
      </div>

      <div className="grid sm:grid-cols-2 gap-3">
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold">{t('endpoint.model')}</span>
          <div className="flex gap-1.5">
            <input value={value.model} onChange={e => onChange({ model: e.target.value })}
                   placeholder="deepseek-chat"
                   className="flex-1 min-w-0 h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
            <button type="button" onClick={() => void detect()}
                    disabled={detecting || !value.baseUrl.trim() || (!exists && !value.apiKey.trim())}
                    title={t('endpoint.detectTitle')}
                    className="shrink-0 border border-border hover:bg-surface-hover rounded-lg px-2.5 h-9 text-xs font-bold text-primary flex items-center gap-1 disabled:opacity-50">
              {detecting ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                         : <ScanSearch className="w-3.5 h-3.5" />}
              {t('endpoint.detect')}
            </button>
          </div>
        </label>
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold flex items-center gap-1">
            <KeyRound className="w-3 h-3" /> API Key
            {exists && <span className="text-muted-foreground/70 font-normal">{t('endpoint.keyTail', { tail: keyTail })}</span>}
          </span>
          <input value={value.apiKey} onChange={e => onChange({ apiKey: e.target.value })}
                 type="password"
                 placeholder={exists ? t('endpoint.keyPhExists') : t('endpoint.keyPh')}
                 className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
        </label>
      </div>

      <div className="space-y-1 text-xs">
        <span className="text-muted-foreground font-bold">{t('endpoint.effort')}</span>
        {/* 输入框是唯一真值，下面的芯片只管往里填：各家档位名字自己定，写死五选一会挡住 xhigh 这类 */}
        <input value={value.reasoningEffort} onChange={e => onChange({ reasoningEffort: e.target.value })}
               placeholder={t('endpoint.effortPh')} maxLength={16}
               className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
        <div className="flex flex-wrap gap-1.5">
          {EFFORT_PRESETS.map(o => (
            <button key={o.value} type="button" onClick={() => onChange({ reasoningEffort: o.value })}
                    className={cn('px-2 h-7 rounded-md border text-[11px] num',
                      (value.reasoningEffort ?? '') === o.value
                        ? 'border-primary/60 bg-card-2 text-primary font-bold'
                        : 'border-border text-muted-foreground hover:text-foreground')}>
              {o.label ?? t('effort.default')}
            </button>
          ))}
        </div>
        {/* 模型支不支持这个参数查不到：协议的 /v1/models 只回 id/object/created/owned_by。
            传给不支持的模型各家表现不一致（有的忽略，OpenAI 官方直接 400），所以只能让用户自己试 */}
        <span className="text-[10px] text-muted-foreground/70 block">
          {t('endpoint.effortHint')}
        </span>
      </div>

      {/* 服务端联网搜索：能声明搜索工具的协议才有勾选框（chat-completions 没有标准的服务端搜索）。
          端点支不支持查不到，与档位同理由用户自己勾；只有对话的汇总者会用它 */}
      {noWebSearch ? null : SEARCHABLE.has(value.apiProtocol) ? (
        <label className="flex items-start gap-2 text-xs cursor-pointer select-none">
          <input type="checkbox" checked={value.webSearch}
                 onChange={e => onChange({ webSearch: e.target.checked })}
                 className="mt-0.5 accent-primary" />
          <span className="space-y-0.5">
            <span className="font-bold flex items-center gap-1">
              <Globe className="w-3 h-3" /> {t('endpoint.webSearch')}
            </span>
            <span className="text-[10px] text-muted-foreground/70 block">{t('endpoint.webSearchHint')}</span>
          </span>
        </label>
      ) : (
        <span className="text-[10px] text-muted-foreground/70 flex items-center gap-1">
          <Globe className="w-3 h-3" /> {t('endpoint.webSearchOpenAiHint')}
        </span>
      )}

      <button type="button" onClick={() => void test()}
              disabled={testing || !value.baseUrl.trim() || !value.model.trim()
                        || (!exists && !value.apiKey.trim())}
              title={t('endpoint.testTitle')}
              className="border border-border hover:bg-surface-hover rounded-lg px-2.5 h-9 text-xs font-bold text-primary flex items-center gap-1 disabled:opacity-50">
        {testing ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                 : <PlugZap className="w-3.5 h-3.5" />}
        {t('endpoint.test')}
      </button>

      {/* 检测到的模型清单：模型名输入即过滤，点击填入；网关不支持 /models 时照常手输 */}
      {models.length > 0 && (() => {
        const kw = value.model.trim().toLowerCase();
        const hits = models.filter(m => m.toLowerCase().includes(kw));
        return (
          <div className="max-h-28 overflow-y-auto flex flex-wrap gap-1.5 content-start rounded-lg border border-border bg-card-2/50 p-2">
            {hits.map(m => (
              <button key={m} type="button" onClick={() => onChange({ model: m })}
                      className={cn('px-2 h-7 rounded-md border text-[11px] num',
                        value.model === m
                          ? 'border-primary/60 bg-card-2 text-primary font-bold'
                          : 'border-border text-muted-foreground hover:text-foreground')}>
                {m}
              </button>
            ))}
            {hits.length === 0 && (
              <span className="text-[11px] text-muted-foreground px-1 py-1">
                {t('endpoint.noMatch', { kw: value.model.trim() })}
              </span>
            )}
          </div>
        );
      })()}
    </div>
  );
}
