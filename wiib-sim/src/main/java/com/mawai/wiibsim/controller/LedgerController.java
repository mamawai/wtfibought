package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 资金账单。纯读，没有业务逻辑，所以直接吃 mapper 不再套一层 service（同 NotificationController）。
 * <p>
 * 只能查自己的：userId 一律从登录态取，请求参数不许出现能顶替它的入口
 * （由 LedgerControllerTest 的反射守卫钉着）。
 * 翻页契约：id 倒序最新在前，下一页传 beforeId，返回空数组即到底；
 * limit 服务端封顶 100，"返回条数 &lt; limit"不能当到底的判据。
 */
@Tag(name = "资金账单")
@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
public class LedgerController {

    /** 单页封顶。不卡死的话手搓一个 limit=999999 就能把一个用户的全部流水一次拉走 */
    private static final int MAX_LIMIT = 100;

    private final UserLedgerMapper ledgerMapper;

    @GetMapping
    // summary 里必须带上 30/100：前端作者读的是 /doc.html 而不是这个 javadoc，
    // 不知道有封顶就会按"返回条数 < limit 即到底"实现，传个 limit=500 时第一页就被误判成到底
    @Operation(summary = "资金流水（游标翻页，id 倒序；beforeId 不传取最新一页；"
            + "limit 默认 30、服务端封顶 100，返回空数组即到底）")
    public Result<List<UserLedger>> list(@CurrentUserId Long userId,
                                        @RequestParam(required = false) LedgerBizType bizType,
                                        @RequestParam(required = false) Long beforeId,
                                        @RequestParam(defaultValue = "30") int limit) {
        // bizType 收枚举而不是裸 String：乱传的类型名在 Spring 转换阶段就 400
        // （全局处理器接 MethodArgumentTypeMismatchException），不会安静地返个空列表让人以为真没流水
        String bizTypeName = bizType == null ? null : bizType.name();
        // 下限兜到 1：PG 对负数 LIMIT 直接报错，limit=-1 不兜就是一个手搓请求刷 500
        int safeLimit = Math.clamp(limit, 1, MAX_LIMIT);
        return Result.ok(ledgerMapper.selectByCursor(userId, bizTypeName, beforeId, safeLimit));
    }

    /**
     * 筛选下拉的选项。开这个接口而不是让前端硬编码一份，是接着 {@link LedgerBizType} 上
     * "前端不再维护一份映射"那句话——中文说法只在枚举里改一处。
     * <p>
     * 挂在 /api/ledger 下走同一套鉴权，省得为一个下拉去动全局放行名单。
     */
    @GetMapping("/biz-types")
    @Operation(summary = "全部流水类型（筛选下拉用；name 是筛选参数，label 中文名，group 分组）")
    public Result<List<BizTypeOption>> bizTypes() {
        return Result.ok(Arrays.stream(LedgerBizType.values())
                .map(t -> new BizTypeOption(t.name(), t.getLabel(), t.getGroup()))
                .toList());
    }

    /**
     * 下拉选项。必须平铺成这个形状——枚举默认序列化成裸字符串 "FUTURES_OPEN_MARGIN"，
     * 直接返 values() 的话 label 和 group 一个都到不了前端。
     */
    public record BizTypeOption(String name, String label, String group) {}
}
