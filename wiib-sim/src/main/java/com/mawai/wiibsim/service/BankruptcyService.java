package com.mawai.wiibsim.service;

import java.time.LocalDate;

public interface BankruptcyService {

    void checkAndLiquidateAll();

    void resetBankruptUsers(LocalDate today);

    /** 立即破产（全仓穿仓专用）：标记破产+清空两钱包+全部持仓清算，次一交易日重置初始资金 */
    void bankruptNow(Long userId);

}

