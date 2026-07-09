package com.yourara.arafi.service;

import com.yourara.arafi.model.Coupon;
import com.yourara.arafi.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;

    @Transactional
    public Coupon createCoupon(UUID appId, String code, Long discountAmountKobo) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Coupon code cannot be empty.");
        }
        if (discountAmountKobo == null || discountAmountKobo <= 0) {
            throw new IllegalArgumentException("Discount amount must be greater than zero.");
        }

        // Ensure no duplicate active coupon code for this app workspace
        couponRepository.findByAppIdAndCodeAndActiveTrue(appId, code.toUpperCase().trim())
                .ifPresent(c -> {
                    throw new IllegalArgumentException("An active coupon with this code already exists in this workspace.");
                });

        Coupon coupon = Coupon.builder()
                .appId(appId)
                .code(code.toUpperCase().trim())
                .discountAmountKobo(discountAmountKobo)
                .active(true)
                .build();

        return couponRepository.save(coupon);
    }

    public List<Coupon> getCouponsForApp(UUID appId) {
        return couponRepository.findByAppId(appId);
    }
}
