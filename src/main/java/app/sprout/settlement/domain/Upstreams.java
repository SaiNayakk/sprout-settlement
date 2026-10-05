package app.sprout.settlement.domain;

import app.sprout.settlement.config.SettlementProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * The services the back office talks to: the order service (Sprout's books of the day, and settling
 * clients), the ledger (booking the money) and Sprout Bank (moving it). Each call has a hard deadline;
 * no answer, or a 5xx, is {@link Unreachable} and the step is tried again on the next round.
 */
@Component
public class Upstreams {

    static final Duration DEADLINE = Duration.ofSeconds(5);

    public static class Unreachable extends RuntimeException {
        public Unreachable(String what, Throwable cause) {
            super(what, cause);
        }
    }

    public record Reply(int status, JsonNode body) {
        public boolean ok() {
            return status / 100 == 2;
        }

        public String code() {
            return body == null ? "" : body.path("code").asText();
        }
    }

    public record Leg(String account, String side, long paise) {}

    private final SettlementProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    public Upstreams(SettlementProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    // ── the order service ────────────────────────────────────────────────────

    public JsonNode summary(LocalDate day) {
        return ok("oms", send("oms", HttpRequest.newBuilder(URI.create(props.oms().url() + "/internal/v1/settlements/" + day + "/summary"))
                .header("X-Service-Key", props.serviceKey()).GET()));
    }

    public void shortages(LocalDate day, String settlementId, List<Map<String, Object>> shortages) {
        ok("oms", post(props.oms().url() + "/internal/v1/settlements/" + day + "/shortages",
                Map.of("settlementId", settlementId, "shortages", shortages)));
    }

    public void complete(LocalDate day, String settlementId, List<Map<String, Object>> deliveries) {
        ok("oms", post(props.oms().url() + "/internal/v1/settlements/" + day + "/complete",
                Map.of("settlementId", settlementId, "deliveries", deliveries)));
    }

    private Reply post(String url, Object body) {
        return send("oms", HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .header("X-Service-Key", props.serviceKey()).POST(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    // ── the ledger ───────────────────────────────────────────────────────────

    public Reply book(String key, String description, String reference, List<Leg> legs) {
        Map<String, Object> body = Map.of("idempotencyKey", key, "description", description, "reference", reference,
                "postings", legs.stream().map(l -> Map.of("account", l.account(), "side", l.side(), "amount", Money.rupees(l.paise()))).toList());
        return send("ledger", HttpRequest.newBuilder(URI.create(props.ledger().url() + "/v1/journal-entries"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    // ── the bank ─────────────────────────────────────────────────────────────

    public Reply pay(String payeeVpa, long paise, String reference) {
        Map<String, Object> body = Map.of("payeeVpa", payeeVpa, "amount", Money.rupees(paise), "reference", reference);
        return send("bank", HttpRequest.newBuilder(URI.create(props.bank().url() + "/partner/v1/payouts"))
                .header("Content-Type", "application/json").header("X-Partner-Key", props.bank().partnerKey())
                .POST(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    /** What arrived in Sprout's bank account carrying this reference. */
    public long received(String reference) {
        JsonNode body = ok("bank", send("bank", HttpRequest.newBuilder(URI.create(props.bank().url() + "/partner/v1/transactions?reference="
                + URLEncoder.encode(reference, StandardCharsets.UTF_8))).header("X-Partner-Key", props.bank().partnerKey()).GET()));
        long total = 0;
        for (JsonNode t : body.path("transactions")) {
            if (t.path("direction").asText().equals("IN")) {
                total += Money.paise(t.path("amount").asText());
            }
        }
        return total;
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private JsonNode ok(String what, Reply r) {
        if (!r.ok()) {
            throw new IllegalStateException(what + " refused: " + r.status() + " " + r.code());
        }
        return r.body();
    }

    private Reply send(String what, HttpRequest.Builder req) {
        try {
            HttpResponse<String> res = http.sendAsync(req.timeout(DEADLINE).build(), HttpResponse.BodyHandlers.ofString())
                    .get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (res.statusCode() >= 500) {
                throw new Unreachable(what + " answered " + res.statusCode(), null);
            }
            JsonNode body = res.body() == null || res.body().isBlank() ? null : json.readTree(res.body());
            return new Reply(res.statusCode(), body);
        } catch (Unreachable e) {
            throw e;
        } catch (TimeoutException e) {
            throw new Unreachable(what + " didn't answer within " + DEADLINE.toMillis() + " ms", e);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new Unreachable(what + " unreachable: " + cause.getClass().getSimpleName(), cause);
        }
    }

    String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
