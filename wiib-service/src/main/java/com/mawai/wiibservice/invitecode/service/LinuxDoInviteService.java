package com.mawai.wiibservice.invitecode.service;

import com.mawai.wiibservice.invitecode.dto.LinuxDoInviteRecordsVO;

public interface LinuxDoInviteService {
    boolean hasStock();
    void apply(String email);
    String verify(String token);
    LinuxDoInviteRecordsVO getRecentRecords(int limit);
    void click();
    long getClickCount();
}
