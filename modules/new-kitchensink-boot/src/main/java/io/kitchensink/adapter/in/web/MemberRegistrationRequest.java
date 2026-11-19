package io.kitchensink.adapter.in.web;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record MemberRegistrationRequest(
        @NotNull @Size(min = 1, max = 25) @Pattern(regexp = "[^0-9]*", message = "Must not contain numbers")
        String name,
        @NotNull @NotEmpty @Email
        String email,
        @NotNull @Size(min = 10, max = 12) @Digits(integer = 12, fraction = 0)
        String phoneNumber) {
}
