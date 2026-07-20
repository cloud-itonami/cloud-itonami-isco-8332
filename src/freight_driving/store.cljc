(ns freight-driving.store
  "SSoT for the ISCO-08 8332 independent owner-operator freight-driving
  sole-proprietor actor, behind a `Store` protocol so the backend is a swap
  (MemStore default ‖ a real Datomic/kotoba-server backend, per the itonami
  actor pattern).

  Domain = independent heavy-truck freight-driving operations:

    route        — a planned route (routeId, origin, destination)
    load         — freight assigned to a route (loadId, routeId, weightKg)
    delivery     — a completed delivery event (deliveryId, loadId, deliveredAt)
    duty-hours   — an appended duty-hour log entry (entryId, loadId, hours)
    human-gap    — an appended human-required-gap referral-draft record
                   (ADR-2607202500): this actor's OWN half only (the gap
                   detected + the draft-id + the target staffing/matching
                   actor named by `kotoba.occupation/human-gap-referral-draft`).
                   Never writes to, or calls, any other actor's store —
                   no HTTP, no real recruiting/payment/e-signature logic,
                   pure governed decision-recording.

  The append-only records are the operating ledger: a delivery or duty-hours
  entry must reference a registered load on a registered route, and
  deliveries/duty-hours/human-gap entries are never mutated in place, only
  appended.")

(defprotocol Store
  (route [st route-id])
  (load-info [st load-id])
  (loads-of [st route-id])
  (deliveries-of [st load-id])
  (duty-hours-of [st load-id])
  (human-gaps [st])
  (register-route! [st route])
  (register-load! [st load])
  (record-delivery! [st delivery])
  (record-duty-hours! [st entry])
  (record-human-gap! [st gap-record]))

(defrecord MemStore [state]
  Store
  (route [_ route-id]
    (get-in @state [:routes route-id]))
  (load-info [_ load-id]
    (get-in @state [:loads load-id]))
  (loads-of [_ route-id]
    (filter #(= route-id (:route-id %)) (vals (:loads @state))))
  (deliveries-of [_ load-id]
    (filter #(= load-id (:load-id %)) (:deliveries @state)))
  (duty-hours-of [_ load-id]
    (filter #(= load-id (:load-id %)) (:duty-hours @state)))
  (human-gaps [_]
    (:human-gaps @state))
  (register-route! [_ route]
    (swap! state assoc-in [:routes (:route-id route)] route))
  (register-load! [_ load]
    (swap! state assoc-in [:loads (:load-id load)] load))
  (record-delivery! [_ delivery]
    (swap! state update :deliveries (fnil conj []) delivery))
  (record-duty-hours! [_ entry]
    (swap! state update :duty-hours (fnil conj []) entry))
  (record-human-gap! [_ gap-record]
    (swap! state update :human-gaps (fnil conj []) gap-record)))

(defn mem-store
  ([] (mem-store {}))
  ([seed]
   (->MemStore (atom (merge {:routes {} :loads {} :deliveries [] :duty-hours [] :human-gaps []} seed)))))
