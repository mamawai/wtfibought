package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.MinesGameStateDTO;
import com.mawai.wiibcommon.dto.MinesStatusDTO;

import java.math.BigDecimal;

public interface MinesService {

    MinesStatusDTO getStatus(Long userId);

    MinesGameStateDTO bet(Long userId, BigDecimal amount);

    MinesGameStateDTO reveal(Long userId, int cell);

    MinesGameStateDTO cashout(Long userId);
}
