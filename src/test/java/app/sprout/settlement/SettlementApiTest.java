package app.sprout.settlement;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import app.sprout.settlement.domain.BackOffice;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The back office on a real Postgres, against stand-ins for the order service's books, a faithful
 * ledger (balanced, idempotent, never below zero) and Sprout Bank. The clearing corporation's news is
 * sent the way it sends it: signed events.
 */
@Testcontainers
@SpringBootTest(properties = {"spring.config.name=settlement", "sprout.settlement.every=1h"})
@AutoConfigureMockMvc
class SettlementApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final String SECRET = "dev-only-clearing-webhook-secret";
    static final String CLIENT = UUID.randomUUID().toString();

    // the order service's books of each day
    static final Map<String, Map<String, Object>> BOOKS = new ConcurrentHashMap<>();
    static final List<String> SHORTAGES = new CopyOnWriteArrayList<>();
    static final Map<String, String> COMPLETED = new ConcurrentHashMap<>();    // tradeDate -> deliveries json
    // the ledger and the bank
    static final Map<String, Long> LEDGER = new ConcurrentHashMap<>();
    static final Map<String, String> ENTRIES = new ConcurrentHashMap<>();
    static final Map<String, Long> PAID = new ConcurrentHashMap<>();            // reference -> paise Sprout paid out
    static final Map<String, Long> ARRIVED = new ConcurrentHashMap<>();         // reference -> paise paid to Sprout
    static final AtomicReference<String> BANK_REFUSES = new AtomicReference<>();
    static final AtomicInteger DAYS = new AtomicInteger();
    static final HttpServer STANDINS = standIns();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + STANDINS.getAddress().getPort();
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=settlement");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("sprout.settlement.oms.url", () -> base);
        r.add("sprout.settlement.ledger.url", () -> base);
        r.add("sprout.settlement.bank.url", () -> base);
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-07T04:00:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.SETTLEMENT_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired BackOffice office;

    String day;
    String id;

    @BeforeEach
    void aTradeDate() {
        day = "2026-11-" + String.format("%02d", DAYS.incrementAndGet());
        id = UUID.randomUUID().toString();
        BANK_REFUSES.set(null);
        // Sprout's customers start with enough at the bank and the ledger agrees
        LEDGER.merge("sprout:bank", 1_000_000_00L, Long::sum);
        LEDGER.merge("customer:" + CLIENT + ":cash", 1_000_000_00L, Long::sum);
    }

    /** Sprout's books say the day came to this; the ledger holds the clearing balances the fills booked. */
    void books(long payable, long receivable, long bought, long sold) {
        BOOKS.put(day, Map.of("tradeDate", day, "payable", rupees(payable), "receivable", rupees(receivable), "closeOuts", "0.00",
                "unpostedLedgerEntries", 0, "lines", List.of(Map.of("clientCode", CLIENT, "symbol", "HARBOR", "bought", bought, "sold", sold))));
        LEDGER.merge("sprout:clearing-payable", payable, Long::sum);
        LEDGER.merge("customer:" + CLIENT + ":cash", -payable, Long::sum);
        LEDGER.merge("sprout:clearing-receivable", receivable, Long::sum);
        LEDGER.merge("customer:" + CLIENT + ":unsettled", receivable, Long::sum);
    }

    Map<String, Object> settlement(String direction, long amount, long bought, long sold, String lineStatus) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", id);
        s.put("member", "sprout");
        s.put("tradeDate", day);
        s.put("status", "AWAITING_FUNDS");
        s.put("fundsDirection", direction);
        s.put("fundsAmount", rupees(amount));
        if (direction.equals("PAY")) {
            s.put("payTo", "clearing@sproutbank");
        }
        s.put("payReference", "scc-" + id);
        s.put("lines", List.of(Map.of("clientCode", CLIENT, "symbol", "HARBOR", "bought", bought, "sold", sold, "net", bought - sold,
                "status", lineStatus)));
        return s;
    }

    ResultActions event(String type, Map<String, Object> settlement) throws Exception {
        String body = JSON.writeValueAsString(Map.of("eventId", UUID.randomUUID().toString(), "type", type, "settlement", settlement,
                "occurredAt", "2026-10-07T04:00:00Z"));
        return mvc.perform(post("/internal/v1/clearing-events").contentType(MediaType.APPLICATION_JSON)
                .header("X-Clearing-Signature", "sha256=" + sign(body)).content(body));
    }

    String stage() {
        return office.settlement(id).status();
    }

    // ── settling ─────────────────────────────────────────────────────────────

    @Test
    void aDayThatOwesIsCheckedPaidBookedThenSettledForClients() throws Exception {
        books(10_000_00, 1_200_00, 10, 6);
        long bankBefore = LEDGER.get("sprout:bank");
        event("OBLIGATION", settlement("PAY", 8_800_00, 10, 6, "PENDING")).andExpect(status().isNoContent());
        assertThat(stage()).isEqualTo("FUNDS_SETTLED");
        assertThat(PAID.get("scc-" + id)).isEqualTo(8_800_00);
        assertThat(LEDGER.get("sprout:bank")).isEqualTo(bankBefore - 8_800_00);
        assertThat(COMPLETED).doesNotContainKey(day);
        event("SETTLED", settlement("PAY", 8_800_00, 10, 6, "DELIVERED")).andExpect(status().isNoContent());
        assertThat(stage()).isEqualTo("COMPLETED");
        assertThat(COMPLETED.get(day)).contains("\"quantity\":4");
        office.advanceAll();
        assertThat(PAID.get("scc-" + id)).as("paid once").isEqualTo(8_800_00);
        mvc.perform(get("/v1/settlements").header("X-Service-Key", "dev-only-service-key")).andExpect(status().isOk())
                .andExpect(MATCHES_CONTRACT);
    }

    @Test
    void aDayThatIsOwedIsBookedOnlyOnceTheMoneyIsThere() throws Exception {
        books(999_95, 15_520_00, 1, 50);
        event("OBLIGATION", settlement("RECEIVE", 14_520_05, 1, 50, "PENDING"));
        assertThat(stage()).as("checked, but the money hasn't come").isEqualTo("CHECKED");
        long bankBefore = LEDGER.get("sprout:bank");
        long receivableBefore = LEDGER.get("sprout:clearing-receivable");
        long payableBefore = LEDGER.get("sprout:clearing-payable");
        ARRIVED.put("scc-" + id, 14_520_05L);
        event("SETTLED", settlement("RECEIVE", 14_520_05, 1, 50, "DELIVERED"));
        assertThat(stage()).isEqualTo("COMPLETED");
        assertThat(LEDGER.get("sprout:bank")).isEqualTo(bankBefore + 14_520_05);
        assertThat(LEDGER.get("sprout:clearing-receivable")).as("the day's receivable is cleared").isEqualTo(receivableBefore - 15_520_00);
        assertThat(LEDGER.get("sprout:clearing-payable")).isEqualTo(payableBefore - 999_95);
    }

    @Test
    void figuresThatDontMatchTheBooksAreABreakAndNothingIsPaid() throws Exception {
        books(10_000_00, 0, 10, 0);
        event("OBLIGATION", settlement("PAY", 10_500_00, 10, 0, "PENDING"));
        assertThat(stage()).isEqualTo("BREAK");
        assertThat(office.settlement(id).breakReason()).contains("10500.00").contains("10000.00");
        assertThat(PAID).doesNotContainKey("scc-" + id);
        office.advanceAll();
        assertThat(stage()).as("a break stays stopped").isEqualTo("BREAK");
    }

    @Test
    void sharesThatDontMatchAreABreakToo() throws Exception {
        books(10_000_00, 0, 10, 0);
        event("OBLIGATION", settlement("PAY", 10_000_00, 11, 1, "PENDING"));
        assertThat(stage()).isEqualTo("BREAK");
        assertThat(office.settlement(id).breakReason()).contains("bought/sold");
    }

    @Test
    void aShortDeliveryIsChargedToTheClientBeforeChecking() throws Exception {
        BOOKS.put(day, Map.of("tradeDate", day, "payable", "1200.00", "receivable", "1000.00", "closeOuts", "1200.00",
                "unpostedLedgerEntries", 0, "lines", List.of(Map.of("clientCode", CLIENT, "symbol", "HARBOR", "bought", 0, "sold", 5))));
        LEDGER.merge("sprout:clearing-receivable", 1000_00L, Long::sum);
        LEDGER.merge("customer:" + CLIENT + ":unsettled", 1000_00L, Long::sum);
        LEDGER.merge("customer:" + CLIENT + ":dues", 1200_00L, Long::sum);
        LEDGER.merge("sprout:clearing-payable", 1200_00L, Long::sum);
        Map<String, Object> s = settlement("PAY", 200_00, 0, 5, "SHORT");
        s.put("lines", List.of(Map.of("clientCode", CLIENT, "symbol", "HARBOR", "bought", 0, "sold", 5, "net", -5, "status", "SHORT",
                "shortQuantity", 5, "closeOutValue", "1200.00")));
        event("OBLIGATION", s);
        assertThat(SHORTAGES).anyMatch(x -> x.contains(id) && x.contains("1200.00"));
        assertThat(stage()).isEqualTo("FUNDS_SETTLED");
        assertThat(PAID.get("scc-" + id)).isEqualTo(200_00);
    }

    @Test
    void ifTheBankWontPayTheSettlementWaitsAndPaysLater() throws Exception {
        books(5_000_00, 0, 5, 0);
        BANK_REFUSES.set("INSUFFICIENT_BALANCE");
        event("OBLIGATION", settlement("PAY", 5_000_00, 5, 0, "PENDING"));
        assertThat(stage()).isEqualTo("CHECKED");
        BANK_REFUSES.set(null);
        office.advanceAll();
        assertThat(stage()).isEqualTo("FUNDS_SETTLED");
        assertThat(PAID.get("scc-" + id)).isEqualTo(5_000_00);
    }

    @Test
    void forgedNewsIsRefusedAndRepeatsAreIgnored() throws Exception {
        books(1_000_00, 0, 1, 0);
        mvc.perform(post("/internal/v1/clearing-events").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Clearing-Signature", "sha256=" + "0".repeat(64)).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        String body = JSON.writeValueAsString(Map.of("eventId", UUID.randomUUID().toString(), "type", "OBLIGATION",
                "settlement", settlement("PAY", 1_000_00, 1, 0, "PENDING"), "occurredAt", "2026-10-07T04:00:00Z"));
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/internal/v1/clearing-events").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Clearing-Signature", "sha256=" + sign(body)).content(body)).andExpect(status().isNoContent());
        }
        assertThat(PAID.get("scc-" + id)).isEqualTo(1_000_00);
        mvc.perform(get("/v1/settlements")).andExpect(status().isUnauthorized());
    }

    // ── the stand-ins ────────────────────────────────────────────────────────

    static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    static boolean asset(String account) {
        return account.equals("sprout:bank") || account.equals("sprout:clearing-receivable") || account.endsWith(":dues");
    }

    static HttpServer standIns() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/internal/v1/settlements/", ex -> {
                String[] parts = ex.getRequestURI().getPath().split("/");   // /internal/v1/settlements/{day}/{what}
                String d = parts[4];
                String what = parts[5];
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                switch (what) {
                    case "summary" -> reply(ex, 200, BOOKS.get(d));
                    case "shortages" -> {
                        SHORTAGES.add(body);
                        reply(ex, 204, null);
                    }
                    case "complete" -> {
                        COMPLETED.putIfAbsent(d, body);
                        reply(ex, 204, null);
                    }
                    default -> reply(ex, 404, Map.of());
                }
            });
            s.createContext("/v1/journal-entries", SettlementApiTest::ledger);
            s.createContext("/partner/v1/payouts", ex -> {
                JsonNode p = JSON.readTree(ex.getRequestBody().readAllBytes());
                if (BANK_REFUSES.get() != null) {
                    reply(ex, 422, Map.of("code", BANK_REFUSES.get()));
                    return;
                }
                boolean fresh = PAID.putIfAbsent(p.path("reference").asText(), paise(p.path("amount").asText())) == null;
                reply(ex, fresh ? 201 : 200, Map.of("status", "COMPLETED"));
            });
            s.createContext("/partner/v1/transactions", ex -> {
                String ref = ex.getRequestURI().getQuery().replace("reference=", "");
                List<Map<String, Object>> txns = new ArrayList<>();
                if (ARRIVED.containsKey(ref)) {
                    txns.add(Map.of("direction", "IN", "amount", rupees(ARRIVED.get(ref)), "reference", ref));
                }
                reply(ex, 200, Map.of("transactions", txns));
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static synchronized void ledger(HttpExchange ex) throws IOException {
        JsonNode e = JSON.readTree(ex.getRequestBody().readAllBytes());
        String key = e.path("idempotencyKey").asText();
        String canonical = JSON.writeValueAsString(e.path("postings"));
        if (ENTRIES.containsKey(key)) {
            reply(ex, ENTRIES.get(key).equals(canonical) ? 200 : 409, Map.of());
            return;
        }
        Map<String, Long> delta = new LinkedHashMap<>();
        long net = 0;
        for (JsonNode p : e.path("postings")) {
            long amount = paise(p.path("amount").asText());
            boolean debit = p.path("side").asText().equals("DEBIT");
            net += debit ? amount : -amount;
            String account = p.path("account").asText();
            delta.merge(account, asset(account) == debit ? amount : -amount, Long::sum);
        }
        if (net != 0) {
            reply(ex, 422, Map.of("code", "UNBALANCED"));
            return;
        }
        for (var d : delta.entrySet()) {
            if (LEDGER.getOrDefault(d.getKey(), 0L) + d.getValue() < 0) {
                reply(ex, 422, Map.of("code", "INSUFFICIENT_FUNDS"));
                return;
            }
        }
        delta.forEach((a, d) -> LEDGER.merge(a, d, Long::sum));
        ENTRIES.put(key, canonical);
        reply(ex, 201, Map.of());
    }

    static String rupees(long p) {
        return p / 100 + "." + String.format("%02d", p % 100);
    }

    static long paise(String r) {
        return Long.parseLong(r.replace(".", ""));
    }

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }
}
