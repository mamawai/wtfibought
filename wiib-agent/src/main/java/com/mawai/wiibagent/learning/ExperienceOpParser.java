package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceVerdict;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 复盘输出的解析：把【经验更新】后面的行、复审输出的行翻成账本操作。
 * 行首固定词只认当前语言那一套（词表 reviewer.op.* / reviewer.audit.*）；认不出的行丢掉。
 * 字段用「｜」或「|」分隔；全角空格当普通空格；行首的 "- " / "* " / "1. " 先剥掉。
 */
@Component
@RequiredArgsConstructor
public class ExperienceOpParser {

    private static final Pattern SEP = Pattern.compile("\\s*[｜|]\\s*");
    private static final Pattern BULLET = Pattern.compile("^(?:[-*]\\s+|\\d+\\.\\s+)");
    private static final Pattern ID_LINE = Pattern.compile("^#(\\d{1,18})\\s*(\\S+)\\s*(.*)$");
    private static final Pattern DELETE_ID = Pattern.compile("^#(\\d{1,18})$");
    private static final Pattern SOURCE_ID = Pattern.compile("#(\\d{1,18})(?!\\d)");

    private final PromptCatalog prompts;

    /** 复审输出：结论 + 拆分出来的新增（带来源） */
    public record AuditParsed(List<ExperienceLedger.Audit> audits, List<ExperienceLedger.Add> adds) {
    }

    /** 复盘输出里【经验更新】之后的操作；没有更新段标记时为空 */
    public List<ExperienceLedger.Op> parse(String output, AgentLang lang) {
        String mark = prompts.get(lang, "reviewer.mark.updates");
        int idx = output.lastIndexOf(mark);
        if (idx < 0) {
            return List.of();
        }
        return parseOps(output.substring(idx + mark.length()), lang);
    }

    /** 逐行解析经验更新；审计词开头的行在这里不认，新增不认来源 */
    public List<ExperienceLedger.Op> parseOps(String text, AgentLang lang) {
        Words w = new Words(lang);
        List<ExperienceLedger.Op> ops = new ArrayList<>();
        for (String raw : normalize(text).split("\\R")) {
            String line = clean(raw);
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = SEP.split(line);
            String head = parts[0].strip();
            Matcher m = ID_LINE.matcher(head);
            if (m.matches()) {
                String verdict = w.verdict(m.group(2));
                if (verdict != null && m.group(3).isBlank()) {
                    ops.add(new ExperienceLedger.Verdict(Long.parseLong(m.group(1)), verdict, rest(parts, 1)));
                }
                continue;
            }
            String[] tokens = head.split("\\s+");
            String first = tokens[0];
            if (w.is(first, w.add)) {
                ExperienceLedger.Add add = add(tokens, parts, w, false);
                if (add != null) {
                    ops.add(add);
                }
            } else if (w.is(first, w.finding) && parts.length >= 2) {
                ops.add(new ExperienceLedger.Finding(parts[1].strip(), rest(parts, 2)));
            } else if (w.is(first, w.delete) && tokens.length >= 2 && parts.length >= 2) {
                Matcher d = DELETE_ID.matcher(tokens[1]);
                if (d.matches()) {
                    ops.add(new ExperienceLedger.Delete(Long.parseLong(d.group(1)), parts[1].strip()));
                }
            }
        }
        return ops;
    }

    public AuditParsed parseAudit(String output, AgentLang lang) {
        Words w = new Words(lang);
        List<ExperienceLedger.Audit> audits = new ArrayList<>();
        List<ExperienceLedger.Add> adds = new ArrayList<>();
        for (String raw : normalize(output).split("\\R")) {
            String line = clean(raw);
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = SEP.split(line);
            String head = parts[0].strip();
            Matcher m = ID_LINE.matcher(head);
            if (m.matches()) {
                String kind = w.audit(m.group(2));
                if (kind == null || !m.group(3).isBlank()) {
                    continue;
                }
                long id = Long.parseLong(m.group(1));
                if (ExperienceLedger.AUDIT_REVISE.equals(kind)) {
                    if (parts.length >= 2) {
                        audits.add(new ExperienceLedger.Audit(id, kind, parts[1].strip(), rest(parts, 2)));
                    }
                } else {
                    audits.add(new ExperienceLedger.Audit(id, kind, null, rest(parts, 1)));
                }
                continue;
            }
            String[] tokens = head.split("\\s+");
            if (w.is(tokens[0], w.add)) {
                ExperienceLedger.Add add = add(tokens, parts, w, true);
                if (add != null && add.sourceId() != null) {
                    adds.add(add);
                }
            }
        }
        return new AuditParsed(audits, adds);
    }

    /** 全角空格当普通空格 */
    private static String normalize(String text) {
        return text.replace('　', ' ');
    }

