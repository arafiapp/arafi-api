package com.yourara.arafi.repository;

import com.yourara.arafi.model.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, UUID> {
    List<Coupon> findByAppId(UUID appId);
    Optional<Coupon> findByAppIdAndCodeAndActiveTrue(UUID appId, String code);
}
