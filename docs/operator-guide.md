# Operator Guide

## First Deployment

1. Define the operator's service area and intake process.
2. Define consent and purpose categories.
3. Run synthetic operating cases.
4. Enable human-reviewed sign-off for `:high`/`:safety-critical` actions.
5. Measure operating outcomes and audit coverage.

## Minimum Production Controls

- consent and disclosure log
- safety-critical escalation path
- provenance for all operating records
- human review for high-risk cases
- audit export for all gated actions

## Certification

Certified operators must prove that the governor gates every safety-critical
robot action, and that safety-critical risks escalate to humans.

## Day in the Life: Intake → Propose → Approve → Execute → Audit

This walkthrough is a real freight run for this occupation (ISCO-8332, Heavy
Truck and Lorry Drivers) end to end — not an abstract "task." A broker has a
pallet of refrigerated produce that needs to move from a distribution center
to a grocery store's receiving dock before its delivery window closes.

1. **Intake.** The broker files the load into the platform: pickup site,
   delivery site, weight, delivery window, and (for this run) an
   expedited/"rush" flag because the window is tight. This becomes a queued
   job tied to the driver's route for the day, alongside the driver's other
   scheduled drops. Nothing about the run has been approved yet — intake
   just registers that the load exists.

2. **Propose.** The platform proposes a route and a duty-hour plan for the
   driver: current position, distance to pickup, drive time to delivery, and
   how much of the driver's remaining duty-hour budget the run would consume
   (fed by `:telemetry`). If the proposed route crosses a safety-critical
   zone (e.g. a restricted corridor near a school zone or a weight-limited
   bridge), that deviation is flagged here for governor review rather than
   silently routed around.

3. **Approve.** Before the driver leaves the yard with this specific load,
   `:freight-driver-governor` must sign off: it checks the driver's identity
   and credential (`:identity`), confirms the duty-hour ledger has room for
   this run, and — if the proposed route touched a safety-critical zone —
   confirms a human reviewer signed off on the deviation. This produces a
   **per-load manifest**, good for this one drop only. Note that the rush
   flag on this load does **not** change the check: an expedited pallet gets
   exactly the same sign-off as a routine one, it just happens to pay more
   (the "Revenue" section's per-load fee scales with priority; the governor
   rule does not).

4. **Execute.** The driver drives the route and, on arrival, hands off the
   pallet and captures delivery-proof (signature/photo, timestamped and
   identity-stamped) at the receiving dock. This drop-off consumes the
   manifest granted in step 3 — it is not reusable for the driver's next
   stop. If a driver were to attempt a hand-off at a stop with no current
   manifest (skipped step 3, or the manifest was already spent on a prior
   drop), that is an **unmanifested load**: the governor rejects it and logs
   a violation rather than letting the drop-off complete.

5. **Audit.** The completed run appends to the `:audit-ledger`: the manifest
   sign-off, the duty-hour delta, the delivery-proof record, and — if
   applicable — the human sign-off on the safety-critical deviation. None of
   these records can be edited after the fact, only appended to, so a
   shipper dispute or a certifier's spot-check can reconstruct exactly what
   the governor approved and when, per the Trust Controls in
   `business-model.md`.

Every stop on the driver's route repeats steps 2–5: propose the next leg,
take a fresh manifest at the yard, execute the drop, audit the record. A
driver never carries a standing "all clear" for the day — only for the load
currently in hand.

## Feel the Loop: ITONAMI Freight Run

The `intake → propose → approve → execute → audit` loop above is also
playable. `network-isekai`'s **ITONAMI: Freight Run**
(`public/games/itonami/freight-run`) turns this exact blueprint into a small
depot round: touch the "yard" to take this round's
`:freight-driver-governor` manifest sign-off, then touch a "parcel" to
deliver it — clear all 8 drops to close the manifest clean. A "rush-parcel"
job is worth 3x score but requires the identical sign-off (no shortcut for
urgency, matching the rule above), and delivering a parcel without a current
sign-off is an unmanifested load that costs a life. It's a hands-on way to
feel why the approval gate is per-load and non-reusable before reading the
formal rule in `business-model.md`.
