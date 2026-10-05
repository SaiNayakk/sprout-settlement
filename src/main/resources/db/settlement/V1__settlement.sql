-- Each settlement the clearing corporation tells Sprout about, and how far Sprout has got with it.
CREATE TABLE settlements (
    id                 text PRIMARY KEY,           -- the clearing corporation's settlement id
    trade_date         date NOT NULL,
    status             text NOT NULL CHECK (status IN ('RECEIVED', 'CHECKED', 'FUNDS_SETTLED', 'COMPLETED', 'BREAK')),
    funds_direction    text NOT NULL CHECK (funds_direction IN ('PAY', 'RECEIVE', 'NONE')),
    funds_paise        bigint NOT NULL,
    pay_to             text,
    pay_reference      text NOT NULL,
    payable_paise      bigint,                     -- by Sprout's books, fixed when checked
    receivable_paise   bigint,
    obligation         text NOT NULL,              -- the clearing corporation's settlement, as last sent
    settled            boolean NOT NULL DEFAULT false,
    break_reason       text,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);

CREATE INDEX settlements_unfinished ON settlements (trade_date) WHERE status NOT IN ('COMPLETED', 'BREAK');

CREATE TABLE clearing_events (
    event_id     uuid PRIMARY KEY,
    received_at  timestamptz NOT NULL
);
