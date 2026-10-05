# sprout-settlement

Sprout's **settlement back office**. The [clearing corporation](https://github.com/SaiNayakk/sprout-clearing)
tells it what each trade date comes to and when it has settled; for each one it:

1. **checks the obligation against Sprout's own books** (the [order service](https://github.com/SaiNayakk/sprout-oms)'s summary of the day): the money, and every client's shares bought and sold. A mismatch is a **break**: the settlement stops with nothing paid, for a person to look at;
2. charges any client who didn't deliver shares they sold for the close-out;
3. **moves the money** through Sprout Bank (pays the clearing corporation, or sees its payment arrive) and books the same movement in the [ledger](https://github.com/SaiNayakk/sprout-ledger), so the ledger's `sprout:bank` always equals Sprout's account at the bank;
4. once settled, tells the order service, which makes clients' sale proceeds cash and their shares delivered.

Every step is recorded as it completes and every external call is idempotent (payments carry the
clearing corporation's reference; ledger entries fixed keys), so it resumes safely after any failure.
Callbacks are verified against the clearing corporation's signature and applied once.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`settlement-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/settlement-v1.yaml)
in sprout-contracts. It runs inside the **money** host.

`./mvnw verify` runs the tests on a real Postgres (Docker needed) against stand-ins for the order
service, a faithful ledger and the bank.

## License

MIT
