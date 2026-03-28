package com.mawai.wiibservice.invitecode.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LinuxDoInviteRecordsVO {
    private List<LinuxDoInviteRecordVO> list;
    private Long total;
}
