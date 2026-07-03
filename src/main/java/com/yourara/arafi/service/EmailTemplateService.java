package com.yourara.arafi.service;

import com.yourara.arafi.model.EmailTemplate;
import com.yourara.arafi.repository.EmailTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final EmailTemplateRepository emailTemplateRepository;

    @Transactional
    public EmailTemplate saveOrUpdateTemplate(UUID appId, String subject, String body) {
        EmailTemplate template = emailTemplateRepository.findByAppId(appId)
                .orElse(null);

        if (template == null) {
            template = EmailTemplate.builder()
                    .appId(appId)
                    .emailSubject(subject)
                    .emailBodyTemplate(body)
                    .build();
        } else {
            template.setEmailSubject(subject);
            template.setEmailBodyTemplate(body);
        }

        return emailTemplateRepository.save(template);
    }
}
