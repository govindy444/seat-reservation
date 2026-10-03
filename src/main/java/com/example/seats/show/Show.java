package com.example.seats.show;

import java.time.Instant;
import java.util.UUID;

public record Show(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats, Instant createdAt) {
}
