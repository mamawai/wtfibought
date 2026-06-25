package com.mawai.wiibsim.service;

import com.mawai.wiibsim.service.model.MarginRepayResult;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface MarginAccountService {

    int normalizeLeverageMultiple(Integer leverageMultiple);

    void addLoanPrincipal(Long userId, BigDecimal principalDelta);

    MarginRepayResult applyCashInflow(Long userId, BigDecimal amount, String reason);

    void accrueDailyInterest(LocalDate today);
}

