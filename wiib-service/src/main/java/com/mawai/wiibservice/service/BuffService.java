package com.mawai.wiibservice.service;

import com.mawai.wiibcommon.dto.BuffStatusDTO;
import com.mawai.wiibcommon.dto.UserBuffDTO;

import java.math.BigDecimal;

public interface BuffService {

    BuffStatusDTO getStatus(Long userId);

    UserBuffDTO draw(Long userId);

    BigDecimal getDiscountRate(Long userId, Long buffId);

    void markUsed(Long buffId);
}
