package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.VideoPokerGameStateDTO;
import com.mawai.wiibcommon.dto.VideoPokerStatusDTO;

import java.math.BigDecimal;
import java.util.List;

public interface VideoPokerService {

    VideoPokerStatusDTO getStatus(Long userId);

    VideoPokerGameStateDTO bet(Long userId, BigDecimal amount);

    VideoPokerGameStateDTO draw(Long userId, List<Integer> held);
}
