package com.mawai.wiibagent.trader.prompt;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.TraderRiskConfig;
import com.mawai.wiibagent.trader.wakeup.TraderWakeupRunner;
import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * trader 系统提示词组装：平台模板 + 复盘笔记 + 学习笔记 + 用户自定义段 + 固定收尾格式 + 输出语言。
 * 每次唤醒现读现拼——用户改完 customPrompt，下一根 K 线自然生效，热更新零机制。
 * <p>
 * 文本全在 {@link PromptCatalog} 的 {@code trader.*}，按用户的 {@link AgentLang} 取；骨架
 * （条件分支、循环、插值、换行框）留在这里。<b>用户自己写的字不翻译</b>——customPrompt 与
 * ownerNote 原样注入。
 * <p>
 * 分层：
 * ① 平台模板只讲事实——环境、开场白里有什么、工具、规格、护栏会拒什么、计划系统、成本、收尾格式；
 *   交易方法/风格/纪律一个字不放，那些只在主人的交易指令里；
 * ② 状态在开场白（user 消息）——账户、上一轮结论、事件、战绩都在那边，system 只放环境、规则与格式；
 * ③ 固定收尾格式——结论块既是公开展示单元，也是下一轮回注的材料；
 * ④ 主人的交易指令放最后（近因权重最高）且明示优先级：方法与纪律听主人的，规格/护栏/收尾格式不可覆盖；
 * ⑤ 主人留言不进系统提示词，进唤醒开场白末尾（{@link #ownerNoteBlock}）：进了 user 消息它才是"本轮要回答的问题之一"。
 *   举证责任倒置——执行不需要理由，否决只认两种：撞系统硬规则、引用具体数字的独立判断。按剩余轮次逐轮注入、减到 0 清空。
 * <p>
 * ⑥ 输出语言硬收尾排在④之后：④是主人亲笔、不翻译，可能与平台模板不同语言，且近因权重最高。
 * 语言指令因此说两次——模板正文一次，整篇最末一行再一次。
 */
@Component
@RequiredArgsConstructor
public class TraderPromptAssembler {

    /** 只为留言而来：注入的同一处就得把轮次减掉，见 {@link #consumeOwnerNote} */
    private final AiTraderMapper traderMapper;
    private final PromptCatalog prompts;

    public String assemble(AiTrader trader, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        WakeWindow window = WakeWindow.of(trader);
        // 平台模板只讲事实，永远注入；复盘笔记/学习笔记是数据不是指令，也永远注入
        sb.append(platformTemplate(lang, trader.getIntervalCode(), trader.getSymbols(),
                TraderRiskConfig.of(trader), window == null ? null : window.text()));

        if (trader.getMemory() != null && !trader.getMemory().isBlank()) {
            sb.append('\n').append(prompts.get(lang, "trader.label.memory")).append('\n')
                    .append(trader.getMemory()).append('\n');
        }

        // 与复盘笔记并列注入不合并：来源分开，模型才分得清"自己的教训"与"从别人学的"
        if (trader.getLearningNotes() != null && !trader.getLearningNotes().isBlank()) {
            sb.append('\n').append(prompts.get(lang, "trader.label.learningNotes")).append('\n')
                    .append(trader.getLearningNotes()).append('\n');
        }

        // 主人的交易指令段无条件出：模板正文和开场白都指向它。有字就原样注入不过词表，
        // 紧跟一句 ownerWritten 交代"这段是主人亲笔、可能是另一门语言、照意思做但输出语言不变"，位置在他的字之前；
        // 没字就填占位句
        sb.append('\n').append(prompts.get(lang, "trader.label.customPrompt")).append('\n');
        if (trader.getCustomPrompt() != null && !trader.getCustomPrompt().isBlank()) {
            sb.append(prompts.get(lang, "trader.label.ownerWritten")).append('\n')
                    .append(trader.getCustomPrompt()).append('\n');
        } else {
            sb.append(prompts.get(lang, "trader.label.customPromptEmpty")).append('\n');
        }

        // 固定收尾格式：系统强制，主人指令改不掉；排在主人指令之后压近因
        sb.append('\n').append(closingFormat(lang, trader.getSymbols())).append('\n');
        // 输出语言硬收尾：整篇最末一行，排在主人指令之后（模板正文里说过一次，这里再说一次）
        sb.append('\n').append(prompts.get(lang, "trader.label.outputLanguage")).append('\n');
        return sb.toString();
    }

    /** 平台默认交易指令：新建 trader 时预填进指令框的那段，按主人语言取 */
    public String defaultInstructions(AgentLang lang) {
        return prompts.get(lang, "trader.label.defaultInstructions");
    }

    /**
     * 固定收尾格式块：系统强制，主人指令改不掉。复盘素材、stale 剔段、观望门控全靠 [SYMBOL] 段切分，
     * 格式丢了下游全退化。骨架按 trader 真实币种生成：首币写全三行，其余只列段头——模型照着填，不用自己猜币码。
     * 预览接口也用它，MyTrader 页看到的与真喂的一致
     */
    public String closingFormat(AgentLang lang, String symbols) {
        StringBuilder skeleton = new StringBuilder();
        for (String s : symbols.split(",")) {
            String symbol = s.strip();
            if (symbol.isEmpty()) {
                continue;
            }
            if (!skeleton.isEmpty()) {
                skeleton.append('\n');
            }
            skeleton.append(prompts.get(lang,
                    skeleton.isEmpty() ? "trader.label.closingSection" : "trader.label.closingSectionMore",
                    Map.of("symbol", symbol)));
        }
        return prompts.get(lang, "trader.label.closingFormat", Map.of(
                "mark", prompts.get(lang, "trader.mark.conclusion"),
                "skeleton", skeleton.toString()));
    }

    /**
     * 主人留言段：拼进唤醒开场白末尾（user 消息，最近因位置），不进系统提示词。
     * 段头报剩余次数 + 亲笔提示 + 正文原样 + footer（举证责任倒置、结论里必须单起一行对账、反重放）。
     * 无待读留言返回空串。<b>注入即消费</b>：返回非空就已经递减一轮，见 {@link #consumeOwnerNote}。
     */
    public String ownerNoteBlock(AiTrader trader, AgentLang lang) {
        String note = trader.getOwnerNote();
        if (note == null || note.isBlank()) {
            return "";
        }
        // 正文非空才叫"有待读留言"，轮次异常一律当 1 轮：迁移时 ALTER 跑了而回填 UPDATE 漏跑，
        // 库里就会出现"有正文、轮次是 0/null"。当 1 处理最坏只是退化回一次性留言，不炸也不吞
        Integer raw = trader.getOwnerNoteRounds();
        int rounds = raw == null || raw <= 0 ? 1 : raw;
        int left = rounds - 1;
        String roundsText = left == 0 ? prompts.get(lang, "trader.label.ownerNoteOnce")
                : prompts.get(lang, "trader.label.ownerNoteMore", Map.of("left", left));
        // 明说还剩几次：持续叮嘱，不是"现在就执行一次"；footer 里的反重放防"平 ETH"念三次平三次。
        // 收尾标记走 {{mark}} 与系统提示词同源
        String block = "\n" + prompts.get(lang, "trader.label.ownerNote", Map.of("rounds", roundsText))
                + "\n" + prompts.get(lang, "trader.label.ownerWritten")
                + "\n" + note
                + "\n" + prompts.get(lang, "trader.label.ownerNoteFooter",
                        Map.of("mark", prompts.get(lang, "trader.mark.conclusion")));
        consumeOwnerNote(trader, note, left);
        return block;
    }

    /**
     * 消费一轮：递减必须紧贴注入写在一起。
     * 拆成两处（比如让唤醒回路事后减）迟早会掉进两个坑之一——注了没减，留言每轮重念、
     * 模型把阶段性交代当成长期规则；减了没注，主人的话直接蒸发且无人知晓。
     * <p>
     * 代价是<b>注入即消费</b>：这一轮唤醒后面若失败，那一轮也算用掉了。选它是因为反过来更糟——
     * 不减就会重放，而"可能重复执行一条主人指令"比"偶发丢一轮念诵"危险得多。
     */
    private void consumeOwnerNote(AiTrader trader, String injected, int left) {
        // 条件 SQL，以"库里的正文仍是我注入的这条"为前置：trader 是调度时刻的快照，
        // 取到这里之间隔着数次 HTTP 与多条 SQL，
        // 期间主人可能已在面板改写或撤回。正文对不上就影响 0 行，只递减自己念过的那条。
        //
        // 两个 SET 都读旧行值（SQL 标准），CASE 判的是递减前的轮次：
        // 旧值 <=1 即本次是最后一次，正文一并清空；GREATEST 保证轮次不落到负数。
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, trader.getId())
                .eq(AiTrader::getOwnerNote, injected)
                .setSql("owner_note_rounds = GREATEST(owner_note_rounds - 1, 0), "
                        + "owner_note = CASE WHEN owner_note_rounds <= 1 THEN NULL ELSE owner_note END")
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        // 内存副本与库写保持同构：同一次唤醒里这个对象还会被别处读到，别让它再露一次面。
        // 无条件按 left 走——就算上面因正文被改写而没更新库，这一轮也确实已经注入过了
        if (left == 0) {
            trader.setOwnerNote(null);
        }
        trader.setOwnerNoteRounds(left);
    }

    /**
     * 平台系统提示词模板（环境/开场白/工具/规格/护栏/计划/成本/收尾）——前端预览与唤醒组装共用同一份文本。
     * 只讲事实，两个方向都不带偏置：不写"该 HOLD"也不写"该动"，方法归主人的交易指令。
     *
     * @param wakeWindowText 唤醒时段"HH:mm-HH:mm"，null=全天——节奏行按它说真话，模板与事实不许打架
     */
    public String platformTemplate(AgentLang lang, String intervalCode, String symbols,
                                   TraderRiskConfig risk, String wakeWindowText) {
        // 超过 10 对，Map.of 装不下
        return prompts.get(lang, "trader.template", Map.ofEntries(
                Map.entry("rhythm", rhythmText(lang, intervalCode, wakeWindowText)),
                Map.entry("symbols", symbols),
                // 纪律 4 只留一行指针指向文末收尾块，标记同源
                Map.entry("mark", prompts.get(lang, "trader.mark.conclusion")),
                // 单轮模型调用上限：模型知道预算才会主动并行取数
                Map.entry("maxCalls", TraderWakeupRunner.MAX_MODEL_CALLS),
                Map.entry("leverageMin", risk.leverageMin()),
                Map.entry("leverageMax", risk.leverageMax()),
                Map.entry("marginPctMin", plain(risk.marginPctMin())),
                Map.entry("marginPctMax", plain(risk.marginPctMax())),
                Map.entry("positionRule", positionRule(lang, risk)),
                Map.entry("hedgeRule", hedgeRule(lang, risk))));
    }

    /** 节奏行按时段说真话：全天=每根K线醒一次；有时段就把"只在时段内醒、时段外例行与警报都停、手动例外"写进同一句 */
    private String rhythmText(AgentLang lang, String intervalCode, String wakeWindowText) {
        return wakeWindowText == null
                ? prompts.get(lang, "trader.rhythm.allDay", Map.of("interval", intervalCode))
                : prompts.get(lang, "trader.rhythm.window",
                        Map.of("window", wakeWindowText, "interval", intervalCode));
    }

    private static String plain(java.math.BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private String positionRule(AgentLang lang, TraderRiskConfig risk) {
        return prompts.get(lang, risk.allowMultiPosition()
                ? "trader.positionRule.multi" : "trader.positionRule.single");
    }

    private String hedgeRule(AgentLang lang, TraderRiskConfig risk) {
        return prompts.get(lang, risk.allowHedge() ? "trader.hedgeRule.allow" : "trader.hedgeRule.deny");
    }
}
