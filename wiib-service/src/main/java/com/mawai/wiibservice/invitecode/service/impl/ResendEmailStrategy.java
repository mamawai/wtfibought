package com.mawai.wiibservice.invitecode.service.impl;

import com.mawai.wiibservice.invitecode.service.EmailStrategy;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ResendEmailStrategy implements EmailStrategy {

    @Value("${resend.api-key}")
    private String resendApiKey;

    @Value("${resend.from-email}")
    private String resendFromEmail;

    private Resend resend;

    @PostConstruct
    public void init() {
        this.resend = new Resend(resendApiKey);
    }

    @Override
    public void send(String targetEmail, String subject, String content) {
        CreateEmailOptions params = CreateEmailOptions.builder()
                .from(resendFromEmail)
                .to(targetEmail)
                .subject(subject)
                .html(content)
                .build();
        try {
            CreateEmailResponse data = resend.emails().send(params);
            log.info("邮件发送成功: id={}", data.getId());
        } catch (ResendException e) {
            log.error("邮件发送失败: {}", e.getMessage(), e);
            throw new RuntimeException("邮件发送失败", e);
        }
    }
}
