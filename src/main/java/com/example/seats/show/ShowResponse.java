package com.example.seats.show;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        Counts counts,
        List<SeatView> seats,
        Instant createdAt) {

    public record Counts(int available, int held, int confirmed, boolean reconciled) {
    }

    /** Counts are derived from the same seat snapshot we return, so they can never disagree with it. */
    public static ShowResponse of(Show show, List<SeatView> seats) {
        int available = 0, held = 0, confirmed = 0;
        for (SeatView s : seats) {
            switch (s.status()) {
                case available -> available++;
                case held -> held++;
                case confirmed -> confirmed++;
            }
        }
        boolean reconciled = available + held + confirmed == show.totalSeats();
        return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats(),
                new Counts(available, held, confirmed, reconciled), seats, show.createdAt());
    }
}
