package com.mawai.wiibagent.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词词表两门语言的<b>全量</b>对齐。
 * <p>
 * 与 {@code PromptI18nTest} 分工：那边按手工名单钉语义（约束句不丢、英文非回落）——名单天然有盲区，
 * 新增 key 忘了登记就没人管；这边按 key 集合全量钉存在性——en 缺一条 key 不会报错，
 * 只会让英文用户的那条提示词静默回落中文，这里让它变成红。
 * <p>
 * 直接扫 yml 文件（父目录名=语言码，层级压成点分 key），与 messages/ 的 ParityTest 同款做法。
 */
class PromptParityTest {

    /** {{name}} 占位符，与 LangBundle 同一套写法 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    private static final Map<AgentLang, Map<String, String>> PROMPTS = load();

    private static Map<AgentLang, Map<String, String>> load() {
        Map<AgentLang, Map<String, String>> byLang = new EnumMap<>(AgentLang.class);
        try {
            for (Resource file : new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:prompts/*/*.yml")) {
                String[] parts = file.getURL().getPath().split("/");
                AgentLang lang = AgentLang.find(parts[parts.length - 2]).orElseThrow();
                YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
                yaml.setResources(file);
                Objects.requireNonNull(yaml.getObject()).forEach((k, v) ->
                        byLang.computeIfAbsent(lang, x -> new HashMap<>()).put(k.toString(), v.toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return byLang;
    }

    @Test
    void 两门语言key完全对齐() {
        Map<String, String> zh = PROMPTS.get(AgentLang.ZH);
        Map<String, String> en = PROMPTS.get(AgentLang.EN);
        assertThat(zh).as("中文词表一条都没扫到").isNotEmpty();

        Set<String> missingEn = new TreeSet<>(zh.keySet());
        missingEn.removeAll(en.keySet());
        Set<String> missingZh = new TreeSet<>(en.keySet());
        missingZh.removeAll(zh.keySet());
        assertThat(missingEn).as("英文词表缺这些 key，英文用户会静默回落中文提示词").isEmpty();
        assertThat(missingZh).as("中文词表缺这些 key（zh 是回落语言，缺了 get 当场抛）").isEmpty();
    }

    /** 占位符也要对齐：一边写了 {{name}} 另一边漏了，缺值那侧渲染时会当场抛 */
    @Test
    void 两门语言的占位符一一对应() {
        Map<String, String> en = PROMPTS.get(AgentLang.EN);
        PROMPTS.get(AgentLang.ZH).forEach((k, zhText) ->
                assertThat(placeholders(en.getOrDefault(k, "")))
                        .as("%s 的占位符两门语言对不上", k)
                        .isEqualTo(placeholders(zhText)));
    }

    /**
     * 工具描述只有词表这一份：主代码里每个 @Tool 都得有 tool.<工具名>，缺了那只工具一建就抛。
     * 直接扫编译出来的主代码，不另抄工具清单。
     */
    @Test
    void 代码里的每个工具在词表里都有描述() throws IOException {
        String mainClasses = PromptCatalog.class.getProtectionDomain().getCodeSource().getLocation().toString();
        MetadataReaderFactory readers = new SimpleMetadataReaderFactory();
        Set<String> tools = new TreeSet<>();
        for (Resource file : new PathMatchingResourcePatternResolver().getResources(mainClasses + "**/*.class")) {
            for (MethodMetadata method : readers.getMetadataReader(file).getAnnotationMetadata()
                    .getAnnotatedMethods(Tool.class.getName())) {
                tools.add((String) Objects.requireNonNull(
                        method.getAnnotationAttributes(Tool.class.getName())).get("name"));
            }
        }
        // 探测端点用的空工具，不走词表
        tools.remove("noop");
        assertThat(tools).as("一个工具都没扫到").isNotEmpty();

        for (AgentLang lang : AgentLang.values()) {
            Set<String> missing = new TreeSet<>(tools);
            missing.removeIf(name -> PROMPTS.get(lang).containsKey("tool." + name));
            assertThat(missing).as("%s 词表缺这些工具的描述", lang.code()).isEmpty();
        }
    }

    private static Set<String> placeholders(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
