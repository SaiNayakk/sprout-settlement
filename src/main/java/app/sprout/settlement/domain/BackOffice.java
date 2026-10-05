package app.sprout.settlement.domain;

import app.sprout.settlement.domain.Upstreams.Leg;
import app.sprout.settlement.domain.Upstreams.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Settles each trade date on Sprout's side: check, charge shortfalls, move and book the money, settle
 * clients. One step at a time, each recorded as it completes; every external call is idempotent, so a
 * step that failed half way is simply tried again.
 *
 * <p>Nothing is paid unless the clearing corporation's figures match Sprout's books exactly. A
 * mismatch is a break: the settlement stops, and a person decides.
 */
@Service
public class BackOffice {

    private static final Logger log = LoggerFactory.getLogger(BackOffice.class);

    public record Settlement(String id, LocalDate tradeDate, String status, String fundsDirection, long fundsPaise, String payTo,
                             String payReference, Long payablePaise, Long receivablePaise, JsonNode obligation, boolean settled,
                             String breakReason, Instant createdAt, Instant updatedAt) {}

    private final JdbcClient db;
    private final Clock clock;
    private final Upstreams up;
    private final ObjectMapper json;

    public BackOffice(JdbcClient db, Clock clock, Upstreams up, ObjectMapper json) {
        this.db = db;
        this.clock = clock;
        this.up = up;
        this.json = json;
    }

    // ── news from the clearing corporation ───────────────────────────────────

