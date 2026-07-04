package com.yourara.arafi.service;

import com.yourara.arafi.model.App;
import com.yourara.arafi.model.LedgerEntry;
import com.yourara.arafi.model.Payout;
import com.yourara.arafi.repository.AppRepository;
import com.yourara.arafi.repository.LedgerEntryRepository;
import com.yourara.arafi.repository.PayoutRepository;
import com.yourara.arafi.security.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PayoutService {

    private final PayoutRepository payoutRepository;
    private final AppRepository appRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional
    public Payout requestPayout(UUID appId, BigDecimal amount, String bankAccountNumber, String bankCode, String bankName) {
        App app = appRepository.findById(appId)
                .orElseThrow(() -> new IllegalArgumentException("App workspace not found."));

        String accountNumber = (bankAccountNumber != null && !bankAccountNumber.isBlank()) ? bankAccountNumber : app.getPayoutBankAccountNumber();
        String code = (bankCode != null && !bankCode.isBlank()) ? bankCode : app.getPayoutBankCode();
        String name = (bankName != null && !bankName.isBlank()) ? bankName : app.getPayoutBankName();

        if (accountNumber == null || accountNumber.isBlank() || code == null || code.isBlank()) {
            throw new IllegalArgumentException("Payout destination bank account and bank code must be specified or registered on the app workspace.");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payout amount must be greater than zero.");
        }

        // Validate available balance
        BigDecimal availableBalance = ledgerEntryRepository.computeBalanceForApp(appId);
        if (availableBalance.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient ledger balance. Available balance: " + availableBalance + " NGN.");
        }

        String mode = RequestContext.getMode() != null ? RequestContext.getMode() : "test";

        // 1. Save Payout request in PENDING state
        Payout payout = Payout.builder()
                .appId(appId)
                .amount(amount)
                .bankAccountNumber(accountNumber)
                .bankCode(code)
                .bankName(name)
                .status("PENDING")
                .mode(mode)
                .build();
        payoutRepository.save(payout);

        // 2. Immediately DEBIT the ledger to lock the funds and prevent double-spending
        LedgerEntry debitEntry = LedgerEntry.builder()
                .appId(appId)
                .bankAccountNumber(accountNumber)
                .amount(amount.negate()) // Negative amount represents debit
                .entryType("DEBIT")
                .webhookEventId("payout_" + payout.getId())
                .build();
        ledgerEntryRepository.save(debitEntry);

        return payout;
    }

    public List<Payout> getPayoutsForApp(UUID appId) {
        return payoutRepository.findByAppId(appId);
    }
}
