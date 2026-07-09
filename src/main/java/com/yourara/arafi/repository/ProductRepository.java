package com.yourara.arafi.repository;

import com.yourara.arafi.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {
    List<Product> findByAppIdAndActiveTrue(UUID appId);
    List<Product> findByAppId(UUID appId);
}
