package com.mawai.wiibsim.ledger;

import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Ledger} 的落位守卫。
 * <p>
 * 这个注解靠 Spring AOP 生效，而 Spring AOP 对某些位置<b>完全没反应且不报错</b>：
 * private 方法（CGLIB 子类覆写不了）、final 方法/final 类（同理）、static 方法（不走实例代理）。
 * 标错位置的后果是"注解在那儿摆着、切面永远不 push frame、流水静默落 UNKNOWN"——
 * 编译过、启动过、单测绿，只有线上账单上一片"未分类"才看得出来。事后没法补：balance_after 已成历史。
 * <p>
 * 所以这条测试反射扫全包，把这几种形态在测试期钉死。
 * <p>
 * <b>它管不到的两件事</b>：
 * <ol>
 *   <li>同类内部自调用（{@code this.doXxx()}）也让注解失效，但那要读字节码才知道，本测试识别不了。
 *       类内的事务段不标注解，用 {@link LedgerTx} 在代码里压标签；
 *       新加注解时仍需人工确认调用方是经代理（别的 bean）进来的。</li>
 *   <li>{@code findCandidateComponents} 只收<b>具体的独立类</b>，抽象类里的 {@code @Ledger} 扫不到。
 *       当前没有这种形态（注解全在 @Service 具体类上），真要往抽象基类上标得另想办法。</li>
 * </ol>
 */
class LedgerPlacementTest {

    private static final String SCAN_PACKAGE = "com.mawai.wiibsim";

    /**
     * 扫到的注解方法数下限。存在的唯一理由是防"扫描器本身坏了/包名改了→一个都没扫到→用例空转全绿"，
     * 那样这层守卫就白设了。故意留松（实际远多于此），不当注解清单用，加减注解不该动这个数。
     */
    private static final int MIN_EXPECTED = 1;

    @Test
    void 标了Ledger的方法不能是AOP拦不到的形态() {
        List<Method> annotated = findLedgerMethods();

        assertThat(annotated)
                .as("扫到的 @Ledger 方法数（少于 %d 说明扫描没生效，不是真的没标注）", MIN_EXPECTED)
                .hasSizeGreaterThanOrEqualTo(MIN_EXPECTED);

        List<String> bad = new ArrayList<>();
        for (Method m : annotated) {
            int mod = m.getModifiers();
            String where = m.getDeclaringClass().getSimpleName() + "#" + m.getName();
            if (Modifier.isPrivate(mod)) bad.add(where + " 是 private，CGLIB 覆写不了，注解是空操作");
            if (Modifier.isFinal(mod)) bad.add(where + " 是 final，CGLIB 覆写不了，注解是空操作");
            if (Modifier.isStatic(mod)) bad.add(where + " 是 static，不走实例代理，注解是空操作");
            if (Modifier.isFinal(m.getDeclaringClass().getModifiers())) {
                bad.add(where + " 所在类是 final，CGLIB 生不出代理，注解是空操作");
            }
        }

        assertThat(bad).as("@Ledger 标在了 Spring AOP 拦不到的位置").isEmpty();
    }

    /**
     * 注解只在 Spring 托管的 bean 上才有代理可织。标到一个 new 出来的普通类上同样静默失效，
     * 而这种错比可见性错更难看出来（方法是 public 的，看着没问题）。
     */
    @Test
    void 标了Ledger的方法必须在Spring托管的bean里() {
        List<String> bad = findLedgerMethods().stream()
                .map(Method::getDeclaringClass)
                .distinct()
                .filter(c -> !AnnotatedElementUtils.hasAnnotation(c, Component.class))
                .map(c -> c.getName() + " 不是 Spring bean（@Component/@Service 都没有），无代理可织")
                .toList();

        assertThat(bad).as("@Ledger 标在了非 Spring bean 上").isEmpty();
    }

    /**
     * 第二个 pointcut 用的是通配 {@code atomicDeductFundingFee*}，将来给 FuturesPositionMapper
     * 加第三个同前缀方法，它会<b>自动</b>落进切点：调用点若没配 markPositionFee，切面只打一行 WARN
     * 就静默跳过，钱扣了账没记。第一个 pointcut 有 {@code LedgerRowMapping.HANDLED_METHODS}
     * 那道反射守卫兜着，这条补上对应的那道，两边标准别不一致。
     * <p>
     * 用相等而不是包含：少了=方法被删/改名（切点跟着失效），多了=有人加了同前缀方法，
     * 两个方向都得报，报了就去 doChargeFundingFeeOne 那儿确认新调用点有没有 markPositionFee。
     */
    @Test
    void 新增同前缀的扣保证金方法必须显式过一遍切点约定() {
        Set<String> matched = java.util.Arrays.stream(FuturesPositionMapper.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("atomicDeductFundingFee"))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(matched)
                .as("落进 positionMarginMutation() 切点的方法集变了：每个都必须有调用点的 markPositionFee")
                .isEqualTo(Set.of("atomicDeductFundingFee", "atomicDeductFundingFeePartial"));
    }

    /** 扫全包的具体类，收集所有带 @Ledger 的声明方法 */
    private static List<Method> findLedgerMethods() {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        // 不用默认过滤器（那只认 @Component 等），这里要的是"包里所有具体类"
        provider.addIncludeFilter((TypeFilter) (reader, factory) -> true);

        List<Method> found = new ArrayList<>();
        for (var def : provider.findCandidateComponents(SCAN_PACKAGE)) {
            String name = def.getBeanClassName();
            if (name == null) continue;
            Class<?> clazz;
            try {
                // initialize=false：只读元数据，不触发静态初始化（有些类的 static 块会算表、连缓存）
                clazz = Class.forName(name, false, LedgerPlacementTest.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                throw new AssertionError("扫到了却加载不了的类，扫描范围有问题: " + name, e);
            }
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Ledger.class)) found.add(m);
            }
        }
        return found;
    }
}