    /** Records an OBLIGATION or SETTLED event. Returns false if it was seen before. */
    public boolean receive(UUID eventId, String type, JsonNode s) {
        if (db.sql("INSERT INTO clearing_events (event_id, received_at) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(eventId, ts(clock.instant())).update() == 0) {
            return false;
        }
        Instant now = clock.instant();
        String body = write(s);
        db.sql("""
                        INSERT INTO settlements (id, trade_date, status, funds_direction, funds_paise, pay_to, pay_reference, obligation, settled,
                                                 created_at, updated_at)
                        VALUES (?, ?, 'RECEIVED', ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (id) DO UPDATE SET obligation = EXCLUDED.obligation, settled = settlements.settled OR EXCLUDED.settled,
                                                       updated_at = EXCLUDED.updated_at""")
                .params(s.path("id").asText(), LocalDate.parse(s.path("tradeDate").asText()), s.path("fundsDirection").asText(),
                        Money.paise(s.path("fundsAmount").asText()), s.path("payTo").asText(null), s.path("payReference").asText(), body,
                        type.equals("SETTLED"), ts(now), ts(now))
                .update();
        log.info("Clearing says {} for trade date {} ({})", type, s.path("tradeDate").asText(), s.path("id").asText());
        return true;
    }

    // ── settling ─────────────────────────────────────────────────────────────

    public int advanceAll() {
        List<String> open = db.sql("SELECT id FROM settlements WHERE status NOT IN ('COMPLETED', 'BREAK') ORDER BY trade_date")
                .query(String.class).list();
        int completed = 0;
        for (String id : open) {
            try {
                if (advance(id).status().equals("COMPLETED")) {
                    completed++;
                }
            } catch (Upstreams.Unreachable e) {
                log.info("Settlement {} waits: {}", id, e.getMessage());
            }
        }
        return completed;
    }

    public Settlement advance(String id) {
        Settlement s = settlement(id);
        if (s.status().equals("RECEIVED")) {
            check(s);
            s = settlement(id);
        }
        if (s.status().equals("CHECKED")) {
            moveMoney(s);
            s = settlement(id);
        }
        if (s.status().equals("FUNDS_SETTLED") && s.settled()) {
            up.complete(s.tradeDate(), s.id(), deliveries(s.obligation()));
            step(s.id(), "FUNDS_SETTLED", "COMPLETED");
            log.info("Trade date {} settled for Sprout's clients ({})", s.tradeDate(), s.id());
            s = settlement(id);
        }
        return s;
    }

    /** Charges short deliveries, then compares the clearing corporation's figures with Sprout's books. */
    private void check(Settlement s) {
        JsonNode cc = s.obligation();
        List<Map<String, Object>> shortages = new ArrayList<>();
        for (JsonNode l : cc.path("lines")) {
            if (l.path("status").asText().equals("SHORT")) {
                shortages.add(Map.of("clientCode", l.path("clientCode").asText(), "symbol", l.path("symbol").asText(),
                        "quantity", l.path("shortQuantity").asLong(), "closeOutValue", l.path("closeOutValue").asText()));
            }
        }
        if (!shortages.isEmpty()) {
            up.shortages(s.tradeDate(), s.id(), shortages);
        }
        JsonNode books = up.summary(s.tradeDate());
        if (books.path("unpostedLedgerEntries").asInt() > 0) {
            log.info("Settlement {} waits for the order service's books of {} to be posted", s.id(), s.tradeDate());
            return;
        }
        long payable = Money.paise(books.path("payable").asText());
        long receivable = Money.paise(books.path("receivable").asText());
        long ours = receivable - payable;
        long theirs = switch (s.fundsDirection()) {
            case "RECEIVE" -> s.fundsPaise();
            case "PAY" -> -s.fundsPaise();
            default -> 0;
        };
        String broken = null;
        if (ours != theirs) {
            broken = "Funds: the clearing corporation says " + signed(theirs) + ", Sprout's books say " + signed(ours) + ".";
        } else {
            broken = compareLines(cc.path("lines"), books.path("lines"));
        }
        if (broken != null) {
            db.sql("UPDATE settlements SET status = 'BREAK', break_reason = ?, updated_at = ? WHERE id = ? AND status = 'RECEIVED'")
                    .params(broken, ts(clock.instant()), s.id()).update();
            log.error("SETTLEMENT BREAK for trade date {} ({}): {} Nothing was paid.", s.tradeDate(), s.id(), broken);
            return;
        }
        db.sql("UPDATE settlements SET status = 'CHECKED', payable_paise = ?, receivable_paise = ?, updated_at = ? WHERE id = ? AND status = 'RECEIVED'")
                .params(payable, receivable, ts(clock.instant()), s.id()).update();
    }

    /** Every client's shares bought and sold, by the clearing corporation and by Sprout, must be the same. */
    private static String compareLines(JsonNode cc, JsonNode books) {
        Map<String, String> theirs = new TreeMap<>();
        for (JsonNode l : cc) {
            theirs.put(l.path("clientCode").asText() + " " + l.path("symbol").asText(), l.path("bought").asLong() + "/" + l.path("sold").asLong());
        }
        Map<String, String> ours = new TreeMap<>();
        for (JsonNode l : books) {
            ours.put(l.path("clientCode").asText() + " " + l.path("symbol").asText(), l.path("bought").asLong() + "/" + l.path("sold").asLong());
        }
        if (theirs.equals(ours)) {
            return null;
        }
        for (String k : theirs.keySet()) {
            if (!Objects.equals(theirs.get(k), ours.get(k))) {
                return "Shares for " + k + " (bought/sold): the clearing corporation says " + theirs.get(k) + ", Sprout's books say "
                        + ours.getOrDefault(k, "nothing") + ".";
            }
        }
        String extra = ours.keySet().stream().filter(k -> !theirs.containsKey(k)).findFirst().orElse("?");
        return "Shares for " + extra + ": Sprout's books have trades the clearing corporation doesn't.";
    }

    /** Pays the clearing corporation or sees its payment arrive, and books the same movement in the ledger. */
    private void moveMoney(Settlement s) {
        long payable = s.payablePaise();
        long receivable = s.receivablePaise();
        List<Leg> legs = new ArrayList<>();
        if (payable > 0) {
            legs.add(new Leg("sprout:clearing-payable", "DEBIT", payable));
        }
        if (receivable > 0) {
            legs.add(new Leg("sprout:clearing-receivable", "CREDIT", receivable));
        }
        switch (s.fundsDirection()) {
            case "PAY" -> {
                Reply paid = up.pay(s.payTo(), s.fundsPaise(), s.payReference());
                if (!paid.ok()) {
                    log.error("Sprout Bank refused to pay the clearing corporation ₹{} for {}: {} {}", Money.rupees(s.fundsPaise()), s.id(),
                            paid.status(), paid.code());
                    return;
                }
                legs.add(new Leg("sprout:bank", "CREDIT", s.fundsPaise()));
            }
            case "RECEIVE" -> {
                if (up.received(s.payReference()) < s.fundsPaise()) {
                    return;   // not arrived yet
                }
                legs.add(new Leg("sprout:bank", "DEBIT", s.fundsPaise()));
            }
            default -> { }
        }
        if (!legs.isEmpty()) {
            Reply booked = up.book("settlement-funds:" + s.id(), "Settlement of " + s.tradeDate() + " with the clearing corporation", s.id(), legs);
            if (!booked.ok()) {
                log.error("The ledger refused settlement {}: {} {}", s.id(), booked.status(), booked.code());
                return;
            }
        }
        step(s.id(), "CHECKED", "FUNDS_SETTLED");
        log.info("Settlement {}: {} ₹{} with the clearing corporation, booked", s.id(), s.fundsDirection().toLowerCase(),
                Money.rupees(s.fundsPaise()));
    }

    /** What the clearing corporation delivered to each client's demat account. */
    private static List<Map<String, Object>> deliveries(JsonNode cc) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode l : cc.path("lines")) {
            if (l.path("status").asText().equals("DELIVERED") && l.path("net").asLong() > 0) {
                out.add(Map.of("clientCode", l.path("clientCode").asText(), "symbol", l.path("symbol").asText(), "quantity", l.path("net").asLong()));
            }
        }
        return out;
    }

    // ── reading ──────────────────────────────────────────────────────────────

    public List<Settlement> recent() {
        return db.sql(SQL + " ORDER BY trade_date DESC LIMIT 50").query(this::row).list();
    }

    public Settlement settlement(String id) {
        return db.sql(SQL + " WHERE id = ?").param(id).query(this::row).single();
    }

    private void step(String id, String from, String to) {
        db.sql("UPDATE settlements SET status = ?, updated_at = ? WHERE id = ? AND status = ?").params(to, ts(clock.instant()), id, from).update();
    }

    private static final String SQL = """
            SELECT id, trade_date, status, funds_direction, funds_paise, pay_to, pay_reference, payable_paise, receivable_paise, obligation,
                   settled, break_reason, created_at, updated_at FROM settlements""";

    private Settlement row(ResultSet rs, int n) throws SQLException {
        JsonNode obligation;
        try {
            obligation = json.readTree(rs.getString("obligation"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new Settlement(rs.getString("id"), rs.getObject("trade_date", LocalDate.class), rs.getString("status"),
                rs.getString("funds_direction"), rs.getLong("funds_paise"), rs.getString("pay_to"), rs.getString("pay_reference"),
                rs.getObject("payable_paise", Long.class), rs.getObject("receivable_paise", Long.class), obligation, rs.getBoolean("settled"),
                rs.getString("break_reason"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static String signed(long paise) {
        return paise >= 0 ? "Sprout is owed ₹" + Money.rupees(paise) : "Sprout owes ₹" + Money.rupees(-paise);
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
