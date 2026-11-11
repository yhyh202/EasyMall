package com.easymall;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class Requests {
    private Requests() {}
    public record Credentials(@NotBlank @Pattern(regexp="[a-zA-Z0-9_]{3,50}") String username,
                              @NotBlank @Pattern(regexp="[\\x21-\\x7E]{8,64}") String password) {}
    public record Category(@NotBlank @Size(max=80) String name) {}
    public record Product(@NotNull @Positive Long categoryId, @NotBlank @Size(max=150) String name,
                          @NotNull @Size(max=2000) String description,
                          @NotNull @DecimalMin("0.01") @DecimalMax("99999999.99") @Digits(integer=8,fraction=2) BigDecimal price,
                          @NotNull Boolean active) {}
    public record Address(@NotBlank @Size(max=80) String recipient,
                          @NotBlank @Pattern(regexp="[+0-9 -]{5,30}") String phone,
                          @NotBlank @Size(max=500) String detail, boolean isDefault) {}
    public record Cart(@NotNull @Positive Long productId, @Min(1) @Max(999) int quantity) {}
    public record Quantity(@Min(1) @Max(999) int quantity) {}
    public record Line(@NotNull @Positive Long productId,@Min(1) @Max(999) int quantity) {}
    public record Order(@NotNull @Positive Long addressId, @NotEmpty @Size(max=50) List<@NotNull @Valid Line> items, boolean clearCart) {}
    public record Stock(@Min(-1000000) @Max(1000000) int delta) {}
    public record Ship(@NotBlank @Size(max=100) String shippingNo) {}
}
