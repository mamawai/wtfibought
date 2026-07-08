package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.SettlementDTO;
import com.mawai.wiibcommon.entity.Settlement;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 结算Controller
 */
@Tag(name = "结算接口")
@RestController
@RequestMapping("/api/settlement")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    @GetMapping("/pending")
    @Operation(summary = "获取待结算列表")
    public Result<List<SettlementDTO>> getPendingSettlements(@CurrentUserId Long userId) {
        List<Settlement> settlements = settlementService.getPendingSettlements(userId);

        List<SettlementDTO> dtoList = settlements.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        return Result.ok(dtoList);
    }

    private SettlementDTO toDTO(Settlement settlement) {
        SettlementDTO dto = new SettlementDTO();
        BeanUtils.copyProperties(settlement, dto);
        return dto;
    }
}