    /** 去掉首尾空白与行首的列表符号 */
    private static String clean(String raw) {
        return BULLET.matcher(raw.strip()).replaceFirst("").strip();
    }

    /** 新增 <类型|通用> <格子> <方向> ｜ 正文 ｜ 证据 [｜ 来源 #id]；withSource=false 时来源一律不认 */
    private static ExperienceLedger.Add add(String[] tokens, String[] parts, Words w, boolean withSource) {
        if (tokens.length < 4 || parts.length < 3) {
            return null;
        }
        String play = w.is(tokens[1], w.general) ? null : tokens[1].toUpperCase(Locale.ROOT);
        String slot = w.slot(tokens[2]);
        String dir = w.dir(tokens[3]);
        if (slot == null || dir == null) {
            return null;
        }
        Long sourceId = null;
        for (int i = 3; withSource && i < parts.length; i++) {
            Matcher s = SOURCE_ID.matcher(parts[i]);
            if (parts[i].strip().toLowerCase(Locale.ROOT).startsWith(w.source.toLowerCase(Locale.ROOT)) && s.find()) {
                sourceId = Long.parseLong(s.group(1));
            }
        }
        return new ExperienceLedger.Add(play, slot, dir, parts[1].strip(), parts[2].strip(), sourceId);
    }

    private static String rest(String[] parts, int from) {
        if (parts.length <= from) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (!sb.isEmpty()) {
                sb.append(" ｜ ");
            }
            sb.append(parts[i].strip());
        }
        return sb.toString();
    }

    /** 当前语言的固定词表 */
    private final class Words {
        final String add;
        final String finding;
        final String delete;
        final String general;
        final String source;
        private final Map<String, String> verdicts;
        private final Map<String, String> slots;
        private final Map<String, String> dirs;
        private final Map<String, String> audits;

        Words(AgentLang lang) {
            add = prompts.get(lang, "reviewer.op.add");
            finding = prompts.get(lang, "reviewer.op.finding");
            delete = prompts.get(lang, "reviewer.op.delete");
            general = prompts.get(lang, "reviewer.op.general");
            source = prompts.get(lang, "reviewer.op.source");
            verdicts = Map.of(
                    key(prompts.get(lang, "reviewer.op.support")), AiTraderExperienceVerdict.SUPPORT,
                    key(prompts.get(lang, "reviewer.op.oppose")), AiTraderExperienceVerdict.OPPOSE,
                    key(prompts.get(lang, "reviewer.op.untested")), AiTraderExperienceVerdict.UNTESTED,
                    key(prompts.get(lang, "reviewer.op.kept")), AiTraderExperienceVerdict.SUPPORT,
                    key(prompts.get(lang, "reviewer.op.broken")), AiTraderExperienceVerdict.OPPOSE);
            slots = Map.of(
                    key(prompts.get(lang, "reviewer.op.slot.entry")), AiTraderExperience.SLOT_ENTRY,
                    key(prompts.get(lang, "reviewer.op.slot.failDef")), AiTraderExperience.SLOT_FAIL_DEF,
                    key(prompts.get(lang, "reviewer.op.slot.failHandle")), AiTraderExperience.SLOT_FAIL_HANDLE,
                    key(prompts.get(lang, "reviewer.op.slot.profitHandle")), AiTraderExperience.SLOT_PROFIT_HANDLE,
                    key(prompts.get(lang, "reviewer.op.slot.risk")), AiTraderExperience.SLOT_RISK,
                    key(prompts.get(lang, "reviewer.op.slot.exec")), AiTraderExperience.SLOT_EXEC);
            dirs = Map.of(
                    key(prompts.get(lang, "reviewer.op.dir.opportunity")), AiTraderExperience.DIR_OPPORTUNITY,
                    key(prompts.get(lang, "reviewer.op.dir.restriction")), AiTraderExperience.DIR_RESTRICTION,
                    key(prompts.get(lang, "reviewer.op.dir.execution")), AiTraderExperience.DIR_EXECUTION);
            audits = Map.of(
                    key(prompts.get(lang, "reviewer.audit.keep")), ExperienceLedger.AUDIT_KEEP,
                    key(prompts.get(lang, "reviewer.audit.revise")), ExperienceLedger.AUDIT_REVISE,
                    key(prompts.get(lang, "reviewer.audit.split")), ExperienceLedger.AUDIT_SPLIT,
                    key(prompts.get(lang, "reviewer.audit.retire")), ExperienceLedger.AUDIT_RETIRE);
        }

        boolean is(String token, String word) {
            return key(token).equals(key(word));
        }

        String verdict(String token) {
            return verdicts.get(key(token));
        }

        String slot(String token) {
            return slots.get(key(token));
        }

        String dir(String token) {
            return dirs.get(key(token));
        }

        String audit(String token) {
            return audits.get(key(token));
        }

        private static String key(String s) {
            return s.strip().toLowerCase(Locale.ROOT);
        }
    }
}
