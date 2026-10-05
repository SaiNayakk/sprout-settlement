package app.sprout.settlement.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SettlementBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
