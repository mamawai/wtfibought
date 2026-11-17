import { useId, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { CircleAlert, CircleCheck, Plug, Search } from 'lucide-react';
import { cn } from '../lib/utils';
import { EFFORT_PRESETS } from '../lib/llmEffort';
import { Seg } from './ui/seg';
import { useToast } from './ui/use-toast';
import './LlmEndpointForm.css';

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
  /** Gemini 来源是 Antigravity（如经 CPA 反代）：gemini 协议勾了联网搜索才有这个勾选框，其余情况后端归一 false */
  antigravity: boolean;
  /** 上下文窗口（token），界面按 K 填 */
  contextWindow: number;
}

/** 上下文窗口默认 256K；界面上的范围 128K–1000K，与后端 LlmEndpointService 同口径 */
export const DEFAULT_CONTEXT_WINDOW = 256_000;

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
  /** Admin 平台位：没有联网搜索、上下文窗口这两项 */
  platform?: boolean;
  /** 摆在最后一行右边的按钮（取消 / 保存） */
  actions?: ReactNode;
}

/**
 * LLM 端点表单（样式见 LlmEndpointForm.css；BYOK 端点库里的一条：名称 + 协议 + Base URL + key + 模型 + 思考档位 + 上下文窗口）。
 * Admin 平台 LLM 配置也用它。detectModels 那段有个踩过的坑（见下）。
 *
 * exists 是独立 prop 而不是从 keyTail 推导：调用方常写
 * keyTail={exists ? mine?.apiKeyTail : undefined}，一旦 apiKeyTail 恰好是 undefined，
 * 推导出的 exists 就翻成 false，key 突然变必填、"留空=不换"提示消失。
 */
