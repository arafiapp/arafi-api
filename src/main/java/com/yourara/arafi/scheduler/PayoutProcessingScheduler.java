package com.yourara.arafi.scheduler;

import com.yourara.arafi.model.LedgerEntry;
import com.yourara.arafi.model.Payout;
import com.yourara.arafi.repository.LedgerEntryRepository;
import com.yourara.arafi.repository.PayoutRepository;
import com.yourara.arafi.security.RequestContext;
import com.yourara.arafi.service.NombaClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PayoutProcessingScheduler {

    private final PayoutRepository payoutRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final NombaClientService nombaClientService;

    @Scheduled(fixedDelay = 10000) // Processes payouts every 10 seconds
    @Transactional
    public void processPendingPayouts() {
        List<Payout> pendingPayouts = payoutRepository.findByStatus("PENDING");

        if (pendingPayouts.isEmpty()) {
            return;
        }

        System.out.println("Payout Engine: Processing " + pendingPayouts.size() + " pending payouts...");

        for (Payout payout : pendingPayouts) {
            try {
                // Set request context matching app ID and the environment mode of the payout request
                RequestContext.setContext(payout.getAppId(), payout.getMode());

                long amountKobo = payout.getAmount().multiply(BigDecimal.valueOf(100)).longValue();
                String transferRef = "tf_" + payout.getId().toString().substring(0, 15);

                Map<String, String> transferResult = nombaClientService.processTransfer(
                        payout.getBankCode(),
                        payout.getBankAccountNumber(),
                        amountKobo,
                        transferRef
                );

                payout.setProcessedAt(Instant.now());

                if ("success".equals(transferResult.get("status"))) {
                    payout.setStatus("SUCCESS");
                    payout.setNombaTransferId(transferResult.get("transferId"));
                    System.out.println("Payout Engine: Successfully settled payout ID " + payout.getId() 
                            + " for app: " + payout.getAppId() + " (Txn ID: " + payout.getNombaTransferId() + ")");
                } else {
                    String failureReason = transferResult.get("message");
                    payout.setStatus("FAILED");
                    payout.setFailureReason(failureReason);
                    System.err.println("Payout Engine: Payout ID " + payout.getId() + " failed: " + failureReason);

                    // Reversing CREDIT ledger entry to refund the developer's balance
                    LedgerEntry refundEntry = LedgerEntry.builder()
                            .appId(payout.getAppId())
                            .bankAccountNumber(payout.getBankAccountNumber())
                            .amount(payout.getAmount()) // Positive refund credit
                            .entryType("CREDIT")
                            .webhookEventId("payout_refund_" + payout.getId())
                            .build();
                    ledgerEntryRepository.save(refundEntry);
                    System.out.println("Payout Engine: Refunded " + payout.getAmount() + " NGN back to app " + payout.getAppId());
                }
            } catch (Exception e) {
                payout.setStatus("FAILED");
                payout.setFailureReason(e.getMessage());
                payout.setProcessedAt(Instant.now());

                // Refund on exception
                LedgerEntry refundEntry = LedgerEntry.builder()
                        .appId(payout.getAppId())
                        .bankAccountNumber(payout.getBankAccountNumber())
                        .amount(payout.getAmount())
                        .entryType("CREDIT")
                        .webhookEventId("payout_refund_err_" + payout.getId())
                        .build();
                ledgerEntryRepository.save(refundEntry);
                System.err.println("Payout Engine: Error processing payout " + payout.getId() + ": " + e.getMessage() + ". Refunded.");
            } finally {
                RequestContext.clear();
                payoutRepository.save(payout);
            }
        }
    }
}
