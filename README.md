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

## License

AGPL-3.0-or-later.
