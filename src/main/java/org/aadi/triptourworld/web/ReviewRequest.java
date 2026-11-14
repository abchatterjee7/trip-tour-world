package org.aadi.triptourworld.web;

import jakarta.validation.constraints.Size;

public record ReviewRequest(
        boolean approved,
        @Size(max = 2000, message = "feedback must be at most 2000 characters")
        String feedback) {
}
