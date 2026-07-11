package com.yourara.arafi.controller;

import com.yourara.arafi.config.OpenApiConfig;
import com.yourara.arafi.model.Product;
import com.yourara.arafi.model.request.CreateProductCheckoutRequest;
import com.yourara.arafi.model.request.CreateProductRequest;
import com.yourara.arafi.model.response.ErrorResponse;
import com.yourara.arafi.security.RequestContext;
import com.yourara.arafi.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/products")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Products", description = "Endpoints for managing products and processing one-time e-commerce purchases")
public class ProductController {

    private final ProductService productService;

    @PostMapping
    @Operation(
            summary = "Create a product",
            description = "Creates a product for one-time payments in this workspace context. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> createProduct(@RequestBody CreateProductRequest request) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }
        try {
            Product product = productService.createProduct(appId, request);
            return ResponseEntity.ok(product);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping
    @Operation(
            summary = "List products",
            description = "Retrieves all products created in this workspace context. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> getProducts() {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }
        try {
            List<Product> products = productService.listProducts(appId);
            return ResponseEntity.ok(products);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Archive product",
            description = "Archives a product record. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> deleteProduct(@PathVariable UUID id) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }
        try {
            productService.deleteProduct(appId, id);
            return ResponseEntity.ok(Map.of("message", "Product successfully archived."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{id}/checkout")
    @Operation(
            summary = "Initialize a one-time product checkout session",
            description = "Generates a checkout URL redirect for a customer to complete a one-time product payment. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> checkoutProduct(@PathVariable UUID id, @RequestBody CreateProductCheckoutRequest request) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }
        try {
            Map<String, String> response = productService.createProductCheckout(appId, id, request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // --- Public Checkout Endpoints (No Bearer Key header required) ---

    @GetMapping("/public/checkout/{checkoutId}")
    @Operation(
            summary = "Get public checkout transaction details",
            description = "Public endpoint queried by Arafi Checkout UI to display invoice details (App name, product name, price)."
    )
    public ResponseEntity<?> getPublicCheckoutDetails(@PathVariable UUID checkoutId) {
        try {
            Map<String, Object> details = productService.publicGetCheckoutDetails(checkoutId);
            return ResponseEntity.ok(details);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/public/checkout/{checkoutId}/card-checkout")
    @Operation(
            summary = "Generate Nomba card checkout URL for product",
            description = "Creates a Nomba checkout restricted to cards for a product transaction."
    )
    public ResponseEntity<?> publicGenerateProductCardCheckout(@PathVariable UUID checkoutId) {
        try {
            Map<String, String> details = productService.publicGenerateCardCheckoutUrl(checkoutId);
            return ResponseEntity.ok(details);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/public/checkout/{checkoutId}/bank-transfer")
    @Operation(
            summary = "Allocate public virtual account bank details",
            description = "Public endpoint queried when user selects BANK_TRANSFER as payment method on public checkout."
    )
    public ResponseEntity<?> publicProvisionProductBankTransfer(@PathVariable UUID checkoutId) {
        try {
            Map<String, String> details = productService.publicProvisionProductBankTransfer(checkoutId);
            return ResponseEntity.ok(details);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/public/checkout/{checkoutId}/verify")
    @Operation(
            summary = "Verify public payment status",
            description = "Polled by public redirect checkout page to verify order completion (Card/Transfer)."
    )
    public ResponseEntity<?> publicVerifyProductCardCheckout(@PathVariable UUID checkoutId) {
        try {
            Map<String, String> verification = productService.publicVerifyProductCardCheckout(checkoutId.toString());
            return ResponseEntity.ok(verification);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/public/simulate-transfer")
    @Operation(
            summary = "Sandbox credit simulation",
            description = "Simulates an inbound bank transfer credit webhook event on a provisioned virtual account for testing."
    )
    public ResponseEntity<?> simulateVirtualAccountTransfer(
            @RequestParam String virtualAccountNumber,
            @RequestParam BigDecimal amount
    ) {
        try {
            productService.simulateProductVirtualAccountTransfer(virtualAccountNumber, amount);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Sandbox credit transfer successfully simulated."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }
}
