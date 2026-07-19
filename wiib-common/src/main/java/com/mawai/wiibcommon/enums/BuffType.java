package com.mawai.wiibcommon.enums;

import lombok.Getter;

@Getter
public enum BuffType {
    // 数值对齐 10000 初始资金经济体系：红包=旧值÷10，折扣区间收窄至 99~90 折
    // （旧库存 DISCOUNT_95/90 两名恰好仍取 0.95/0.90，历史未用券语义不变；85/80 落 valueOf catch 自然作废）
    DISCOUNT_99("交易99折", Rarity.COMMON, Category.DISCOUNT, 0.99),
    CASH_500("红包500", Rarity.COMMON, Category.CASH, 500),

    DISCOUNT_97("交易97折", Rarity.RARE, Category.DISCOUNT, 0.97),
    CASH_1000("红包1千", Rarity.RARE, Category.CASH, 1000),

    DISCOUNT_95("交易95折", Rarity.EPIC, Category.DISCOUNT, 0.95),
    CASH_2000("红包2千", Rarity.EPIC, Category.CASH, 2000),

    DISCOUNT_90("交易9折", Rarity.LEGENDARY, Category.DISCOUNT, 0.90),
    CASH_5000("红包5千", Rarity.LEGENDARY, Category.CASH, 5000);

    // 老股市已退，STOCK 档位撤除（历史 STOCK 记录仍可展示，valueOf 落 catch 返回无折扣）
    public enum Category { DISCOUNT, CASH }

    private final String displayName;
    private final Rarity rarity;
    private final Category category;
    private final double value;

    BuffType(String displayName, Rarity rarity, Category category, double value) {
        this.displayName = displayName;
        this.rarity = rarity;
        this.category = category;
        this.value = value;
    }

}
