(ns freight-driving.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [freight-driving.store :as store]
            [freight-driving.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-route! st {:route-id "route-1" :origin "A" :destination "B"})
    (store/register-load! st {:load-id "load-1" :route-id "route-1" :weight-kg 8000})
    st))

(deftest proceeds-on-clean-delivery-log
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :delivery :load-id "load-1" :safety-class :low
                   :effect :propose :confidence 0.9}]
    (is (= :proceed (:decision (governor/assess env proposal))))))

(deftest holds-on-unregistered-load
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :duty-hours :load-id "no-such-load" :hours 4
                   :safety-class :low :effect :propose :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :no-load (:rule %)) (:violations result)))))

(deftest holds-on-orphaned-load-with-no-route-record
  ;; a load's :route-id is caller-supplied at registration and was never
  ;; validated against the route store (:route-fn wasn't even wired into
  ;; env-for-store), so a load could reference a route that was never
  ;; registered, with the route half of "load on a registered route"
  ;; completely unchecked.
  (let [st (store/mem-store)
        _ (store/register-load! st {:load-id "orphan-load" :route-id "no-such-route" :weight-kg 8000})
        env (governor/env-for-store st)
        proposal {:kind :delivery :load-id "orphan-load" :safety-class :low
                   :effect :propose :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :no-route (:rule %)) (:violations result)))))

(deftest holds-on-no-actuation-violation
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :delivery :load-id "load-1" :safety-class :low
                   :effect :direct-write :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :no-actuation (:rule %)) (:violations result)))))

(deftest proceeds-on-duty-hours-within-limit
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :duty-hours :load-id "load-1" :hours 8
                   :safety-class :low :effect :propose :confidence 0.9}]
    (is (= :proceed (:decision (governor/assess env proposal))))))

(deftest holds-on-duty-hours-exceeding-limit-without-high-safety-class
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :duty-hours :load-id "load-1" :hours 12
                   :safety-class :medium :effect :propose :confidence 0.9}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :hours-of-service (:rule %)) (:violations result)))))

(deftest human-approval-on-duty-hours-exceeding-limit-with-high-safety-class
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :duty-hours :load-id "load-1" :hours 12
                   :safety-class :high :effect :propose :confidence 0.9}]
    (is (= :human-approval (:decision (governor/assess env proposal))))))

(deftest holds-on-cumulative-duty-hours-exceeding-limit
  (let [st (fresh-store)]
    (store/record-duty-hours! st {:entry-id "e1" :load-id "load-1" :hours 8})
    (let [env (governor/env-for-store st)
          proposal {:kind :duty-hours :load-id "load-1" :hours 5
                     :safety-class :medium :effect :propose :confidence 0.9}
          result (governor/assess env proposal)]
      (is (= :hold (:decision result)))
      (is (some #(= :hours-of-service (:rule %)) (:violations result))))))

(deftest holds-on-unrecognized-safety-class-instead-of-silently-proceeding
  ;; safety-rank used .indexOf, which returns -1 (not an exception) for a
  ;; value outside safety-classes, silently ranked as 0 == :none -- an
  ;; unrecognized safety-class (typo, wrong type, or unexpected Advisor
  ;; output) used to bypass the mandatory human-approval gate for
  ;; :high/:safety-critical proposals instead of failing closed. Uses
  ;; :kind :delivery so only :invalid-safety-class is exercised, not
  ;; :hours-of-service.
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :delivery :load-id "load-1" :safety-class :extreme
                   :effect :propose :confidence 1.0}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :invalid-safety-class (:rule %)) (:violations result)))))

(deftest human-approval-on-low-confidence
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :delivery :load-id "load-1" :safety-class :none
                   :effect :propose :confidence 0.2}
        result (governor/assess env proposal)]
    (is (= :human-approval (:decision result)))
    (is (= :low-confidence (:reason result)))))

(deftest store-records-append-only
  (let [st (fresh-store)]
    (store/record-delivery! st {:delivery-id "d1" :load-id "load-1" :delivered-at "2026-07-02"})
    (store/record-duty-hours! st {:entry-id "e1" :load-id "load-1" :hours 4})
    (is (= 1 (count (store/deliveries-of st "load-1"))))
    (is (= 1 (count (store/duty-hours-of st "load-1"))))
    (is (= 1 (count (store/loads-of st "route-1"))))))

;; ADR-2607202600: human-required gap detection + referral-draft handoff.
;; :human-required is a NEW, distinct disposition from :human-approval --
;; the robot is structurally unable to perform the task at all (not just
;; "needs a sign-off"), always driven by an explicit ground-truth field on
;; the proposal (`:human-required?` + `:gap`), never inferred. The
;; :target-actor routing in these tests follows `kotoba.occupation/route-gap`'s
;; actual precedence (kotoba-lang/occupation @ fb1b9f9, ADR-2607202600):
;; `:reason :no-automation-path` OR `:location :remote` wins outright over
;; :duration (routes to isic-8299, the no-employer-of-record target), so the
;; :duration :permanent / :on-site+:recurring branches below deliberately use
;; `:reason :missing-technology` instead, to avoid tripping that higher-
;; precedence branch.

