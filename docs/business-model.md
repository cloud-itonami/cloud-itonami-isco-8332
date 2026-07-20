# Business Model: Independent Freight Driving Operations

## Classification

- Repository: `cloud-itonami-isco-8332`
- ISCO-08: `8332`
- Occupation: Heavy Truck and Lorry Drivers
- Social impact: supply-reliability, road-safety, driver-wellbeing

## Customer

- shippers
- freight brokers
- small carriers

## Offer

- route planning
- load/delivery tracking
- duty-hour logging
- delivery-proof capture
- utilization reporting

## Revenue

- per-load fee
- monthly operations platform fee
- reporting package

## Trust Controls

- no driving-assist override without governor gate
- duty-hour logs cannot be edited, only appended
- route deviations near safety-critical zones require human sign-off

## Governor Decision Rule: `:freight-driver-governor`

Every gated action in this business — a driving-assist override, a load
drop-off, a route deviation — passes through a single decision point,
`:freight-driver-governor`. Its rule is not generic "human in the loop"; it is
shaped directly by this blueprint's three social-impact tags:

- **`:supply-reliability`** — the governor will not sign off a drop-off unless
  the load carries a fresh, per-delivery manifest. A manifest is granted at
  the depot/yard immediately before a run and is consumed the instant a load
  is delivered — it is **never a standing permit**. A driver cannot pre-load
  ten sign-offs and run the whole route unsupervised; each delivery re-enters
  the gate. This is what keeps the chain of custody (and therefore the
  reliability guarantee sold to shippers/brokers) intact end to end.
- **`:road-safety`** — driving-assist overrides and any route deviation that
  enters a safety-critical zone require the same governor sign-off, and
  neither is automatically approved by load value or delivery urgency. A
  rush/expedited load is worth more revenue but is **not** exempt from the
  gate — the governor applies the identical check to a 3x-priority parcel as
  to a routine one. Urgency is a business fact, not a safety override.
- **`:driver-wellbeing`** — duty-hour logs are append-only. The governor
  reads the running duty-hour ledger before granting a sign-off; it will
  reject a manifest (and therefore block the drop-off) if the driver is
  outside logged hours-of-service limits. Because the log can only be
  appended to, neither the driver nor the platform can retroactively
  manufacture rest time to push a load through.

**Approves:** a drop-off/override where (a) a manifest sign-off was taken at
the yard for that specific load, (b) the driver's duty-hour ledger is within
limits at sign-off time, and (c) any route deviation into a safety-critical
zone has a human sign-off attached.

**Rejects (and logs as a violation):** any drop-off attempted without a
current manifest — an *unmanifested load* — regardless of the load's value or
urgency, any driving-assist override without a gate check, and any
safety-critical route deviation lacking human sign-off.

## Human-Required Gap Referral (ADR-2607202500)

Some tasks sit outside anything `:freight-driver-governor` can ever approve
the robot for — not because a sign-off is missing, but because the
autonomous-driving stack is structurally not certified for that task at all
(e.g. a recurring manual-loading dock step at the yard, or a route/terrain
that isn't on any automation roadmap). When the proposing Advisor sets an
explicit `:human-required? true` ground-truth flag (with a `:gap` describing
the task), the governor returns a distinct `:human-required` decision — never
folded into the existing `:human-approval` sign-off gate, and never inferred
by the governor itself.

`:human-required` carries a **referral draft**, produced by the shared
`kotoba.occupation/human-gap-referral-draft` function (`kotoba-lang/
occupation`), naming which existing cloud-itonami staffing/matching actor a
human operator should carry the gap to, depending on the gap's shape:

- one-off + remote → `cloud-itonami-isic-8299` (independent contracted
  operator, task-based)
- on-site + recurring → `cloud-itonami-isic-7820` (employer-of-record
  dispatch)
- permanent → `cloud-itonami-isic-7810` (one-time placement-fee agency)
- anything else/ambiguous → `cloud-itonami-isic-6399` (public job board,
  widen reach first)

This actor **never calls any of those actors directly** — no shared store, no
cross-actor invocation. The referral draft is human-carried, exactly the
handoff invariant ADR-2607131000 already established for isic-6399↔7810. This
actor's own ledger (`freight-driving.store/record-human-gap!`) records only
its own half: the gap detected, the draft-id, and the target actor named.

**Honesty boundary**: this repository implements governed, audited
DECISION-MAKING software only — gap detection, referral-draft shaping, and
ledger recording. It does **not** itself contact any real recruiting
platform, execute any real contract, or move any real payment. A human
operator (or a real licensed staffing business) must carry the draft and
supply the real-world recruiting, e-signature, and payment integration.

## Required Technologies

`blueprint.edn` names six required technologies; each exists to serve a
specific function in this specific business, not as a generic platform
checklist:

- **`:robotics`** — this ISCO-8332 occupation is flagged `robotics true`
  because the offer includes driving-assist automation (lane/route
  automation, depot load-assist). Robotics is the actuator the governor
  gates: it is the thing `:freight-driver-governor` decides whether to let
  act.
- **`:telemetry`** — feeds route planning, load/delivery tracking, and
  duty-hour logging from the "Offer" section above. Telemetry is the sensor
  side of the loop: it is what tells the governor where the truck is, how
  long the driver has been on duty, and whether a route deviation has
  entered a safety-critical zone.
- **`:identity`** — verifies the driver's credential (CDL-equivalent) at
  manifest sign-off and stamps delivery-proof capture with a verifiable
  identity, so a completed drop-off is attributable to a specific,
  authorized driver rather than an anonymous account.
- **`:dmn`** — encodes the `:freight-driver-governor` decision rule itself
  (manifest freshness, duty-hour limits, safety-critical-zone check) as a
  decision table rather than ad hoc code, so the approve/reject logic above
  is auditable and versioned independently of the app.
- **`:bpmn`** — encodes the intake → propose → approve → execute → audit
  process (load request → route/duty-hour proposal → governor sign-off →
  delivery → audit export) as the operating workflow shippers and brokers
  are actually buying (see Offer: route planning, delivery-proof capture).
- **`:audit-ledger`** — the append-only store behind the Trust Controls
  above: duty-hour logs, manifest sign-offs, and delivery-proof records all
  land here so a completed job can be reconstructed for a certifier or a
  shipper dispute without trusting the driver's or platform's word alone.
