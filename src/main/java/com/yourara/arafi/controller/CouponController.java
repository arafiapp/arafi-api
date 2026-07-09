package com.yourara.arafi.controller;

import com.yourara.arafi.config.OpenApiConfig;
import com.yourara.arafi.model.Coupon;
import com.yourara.arafi.model.request.CreateCouponRequest;
import com.yourara.arafi.model.response.ErrorResponse;
import com.yourara.arafi.security.RequestContext;
import com.yourara.arafi.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/coupons")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Coupons", description = "Endpoints for managing workspace subscription discounts and coupon campaigns")
public class CouponController {

    private final CouponService couponService;

    @PostMapping
    @Operation(
            summary = "Create a discount coupon code",
            description = "Creates a new coupon in the active workspace. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> createCoupon(@RequestBody CreateCouponRequest request) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }
        try {
            Coupon coupon = couponService.createCoupon(appId, request.getCode(), request.getDiscountAmountKobo());
            return ResponseEntity.ok(coupon);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping
    @Operation(
            summary = "List coupons",
            description = "Retrieves all coupon campaign records created in this workspace context. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> getCoupons() {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }
        try {
            List<Coupon> coupons = couponService.getCouponsForApp(appId);
            return ResponseEntity.ok(coupons);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }
}
