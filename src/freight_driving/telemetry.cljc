(ns freight-driving.telemetry
  "ITONAMI play-data -> operating-ledger bridge (ADR-2607031000 addendum,
  2026-07-04): ingest a network-isekai `Freight Run` playthrough record as a
  REAL operating record, through the SAME governor/store path a live
  proposal would use — never a parallel bypass. network-isekai has no
  server (static, CodePen-style host); the bridge is a JSON record the game
  host persists to localStorage (`isekai.itonami.log.<blueprint-id>`,
  `src/isekai/game.cljc` in network-isekai) that an operator exports and
  feeds to `ingest-playthrough!` here.

  A playthrough with governor violations (an unmanifested/HOS-blocked drop
  logged by the game) is submitted at :high safety-class / low confidence —
  the SAME hard invariant that forces human sign-off on a risky delivery
  forces human sign-off on ingesting a risky playthrough, too."
  (:require [freight-driving.store :as store]
            [freight-driving.governor :as governor]))

(def session-route-id "network-isekai-session")
(def session-load-id "network-isekai-playthrough")

(defn ensure-session-load!
  "Idempotently register the synthetic route/load a playthrough files its
  delivery against — distinct from any real route/load the operator tracks,
  so playthrough ingestion can never be mistaken for real dispatch
  provenance."
  [st]
  (when-not (store/route st session-route-id)
    (store/register-route! st {:route-id session-route-id
                                :origin "network-isekai"
                                :destination "playthrough sessions"}))
  (when-not (store/load-info st session-load-id)
    (store/register-load! st {:load-id session-load-id
                               :route-id session-route-id
                               :weight-kg 0})))

(defn ingest-playthrough!
  "Run a network-isekai playthrough record
  (`{:score :picked :lives-remaining :violations :outcome}`, matching the
  JSON shape `src/isekai/game.cljc` persists) through the real
  FreightDriverGovernor and, only on :proceed, append it to the ledger via
  `record-delivery!`. Returns the governor's decision map plus `:ingested?`
  and the original `:record`."
  [st {:keys [score picked violations] :as record}]
  (ensure-session-load! st)
  (let [env (governor/env-for-store st)
        proposal {:kind :delivery
                  :load-id session-load-id
                  :effect :propose
                  :safety-class (if (pos? (or violations 0)) :high :low)
                  :confidence (if (pos? (or violations 0)) 0.4 0.9)}
        decision (governor/assess env proposal)
        proceed? (= :proceed (:decision decision))]
    (when proceed?
      (let [seq-n (count (store/deliveries-of st session-load-id))]
        (store/record-delivery! st {:delivery-id (str "playthrough-" (or (:ts record) seq-n))
                                     :load-id session-load-id
                                     :delivered-at (or (:ts record) seq-n)
                                     :source :network-isekai
                                     :raw record})))
    (assoc decision :ingested? proceed? :record record)))
