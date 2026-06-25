package com.mawai.wiibsim.service;

import java.time.LocalDate;

public interface BankruptcyService {

    void checkAndLiquidateAll();

    void resetBankruptUsers(LocalDate today);

    LocalDate nextTradingDay(LocalDate d);
}

