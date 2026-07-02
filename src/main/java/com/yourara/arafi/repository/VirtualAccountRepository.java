package com.yourara.arafi.repository;

import com.yourara.arafi.model.VirtualAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, UUID> {
    Optional<VirtualAccount> findByBankAccountNumber(String bankAccountNumber);
}