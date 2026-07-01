# cloud-itonami-isco-8332

Open Occupation Blueprint for **ISCO-08 8332**: Heavy Truck and Lorry Drivers.

This repository designs a forkable OSS business for an independent owner-operator heavy-truck driver: an ADAS/co-pilot assist system performs the physical driving-assist and dock-coupling work under a governor-gated actor, so the operator keeps their own duty-hour and delivery records instead of renting a closed fleet-telematics SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here an ADAS/co-pilot assist system performs lane-keeping, collision-avoidance and loading-dock coupling assist under an actor that proposes
actions and an independent **Freight Driver Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
highway driving, backing near loading docks, or hours-of-service edge cases) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
freight order + route plan + duty-hour log
        |
        v
Route Advisor -> Freight Driver Governor -> drive/deliver, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `8332`). Required capabilities:

- :robotics
- :telemetry
- :identity
- :dmn
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Reference implementation

`src/freight_driving/{store,governor}.cljc` is a minimal but real
implementation of the Core Contract above (pure cljc, no external deps):

- `freight-driving.store` — `Store` protocol + `MemStore`: routes, loads,
  deliveries, duty-hours entries. A delivery/duty-hours entry can only be
  recorded against a registered load on a registered route (load
  provenance).
- `freight-driving.governor` — `FreightDriverGovernor`: `assess` gates a
  proposal against the load/duty-hours env. Hard invariants force `:hold`
  (no load, direct-write instead of `:propose`, or a `:duty-hours` entry
  whose hours — added to the load's already-logged hours — exceed
  `max-daily-hours` at below `:high` safety-class); an hours-of-service
  overage always requires `:high`+ safety-class and thus
  `:human-approval` — it can never be auto-approved, only recorded and
  escalated; low-confidence proposals also escalate.

```bash
clojure -M:test   # 9 tests, 16 assertions, green
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation) —
the 7th `cloud-itonami-isco-*` occupation to reach that tier, after
`cloud-itonami-isco-6112`, `-2221`, `-7126`, `-4321`, `-9312` and `-5322`
(ADR-2607012000).

## License

AGPL-3.0-or-later.
