package com.mawai.wiibservice.invitecode.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LinuxDoInviteRecordVO {
    private String maskedIdentifier;
    private LocalDateTime claimedAt;
}
