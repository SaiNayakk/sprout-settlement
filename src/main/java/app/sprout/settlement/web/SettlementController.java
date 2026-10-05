package app.sprout.settlement.web;

import app.sprout.settlement.config.SettlementProperties;
import app.sprout.settlement.domain.ApiException;
import app.sprout.settlement.domain.BackOffice;
import app.sprout.settlement.domain.BackOffice.Settlement;
import app.sprout.settlement.domain.ErrorCode;
import app.sprout.settlement.domain.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The back office's API (settlement-v1.yaml): the clearing corporation's callback, and settlements for operators. */
@RestController
public class SettlementController {

    private final BackOffice office;
    private final SettlementProperties props;
    private final ObjectMapper json;

    public SettlementController(BackOffice office, SettlementProperties props, ObjectMapper json) {
        this.office = office;
        this.props = props;
        this.json = json;
    }

    @PostMapping("/internal/v1/clearing-events")
    public ResponseEntity<Void> event(@RequestHeader(value = "X-Clearing-Signature", required = false) String signature,
                                      @RequestBody byte[] raw) throws Exception {
        String body = new String(raw, StandardCharsets.UTF_8);
        String expected = "sha256=" + hmac(props.clearing().webhookSecret(), body);
        if (signature == null || !MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.INVALID_SIGNATURE, "This isn't signed by the clearing corporation.");
        }
        JsonNode e = json.readTree(body);
        if (office.receive(UUID.fromString(e.path("eventId").asText()), e.path("type").asText(), e.path("settlement"))) {
            try {
                office.advance(e.path("settlement").path("id").asText());
            } catch (RuntimeException later) {
                // kept; the next round carries on from here
            }
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/settlements")
    public Map<String, Object> settlements(@RequestHeader(value = "X-Service-Key", required = false) String key) {
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), props.serviceKey().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Operators and Sprout services only.");
        }
        return Map.of("settlements", office.recent().stream().map(SettlementController::dto).toList());
    }

    static Map<String, Object> dto(Settlement s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("tradeDate", s.tradeDate().toString());
        m.put("status", s.status());
        m.put("fundsDirection", s.fundsDirection());
        m.put("fundsAmount", Money.rupees(s.fundsPaise()));
        if (s.payablePaise() != null) {
            m.put("payable", Money.rupees(s.payablePaise()));
            m.put("receivable", Money.rupees(s.receivablePaise()));
        }
        if (s.breakReason() != null) {
            m.put("breakReason", s.breakReason());
        }
        m.put("createdAt", s.createdAt().toString());
        m.put("updatedAt", s.updatedAt().toString());
        return m;
    }

    static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