export function LlmEndpointForm({ value, onChange, exists, keyTail, onDetect, onTest, platform, actions }: LlmEndpointFormProps) {
  const { toast } = useToast();
  const { t } = useTranslation('ai');
  // 同一页可能同时开着两份表单，标签和输入框的配对 id 要各不相同
  const uid = useId();
  // null=还没检测；检测过就是拿到的清单（可能是空的）
  const [models, setModels] = useState<string[] | null>(null);
  const [detecting, setDetecting] = useState(false);
  const [testing, setTesting] = useState(false);
  // 最近一次测试连通性的结果
  const [tested, setTested] = useState<'ok' | 'fail' | null>(null);

  // 不走调用方的通用 run() 包装：检测不能触发页面重载，
  // 否则会用库里旧配置冲掉表单里未保存的 baseUrl/key
  const detect = async () => {
    // 转圈的钮键盘还按得动
    if (detecting) return;
    setDetecting(true);
    try {
      setModels(await onDetect());
    } catch (e) {
      toast((e as Error).message || t('endpoint.detectFailed'), 'error');
    } finally {
      setDetecting(false);
    }
  };

  // 测试连通性按钮：没勾联网搜索的保存不发上游请求，想验通不通就点这里；失败的原话另弹一条
  const test = async () => {
    if (testing) return;
    setTesting(true);
    try {
      await onTest();
      setTested('ok');
    } catch (e) {
      setTested('fail');
      toast((e as Error).message || t('endpoint.testFailed'), 'error');
    } finally {
      setTesting(false);
    }
  };

  // 后端测连通性看的几项（协议、地址、key、模型、档位、联网搜索、Antigravity）改了，上次的测试结果不作数
  const setProbed = (patch: Partial<LlmEndpointValue>) => {
    setTested(null);
    onChange(patch);
  };

  // 换了协议，之前的检测结果也不作数
  const setProtocol = (p: string) => {
    setModels(null);
    setProbed({ apiProtocol: p });
  };

  const searchable = SEARCHABLE.has(value.apiProtocol);
  // 检测到的模型：模型名输入即过滤，点一个填进去；网关不支持 /models 时照常手输
  const kw = value.model.trim().toLowerCase();
  const hits = models?.filter(m => m.toLowerCase().includes(kw)) ?? [];
  return (
    <div className={cn('epf', !exists && 'new')}>
      <div className="fld">
        <label className="fl" htmlFor={`${uid}name`}>{t('endpoint.name')}</label>
        <label className="inp">
          <input id={`${uid}name`} value={value.name} onChange={e => onChange({ name: e.target.value })}
                 placeholder={t('endpoint.namePh')} maxLength={32} autoComplete="off" />
        </label>
      </div>
      <div className="fld">
        <div className="fl">{t('endpoint.protocol')}</div>
        <Seg className="full" label={t('endpoint.protocol')} value={PROTOCOLS.includes(value.apiProtocol) ? value.apiProtocol : 'openai'}
             onChange={setProtocol} options={PROTOCOLS.map(p => ({ value: p, label: p }))} />
      </div>
      <div className="fld w2">
        <label className="fl" htmlFor={`${uid}url`}>{t('endpoint.baseUrl')}</label>
        <label className="inp">
          <input id={`${uid}url`} value={value.baseUrl} onChange={e => setProbed({ baseUrl: e.target.value })}
                 placeholder={BASE_URL_PH[value.apiProtocol] ?? BASE_URL_PH.openai} autoComplete="off" spellCheck={false} />
        </label>
      </div>
      <div className="fld w2">
        <label className="fl" htmlFor={`${uid}key`}><span>API Key</span>{exists && <small>{t('endpoint.keyTail', { tail: keyTail })}</small>}</label>
        <label className="inp">
          <input id={`${uid}key`} type="password" value={value.apiKey} onChange={e => setProbed({ apiKey: e.target.value })} autoComplete="off"
                 placeholder={exists ? t('endpoint.keyPhExists') : t(platform ? 'endpoint.keyPhPlatform' : 'endpoint.keyPh')} />
        </label>
      </div>
      <div className="fld w2">
        <label className="fl" htmlFor={`${uid}model`}>{t('endpoint.model')}</label>
        <div className="mdl">
          <label className="inp">
            <input id={`${uid}model`} value={value.model} onChange={e => setProbed({ model: e.target.value })}
                   placeholder="deepseek-chat" autoComplete="off" spellCheck={false} />
          </label>
          <button type="button" className={cn('btn', detecting && 'busy')} onClick={() => void detect()} title={t('endpoint.detectTitle')}
                  disabled={!value.baseUrl.trim() || (!exists && !value.apiKey.trim())}>
            <Search className="ic" /><span>{t('endpoint.detect')}</span>
          </button>
        </div>
        {models && (
          <div className="det">
            <span>{models.length ? t('endpoint.detected', { count: models.length }) : t('endpoint.noModels')}</span>
            {hits.map(m => (
              <button key={m} type="button" className="chip" aria-pressed={value.model === m} onClick={() => setProbed({ model: m })}>{m}</button>
            ))}
            {models.length > 0 && hits.length === 0 && <span>{t('endpoint.noMatch', { kw: value.model.trim() })}</span>}
          </div>
        )}
      </div>
      <div className="fld">
        <label className="fl" htmlFor={`${uid}effort`}>{t('endpoint.effort')}</label>
        {/* 输入框是唯一真值，下面的小片只管往里填：各家档位名字自己定，写死五选一会挡住 xhigh 这类 */}
        <label className="inp">
          <input id={`${uid}effort`} value={value.reasoningEffort} onChange={e => setProbed({ reasoningEffort: e.target.value })}
                 placeholder={t('endpoint.effortPh')} maxLength={16} autoComplete="off" />
        </label>
        <div className="chips">
          {EFFORT_PRESETS.map(o => (
            <button key={o.value} type="button" className="chip" aria-pressed={value.reasoningEffort === o.value}
                    onClick={() => setProbed({ reasoningEffort: o.value })}>
              {o.label ?? t('effort.default')}
            </button>
          ))}
        </div>
        {/* 模型支不支持这个参数查不到：协议的 /v1/models 只回 id/object/created/owned_by。
            传给不支持的模型各家表现不一致（有的忽略，OpenAI 官方直接 400），所以只能让用户自己试 */}
        <p className="hint">{t('endpoint.effortHint')}</p>
      </div>
      {/* 上下文窗口：按 K 填、按 token 存，只收数字和小数点；清空时存 0，交给后端报范围 */}
      {!platform && (
        <div className="fld">
          <label className="fl" htmlFor={`${uid}ctx`}>{t('endpoint.contextWindow')}</label>
          <label className="inp">
            <input id={`${uid}ctx`} inputMode="numeric" value={value.contextWindow ? value.contextWindow / 1000 : ''}
                   onChange={e => onChange({ contextWindow: Math.round((parseFloat(e.target.value.replace(/[^\d.]/g, '')) || 0) * 1000) })} />
            <span className="u">K</span>
          </label>
          <p className="hint">{t('endpoint.contextWindowHint')}</p>
        </div>
      )}
      {/* 服务端联网搜索：chat-completions（openai 协议）没有标准的服务端搜索，勾不了。
          勾了的保存前后端会探测，探测不过不让存；只有对话的主 agent 与调研子 agent 会用它 */}
      {!platform && (
        <label className={cn('ck w2', !searchable && 'off')}>
          <input type="checkbox" checked={searchable && value.webSearch} disabled={!searchable}
                 onChange={e => setProbed({ webSearch: e.target.checked })} />
          <div>
            <b>{t('endpoint.webSearch')}</b>
            <span>{searchable ? t('endpoint.webSearchHint') : t('endpoint.webSearchOpenAiHint')}</span>
          </div>
        </label>
      )}
      {/* Antigravity 来源：它不收搜索和本地工具在同一请求里，勾上后后端把搜索改成单独发一次只带搜索的请求 */}
      {!platform && value.apiProtocol === 'gemini' && value.webSearch && (
        <label className="ck w2">
          <input type="checkbox" checked={value.antigravity} onChange={e => setProbed({ antigravity: e.target.checked })} />
          <div><b>{t('endpoint.antigravity')}</b><span>{t('endpoint.antigravityHint')}</span></div>
        </label>
      )}
      <div className="ft w2">
        <button type="button" className={cn('btn', testing && 'busy')} onClick={() => void test()} title={t('endpoint.testTitle')}
                disabled={!value.baseUrl.trim() || !value.model.trim() || (!exists && !value.apiKey.trim())}>
          <Plug className="ic" /><span>{t('endpoint.test')}</span>
        </button>
        {tested && (
          <span className={cn('res', tested === 'ok' ? 'up' : 'dn')}>
            {tested === 'ok' ? <CircleCheck className="ic" /> : <CircleAlert className="ic" />}
            {tested === 'ok' ? t('endpoint.testOk') : t('endpoint.testFailed')}
          </span>
        )}
        <span className="sp" />
        {actions}
      </div>
    </div>
  );
}
