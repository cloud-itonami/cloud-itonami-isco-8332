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
