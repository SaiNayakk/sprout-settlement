package app.sprout.settlement.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Moves unfinished settlements along every few seconds, so nothing depends on a callback arriving at the right moment. */
@Component
public class Rounds {

    private static final Logger log = LoggerFactory.getLogger(Rounds.class);

    private final BackOffice office;

    public Rounds(BackOffice office) {
        this.office = office;
    }

    @Scheduled(fixedDelayString = "${sprout.settlement.every:5s}")
    void round() {
        try {
            office.advanceAll();
        } catch (RuntimeException e) {
            log.warn("The settlement round didn't finish: {}", e.getMessage());
        }
    }
}
