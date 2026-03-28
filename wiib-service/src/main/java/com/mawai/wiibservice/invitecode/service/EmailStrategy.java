package com.mawai.wiibservice.invitecode.service;

public interface EmailStrategy {
    void send(String targetEmail, String subject, String content);
}
