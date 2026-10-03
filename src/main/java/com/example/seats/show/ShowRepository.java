package com.example.seats.show;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertShow(Show show) {
        jdbc.update("""
                INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats(),
                Timestamp.from(show.createdAt()));
    }

    /** All seats in one round trip; ORDINALITY keeps the admin's ordering. */
    public void insertSeats(UUID showId, List<String> labels) {
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO seats (show_id, label, position)
                    SELECT ?, label, ord FROM unnest(?::text[]) WITH ORDINALITY AS t(label, ord)
                    """);
            ps.setObject(1, showId);
            ps.setArray(2, con.createArrayOf("text", labels.toArray()));
            return ps;
        });
    }

    public Optional<Show> findShow(UUID id) {
        return jdbc.query("""
                SELECT id, name, price_paise, per_user_limit, total_seats, created_at FROM shows WHERE id = ?
                """, (rs, i) -> new Show(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getLong("price_paise"),
                rs.getInt("per_user_limit"),
                rs.getInt("total_seats"),
                rs.getTimestamp("created_at").toInstant()), id).stream().findFirst();
    }

    public List<SeatView> findSeats(UUID showId) {
        return jdbc.query("SELECT label, status FROM seats WHERE show_id = ? ORDER BY position",
                (rs, i) -> new SeatView(rs.getString("label"), SeatStatus.valueOf(rs.getString("status"))), showId);
    }
}
