package com.yourara.arafi.repository;

import com.yourara.arafi.model.ProductTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductTransactionRepository extends JpaRepository<ProductTransaction, UUID> {
    List<ProductTransaction> findByAppId(UUID appId);
    Optional<ProductTransaction> findByNombaReference(String nombaReference);
    Optional<ProductTransaction> findByVirtualAccountNumber(String virtualAccountNumber);
}
