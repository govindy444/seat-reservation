package com.example.seats.show;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService service;

    public ShowController(ShowService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ShowResponse> create(@Valid @RequestBody CreateShowRequest req) {
        ShowResponse show = service.create(req);
        return ResponseEntity.created(URI.create("/shows/" + show.id())).body(show);
    }

    @GetMapping("/{id}")
    ShowResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}
