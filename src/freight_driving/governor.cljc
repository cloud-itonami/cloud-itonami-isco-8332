(ns freight-driving.governor
  "FreightDriverGovernor — the independent safety/traceability layer for the
  ISCO-08 8332 independent owner-operator freight-driving actor. The Route
  Advisor proposes actions (deliver, log duty hours); it has no notion of
  load provenance or hours-of-service limits, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD — the
  itonami-actor pattern (independent Governor gates a proposing actor)
  applied to this occupation.

  Charter (mirrors ADR-2607011000 robotics premise + ADR-2607012000
  cloud-itonami-isco): the actor never dispatches a robot action or writes an
  operating record the governor refuses. A duty-hours entry that would push
  the load's cumulative hours over `max-daily-hours` ALWAYS requires human
  sign-off — it can never be auto-approved, only recorded and escalated
  (the itonami analog of an hours-of-service compliance gate).

  HARD invariants for :freight/propose:
    1. Load provenance      — a delivery or duty-hours entry must reference
       a registered load on a registered route. Checks BOTH halves
       independently (:no-load / :no-route) — a load's :route-id is
       caller-supplied at registration and was never validated against
       the route store (:route-fn wasn't even wired into env-for-store),
       so a load could reference a route that was never registered, with
       the route half of this invariant completely unchecked. Same class
       of gap already found and fixed in the sibling ISCO-1212/2221/6112/
       7126 governors (:no-employee-record / :no-patient-record /
       :no-plot / :no-site).
    2. No-actuation         — the proposal must not directly mutate a
       delivery or duty-hours record outside the record-delivery!/
       record-duty-hours! path (effect must be :propose, never a raw store
       write).
    3. Hours-of-service     — a duty-hours entry whose hours, added to the
       load's already-logged hours, exceeds `max-daily-hours` always
       requires :high or higher safety-class, forcing human sign-off; it is
       never auto-approved regardless of confidence.
  SOFT:
    4. Confidence floor → escalate.

  Dispositions (ADR-2607202500 adds :human-required to the pre-existing
  :proceed/:hold/:human-approval vocabulary): `:human-approval` means the
  robot COULD perform the action but a human must sign off first (e.g. the
  hours-of-service gate above). `:human-required` means the robot is
  structurally UNABLE to perform the task at all — some route/terrain/
  manual step (e.g. a manual-loading dock step) sits outside the
  autonomous-driving stack's certified operating domain, so a human must
  actually DO the work, not just approve a robot action. This is always an
  explicit ground-truth field on the proposal (`:human-required?` +
  `:gap`) — never inferred/guessed by the governor (this fleet's
  discipline: HARD/dispositional checks key off explicit fields on the
  record, never LLM inference). A real HARD violation (load provenance,
  no-actuation, invalid safety-class, hours-of-service) still forces
  :hold regardless of `:human-required?` — see `assess` below."
  (:require [freight-driving.store :as store]
            [kotoba.occupation :as occupation]))

(def isco-08
  "This actor's ISCO-08 unit group id (blueprint.edn `:itonami.blueprint/isco-08`),
  used to shape human-gap referral drafts via `kotoba.occupation/human-gap-referral-draft`."
  "8332")
(def confidence-floor 0.6)
(def max-daily-hours 11)
(def safety-classes [:none :low :medium :high :safety-critical])
(def ^:private known-safety-classes (set safety-classes))

;; .indexOf returns -1 (not an exception) for a safety-class outside
;; safety-classes, and this silently mapped to rank 0 == :none -- an
;; unrecognized safety-class (a typo, wrong type, or an unexpected value
;; from a buggy/malicious Advisor) used to silently rank as the LEAST
;; severe class, defeating `(>= (safety-rank safety-class) (safety-rank
;; :high))` in `assess` below and letting it bypass the mandatory
;; human-approval gate instead of failing closed. Callers must reject an
;; unrecognized safety-class as a hard violation (see hard-violations)
;; before it ever reaches this rank comparison. (The hours-of-service
;; check below also calls safety-rank, but in the OPPOSITE `<` direction,
;; where a silent rank-0 default already fails safe -- left unchanged.)
(defn- safety-rank [safety-class]
  (let [idx (.indexOf safety-classes safety-class)]
    (if (neg? idx) 0 idx)))

(defn- exceeds-hos? [existing-entries proposal]
  (and (= :duty-hours (:kind proposal))
       (number? (:hours proposal))
       (> (+ (reduce + 0 (map :hours existing-entries)) (:hours proposal))
          max-daily-hours)))

(defn- hard-violations [{:keys [load-fn duty-hours-fn route-fn]} proposal]
  (let [{:keys [load-id safety-class effect]} proposal
        found-load (load-fn load-id)
        existing (when found-load (duty-hours-fn load-id))
        route (when found-load (route-fn (:route-id found-load)))]
    (cond-> []
      (nil? found-load)
      (conj {:rule :no-load :detail (str "未登録 load " load-id)})

      (and found-load (nil? route))
      (conj {:rule :no-route :detail (str "未登録 route " (:route-id found-load))})

      (not= :propose effect)
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and (some? safety-class) (not (contains? known-safety-classes safety-class)))
      (conj {:rule :invalid-safety-class
             :detail (str "未知の safety-class " safety-class)})

      (and found-load
           (exceeds-hos? existing proposal)
           (< (safety-rank (or safety-class :none)) (safety-rank :high)))
      (conj {:rule :hours-of-service
             :detail (str "累積 duty-hours が max-daily-hours(" max-daily-hours
                           ") を超過 — :high 以上の safety-class が必須")}))))

(defn assess
  "Assess a proposal against `env` (a map with `:load-fn`/`:duty-hours-fn`/
  `:route-fn` lookups, decoupled from any concrete Store so this stays
  pure). Returns
  `{:decision :proceed|:hold|:human-approval|:human-required :violations [...] :confidence n}`.

  `:human-required?` (true/false) is an explicit ground-truth field the
  proposing Advisor sets on the proposal — never inferred here — paired
  with a `:gap` map (`{:task :reason :duration :location :urgency}`,
  ADR-2607202500 / `kotoba.occupation/human-gap-referral-draft` shape).
  When set, and no real HARD violation fired first, `assess` returns
  `:human-required` with a `:referral` draft naming which existing
  cloud-itonami staffing/matching actor a human operator should carry the
  gap to — this governor never calls that actor directly (ADR-2607131000
  handoff invariant, generalized by ADR-2607202500)."
  [env proposal]
  (let [violations (hard-violations env proposal)
        safety-class (or (:safety-class proposal) :none)
        confidence (or (:confidence proposal) 1.0)]
    (cond
      (seq violations)
      {:decision :hold :violations violations :confidence confidence}

      (true? (:human-required? proposal))
      {:decision :human-required :violations [] :confidence confidence
       :referral (occupation/human-gap-referral-draft isco-08 (:gap proposal))}

      (>= (safety-rank safety-class) (safety-rank :high))
      {:decision :human-approval :violations [] :confidence confidence}

      (< confidence confidence-floor)
      {:decision :human-approval :violations [] :confidence confidence
       :reason :low-confidence}

      :else
      {:decision :proceed :violations [] :confidence confidence})))

(defn env-for-store
  "Build the decoupled env map `assess` needs from a concrete
  `freight-driving.store/Store` implementation."
  [store]
  {:load-fn #(store/load-info store %)
   :duty-hours-fn #(store/duty-hours-of store %)
   :route-fn #(store/route store %)})
