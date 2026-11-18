package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceTrade;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 复盘输出的解析：把【经验更新】后面的行翻成账本操作；【经验更新】下有没有单独一行「本期无更新」另外记。
 * 行首固定词、标记词只认当前语言那一套（词表 reviewer.op.*）；认不出的行丢掉。
 * 字段用「｜」或「|」分隔；全角空格当普通空格；行首的 "- " / "* " / "1. " 先剥掉。
 * 编号行从严，结构不对整行丢弃；新增行认不出的尾段只丢那一段。编号在不在本期、标记对不对得上条目种类归账本判
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExperienceOpParser {

    private static final Pattern SEP = Pattern.compile("\\s*[｜|]\\s*");
    private static final Pattern BULLET = Pattern.compile("^(?:[-*]\\s+|\\d+\\.\\s+)");
    /** #编号后面紧跟第一段；编号后面不许再跟数字（超长编号不认） */
    private static final Pattern ID_LINE = Pattern.compile("^#(\\d{1,18})(?!\\d)\\s*(\\S.*)$");
    private static final Pattern EXP_ID = Pattern.compile("^#(\\d{1,18})$");
    /** 仓位编号之间：顿号、逗号、空格 */
    private static final Pattern POSITION_SEP = Pattern.compile("[、,，\\s]+");
    /** 一个仓位编号，可以带方括号 */
    private static final Pattern POSITION = Pattern.compile("^\\[?(\\d{1,18})]?$");

    private final PromptCatalog prompts;

    /** 复盘输出的【经验更新】段：操作行，和有没有单独一行「本期无更新」 */
    public record ReviewParsed(List<ExperienceLedger.Op> ops, boolean noUpdates) {
    }

    /** 一段「标记词 + 仓位编号列表」：编号已去重 */
    private record Segment(String mark, Set<Long> positions) {
    }

    /** 复盘输出里【经验更新】之后的内容；没有更新段标记时两样都没有 */
    public ReviewParsed parse(String output, AgentLang lang) {
        String mark = prompts.get(lang, "reviewer.mark.updates");
        int idx = output.lastIndexOf(mark);
        if (idx < 0) {
            return new ReviewParsed(List.of(), false);
        }
        String text = output.substring(idx + mark.length());
        Words w = new Words(lang);
        return new ReviewParsed(ops(text, w), noUpdates(text, w));
    }

    /** 逐行解析经验更新 */
    public List<ExperienceLedger.Op> parseOps(String text, AgentLang lang) {
        return ops(text, new Words(lang));
    }

    private static List<ExperienceLedger.Op> ops(String text, Words w) {
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
                ExperienceLedger.Classify c = classify(Long.parseLong(m.group(1)), m.group(2), parts, w);
                if (c != null) {
                    ops.add(c);
                }
                continue;
            }
            String[] tokens = head.split("\\s+");
            String first = tokens[0];
            if (w.is(first, w.add)) {
                ExperienceLedger.Add add = add(line, tokens, parts, w);
                if (add != null) {
                    ops.add(add);
                }
            } else if (w.is(first, w.finding) && parts.length >= 2) {
                ops.add(new ExperienceLedger.Finding(parts[1].strip(), rest(parts, 2)));
            } else if (w.is(first, w.delete) && tokens.length >= 2 && parts.length >= 2) {
                Matcher d = EXP_ID.matcher(tokens[1]);
                if (d.matches()) {
                    ops.add(new ExperienceLedger.Delete(Long.parseLong(d.group(1)), parts[1].strip()));
                }
            }
        }
        return ops;
    }

    /**
     * 编号行：第一段跟在编号后面，之后用「｜」分段。标记段在前（「无」只能单独一个），说明段在后；
     * 说明段后面又出现标记段、同一笔出现在两段里、一个标记段都没有，整行丢弃。
     * 「守 无」这种标记段算一笔都没有；标记段全是这种的，按写了「无」算
     */
    private static ExperienceLedger.Classify classify(long id, String first, String[] parts, Words w) {
        List<String> segments = new ArrayList<>();
        segments.add(first.strip());
        for (int i = 1; i < parts.length; i++) {
            segments.add(parts[i].strip());
        }
        List<ExperienceLedger.Mark> marks = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        boolean none = false;
        boolean marked = false;
        boolean note = false;
        for (String s : segments) {
            if (w.is(s, w.none)) {
                if (note || none || !marks.isEmpty()) {
                    return null;
                }
                none = true;
                continue;
            }
            Segment seg = w.segment(s);
            if (seg == null) {
                note = true;
                continue;
            }
            if (note || none && !seg.positions().isEmpty()) {
                return null;
            }
            marked = true;
            for (long p : seg.positions()) {
                if (!seen.add(p)) {
                    return null;
                }
                marks.add(new ExperienceLedger.Mark(seg.mark(), p));
            }
        }
        if (!none && !marked) {
            return null;
        }
        return new ExperienceLedger.Classify(id, List.copyOf(marks));
    }

    /**
     * 新增 <类型|通用> <格子> <方向> ｜ 正文 ｜ 证据 [｜ 名下/守/破 编号…] [｜ 来源 #编号]。
     * 同一笔出现在两段里整行丢弃；认不出的尾段只丢那一段、记日志，这条新增照常
     */
    private static ExperienceLedger.Add add(String line, String[] tokens, String[] parts, Words w) {
        if (tokens.length < 4 || parts.length < 3) {
            return null;
        }
        String play = w.is(tokens[1], w.general) ? null : tokens[1].toUpperCase(Locale.ROOT);
        String slot = w.slot(tokens[2]);
        String dir = w.dir(tokens[3]);
        if (slot == null || dir == null) {
            return null;
        }
        List<ExperienceLedger.Mark> marks = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Long sourceId = null;
        for (int i = 3; i < parts.length; i++) {
            String s = parts[i].strip();
            Segment seg = w.segment(s);
            Long source = w.source(s);
            if (seg != null) {
                for (long p : seg.positions()) {
                    if (!seen.add(p)) {
                        return null;
                    }
                    marks.add(new ExperienceLedger.Mark(seg.mark(), p));
                }
            } else if (source != null) {
                sourceId = source;
            } else {
                log.info("[Review] 新增行认不出的尾段已忽略 segment={} line={}", s, line);
            }
        }
        return new ExperienceLedger.Add(play, slot, dir, parts[1].strip(), parts[2].strip(), List.copyOf(marks), sourceId);
    }

    /** 有没有单独一行「本期无更新」 */
    private static boolean noUpdates(String text, Words w) {
        return normalize(text).lines().map(ExperienceOpParser::clean).anyMatch(line -> w.is(line, w.noUpdates));
    }

    /** 仓位编号列表：至少一个，每个是 1–18 位数字（可以带方括号）；有一个不是就返回 null */
    private static Set<Long> positions(String text) {
        String t = text.strip();
        if (t.isEmpty()) {
            return null;
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String token : POSITION_SEP.split(t)) {
            Matcher m = POSITION.matcher(token);
            if (!m.matches()) {
                return null;
            }
            ids.add(Long.parseLong(m.group(1)));
        }
        return ids;
    }

    /** 全角空格当普通空格 */
    private static String normalize(String text) {
        return text.replace('　', ' ');
    }

    /** 去掉首尾空白与行首的列表符号 */
    private static String clean(String raw) {
        return BULLET.matcher(raw.strip()).replaceFirst("").strip();
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

    private static String key(String s) {
        return s.strip().toLowerCase(Locale.ROOT);
    }

    /** 当前语言的固定词表 */
    private final class Words {
        final String add;
        final String finding;
        final String delete;
        final String noUpdates;
        final String general;
        final String none;
        private final String source;
        /** 标记词（小写）→ AiTraderExperienceTrade.MARK_* */
        private final Map<String, String> marks;
        private final Map<String, String> slots;
        private final Map<String, String> dirs;

        Words(AgentLang lang) {
            add = prompts.get(lang, "reviewer.op.add");
            finding = prompts.get(lang, "reviewer.op.finding");
            delete = prompts.get(lang, "reviewer.op.delete");
            noUpdates = prompts.get(lang, "reviewer.op.noUpdates");
            general = prompts.get(lang, "reviewer.op.general");
            none = prompts.get(lang, "reviewer.op.none");
            source = key(prompts.get(lang, "reviewer.op.source"));
            marks = Map.of(
                    key(prompts.get(lang, "reviewer.op.under")), AiTraderExperienceTrade.MARK_UNDER,
                    key(prompts.get(lang, "reviewer.op.kept")), AiTraderExperienceTrade.MARK_KEPT,
                    key(prompts.get(lang, "reviewer.op.broken")), AiTraderExperienceTrade.MARK_BROKEN,
                    key(prompts.get(lang, "reviewer.op.miscited")), AiTraderExperienceTrade.MARK_MISCITED);
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
        }

        boolean is(String token, String word) {
            return key(token).equals(key(word));
        }

        String slot(String token) {
            return slots.get(key(token));
        }

        String dir(String token) {
            return dirs.get(key(token));
        }

        /**
         * 「标记词 + 编号列表」整段对得上才算，标记词和编号之间可以不留空格；对不上返回 null。
         * 标记词后面跟的是「无」，这一段一笔都没有
         */
        Segment segment(String s) {
            String lower = s.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> e : marks.entrySet()) {
                if (lower.startsWith(e.getKey())) {
                    String rest = s.substring(e.getKey().length());
                    if (is(rest, none)) {
                        return new Segment(e.getValue(), Set.of());
                    }
                    Set<Long> ids = positions(rest);
                    return ids == null ? null : new Segment(e.getValue(), ids);
                }
            }
            return null;
        }

        /** 「来源 #编号」整段对得上才算 */
        Long source(String s) {
            if (!s.toLowerCase(Locale.ROOT).startsWith(source)) {
                return null;
            }
            Matcher m = EXP_ID.matcher(s.substring(source.length()).strip());
            return m.matches() ? Long.parseLong(m.group(1)) : null;
        }
    }
}
