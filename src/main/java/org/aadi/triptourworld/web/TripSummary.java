package org.aadi.triptourworld.web;

import org.aadi.triptourworld.persistence.TripRecord;

public record TripSummary(String id, String status, String query, String createdAt) {
    public static TripSummary of(TripRecord r) {
        return new TripSummary(r.threadId(), r.status().name(), r.userQuery(), r.createdAt());
    }
}
