package com.mawai.wiibsim.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarginRepayResult {

    private BigDecimal paidInterest;

    private BigDecimal paidPrincipal;

    private BigDecimal creditedToBalance;
}

