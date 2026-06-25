package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.BlackjackStatusDTO;
import com.mawai.wiibcommon.dto.ConvertResultDTO;
import com.mawai.wiibcommon.dto.GameStateDTO;

public interface BlackjackService {

    BlackjackStatusDTO getStatus(Long userId);

    GameStateDTO bet(Long userId, long amount);

    GameStateDTO hit(Long userId);

    GameStateDTO stand(Long userId);

    GameStateDTO doubleDown(Long userId);

    GameStateDTO split(Long userId);

    GameStateDTO insurance(Long userId);

    GameStateDTO forfeit(Long userId);

    ConvertResultDTO convert(Long userId, long amount);
}
