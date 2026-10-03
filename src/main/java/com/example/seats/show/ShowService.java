package com.example.seats.show;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.example.seats.api.ApiException;

@Service
public class ShowService {

    private final ShowRepository repo;

    public ShowService(ShowRepository repo) {
        this.repo = repo;
    }

    /** Show + all its seats commit together, so a show is never visible with a partial seat map. */
    @Transactional
    public ShowResponse create(CreateShowRequest req) {
        if (new HashSet<>(req.seats()).size() != req.seats().size()) {
            throw ApiException.badRequest("seat labels must be unique");
        }
        Show show = new Show(UUID.randomUUID(), req.name(), req.pricePaise(), req.perUserLimitOrDefault(),
                req.seats().size(), Instant.now());
        repo.insertShow(show);
        repo.insertSeats(show.id(), req.seats());
        return ShowResponse.of(show, repo.findSeats(show.id()));
    }

    // REPEATABLE READ: the show row and seat rows come from one snapshot even across two statements.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShowResponse get(UUID id) {
        Show show = repo.findShow(id).orElseThrow(() -> ApiException.notFound("show"));
        return ShowResponse.of(show, repo.findSeats(id));
    }
}
