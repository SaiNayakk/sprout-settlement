package app.sprout.settlement.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.settlement} in settlement.yml. */
@ConfigurationProperties("sprout.settlement")
public record SettlementProperties(Duration every, String serviceKey, Clearing clearing, Url oms, Url ledger, Bank bank) {

    public record Clearing(String webhookSecret) {}

    public record Url(String url) {}

    public record Bank(String url, String partnerKey) {}
}