(deftest human-required-on-recurring-on-site-manual-loading-dock-gap
  ;; Real scenario for this occupation: a recurring manual-loading dock
  ;; step at the yard is outside the autonomous-driving stack's certified
  ;; operating domain (on-site + recurring) -- routes to isic-7820
  ;; (employer-of-record dispatch), per the shared routing table.
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :delivery :load-id "load-1" :safety-class :medium
                   :effect :propose :confidence 0.9
                   :human-required? true
                   :gap {:task "recurring manual loading-dock assist outside the certified autonomous-driving operating domain"
                         :reason :missing-technology
                         :duration :recurring
                         :location :on-site
                         :urgency :normal}}
        result (governor/assess env proposal)]
    (is (= :human-required (:decision result)))
    (is (= [] (:violations result)))
    (is (= "cloud-itonami-isic-7820" (:target-actor (:referral result))))
    (is (= "8332" (:isco (:referral result))))))

(deftest human-required-routes-one-off-remote-to-bpo-task-matching
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :delivery :load-id "load-1" :safety-class :low
                   :effect :propose :confidence 0.9
                   :human-required? true
                   :gap {:task "remotely re-plan a route around a one-off closure"
                         :reason :missing-technology
                         :duration :one-off
                         :location :remote
                         :urgency :low}}
        result (governor/assess env proposal)]
    (is (= :human-required (:decision result)))
    (is (= "cloud-itonami-isic-8299" (:target-actor (:referral result))))))

(deftest human-required-routes-permanent-gap-to-placement-agency
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :delivery :load-id "load-1" :safety-class :low
                   :effect :propose :confidence 0.9
                   :human-required? true
                   :gap {:task "hire a full-time backup driver for a route the stack will never certify"
                         :reason :missing-technology
                         :duration :permanent
                         :location :on-site
                         :urgency :normal}}
        result (governor/assess env proposal)]
    (is (= :human-required (:decision result)))
    (is (= "cloud-itonami-isic-7810" (:target-actor (:referral result))))))

(deftest human-required-routes-ambiguous-gap-to-default-fallback
  ;; kotoba.occupation/route-gap's fallback for an unrecognized/missing
  ;; :reason+:duration+:location combination is the no-employer-of-record
  ;; target isic-8299 (the conservative default per its docstring) -- NOT
  ;; isic-6399. isic-6399 (public job board / widen-reach) is a separate,
  ;; explicitly-invoked step (`kotoba.occupation/widen-reach-draft`), not a
  ;; branch of `route-gap`/`human-gap-referral-draft` itself.
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :delivery :load-id "load-1" :safety-class :low
                   :effect :propose :confidence 0.9
                   :human-required? true
                   :gap {:task "unclear-scope terrain the stack can't classify yet"
                         :reason :other}}
        result (governor/assess env proposal)]
    (is (= :human-required (:decision result)))
    (is (= "cloud-itonami-isic-8299" (:target-actor (:referral result))))))

(deftest hard-violation-holds-even-with-human-required-flag-set
  ;; A real HARD violation (here: no-actuation, a direct-write effect)
  ;; must still :hold regardless of :human-required? -- the flag never
  ;; overrides a hard invariant.
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :delivery :load-id "load-1" :safety-class :low
                   :effect :direct-write :confidence 0.9
                   :human-required? true
                   :gap {:task "should never reach the referral branch"
                         :duration :recurring :location :on-site}}
        result (governor/assess env proposal)]
    (is (= :hold (:decision result)))
    (is (some #(= :no-actuation (:rule %)) (:violations result)))
    (is (nil? (:referral result)))))

(deftest record-human-gap-round-trips-through-the-store
  (let [st (fresh-store)
        env (governor/env-for-store st)
        proposal {:kind :delivery :load-id "load-1" :safety-class :medium
                   :effect :propose :confidence 0.9
                   :human-required? true
                   :gap {:task "recurring manual loading-dock assist outside the certified autonomous-driving operating domain"
                         :reason :missing-technology
                         :duration :recurring
                         :location :on-site
                         :urgency :normal}}
        result (governor/assess env proposal)]
    (is (empty? (store/human-gaps st)))
    (store/record-human-gap! st (:referral result))
    (is (= 1 (count (store/human-gaps st))))
    (is (= (:referral result) (first (store/human-gaps st))))
    (is (= "cloud-itonami-isic-7820" (:target-actor (first (store/human-gaps st)))))))
