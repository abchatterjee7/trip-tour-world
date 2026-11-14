package org.aadi.triptourworld.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TripRequest(
        @NotBlank(message = "message must not be empty")
        @Size(max = 1500, message = "message must be at most 1500 characters")
        String message) {
}
