package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibsim.dto.PublicTradeDTO;
import com.mawai.wiibsim.dto.PublicTradeRow;
import com.mawai.wiibsim.mapper.PublicTradeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 全站成交时间线。交易细节照实给，交易者换成稳定假名。
 * 匿名边界：挡的是"顺着页面看出谁是谁"，不是密码学匿名（拿到盐可反查），模拟盘展示页够用。
 */
@Service
@RequiredArgsConstructor
public class PublicTradeService {

    /** 单页封顶，同 force-orders 那条口径 */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 假名取哈希前 6 位十六进制（16^6 ≈ 1677 万）。
     * 取 4 位看着更短，但 100 个用户就有约 7% 概率撞名，两个人显示成同一个假名，
     * 看页面的人会以为是同一个人连着下的单——那比长两位难看多了。
     */
    private static final int ALIAS_HEX_LEN = 6;

    /** 策略账户的用户名前缀，见 quant 侧 StrategyAccountRegistry */
    private static final String QUANT_USERNAME_PREFIX = "quant-";

    private final PublicTradeMapper publicTradeMapper;

    /**
     * 假名盐。换掉它会把全站假名整体重排（纯展示用，不影响任何数据），
     * 但同一次部署内必须稳定——用随机值启动，重启一次所有人假名就变了，"稳定假名"四个字就没了。
     */
    @Value("${trading.alias-salt:wiib-public-trade}")
    private String aliasSalt;

    /** 全站成交分页（匿名）。symbol/kind 传 null 即不筛 */
    public Page<PublicTradeDTO> pageAll(String symbol, String kind, int pageNum, int pageSize) {
        int safeNum = Math.max(pageNum, 1);
        int safeSize = Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        long total = publicTradeMapper.countAll(symbol, kind);
        List<PublicTradeRow> rows = publicTradeMapper.selectPage(symbol, kind, safeSize, (safeNum - 1) * safeSize);
        return toPage(rows, safeNum, safeSize, total);
    }

    /**
     * 指定用户的成交分页。调用方必须先过隐私门控——判断留在 controller，
     * "看自己永远放行"要知道当前登录人是谁。
     */
    public Page<PublicTradeDTO> pageByUser(Long userId, int pageNum, int pageSize) {
        int safeNum = Math.max(pageNum, 1);
        int safeSize = Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        long total = publicTradeMapper.countByUser(userId);
        List<PublicTradeRow> rows = publicTradeMapper.selectPageByUser(userId, safeSize, (safeNum - 1) * safeSize);
        return toPage(rows, safeNum, safeSize, total);
    }

    private Page<PublicTradeDTO> toPage(List<PublicTradeRow> rows, int pageNum, int pageSize, long total) {
        Page<PublicTradeDTO> page = new Page<>(pageNum, pageSize, total);
        page.setRecords(rows.stream().map(this::toDto).toList());
        return page;
    }

    /** 行→对外 DTO。userId/username 就在这一步被丢掉，往下再也拿不到 */
    private PublicTradeDTO toDto(PublicTradeRow row) {
        PublicTradeDTO dto = new PublicTradeDTO();
        dto.setKind(row.getKind());
        dto.setTradeId(row.getTradeId());
        dto.setAlias(alias(row.getUserId()));
        dto.setIsAi(row.getUsername() != null && row.getUsername().startsWith(QUANT_USERNAME_PREFIX));
        dto.setSymbol(row.getSymbol());
        dto.setOrderSide(row.getOrderSide());
        dto.setQuantity(row.getQuantity());
        dto.setFilledPrice(row.getFilledPrice());
        dto.setFilledAmount(row.getFilledAmount());
        dto.setCreatedAt(row.getCreatedAt());
        return dto;
    }

    /** userId + 盐 的 SHA-256 前 6 位十六进制。同一用户恒定，跨用户基本不会撞 */
    private String alias(Long userId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((userId + ":" + aliasSalt).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, ALIAS_HEX_LEN);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这儿说明运行环境坏了，没有合理的降级
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
