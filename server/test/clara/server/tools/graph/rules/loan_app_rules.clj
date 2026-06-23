(ns clara.server.tools.graph.rules.loan-app-rules
  (:require [clara.rules :as r]
            [clara.rules.accumulators :as acc]
            [clara.server.tools.graph.rules.loan-doc-rules])
  (:import
   [clara.server.tools.graph.rules.loan_app_facts
    Application

    DocumentCheck
    IdentityCheck
    FraudCheck]))

;; NOTE: Leaving this fact type here inline to show fact types coming from multiple places.
(defrecord ApplicationOutcome [app-id status message passed-checks failed-checks checks-complete checks-incomplete])

(r/defrule app-outcome-approved
  {:clara-rules/insert-types [ApplicationOutcome]}
  [Application (= ?app-id app-id)]
  [DocumentCheck (= ?app-id app-id) (= status :pass)]
  [IdentityCheck (= ?app-id app-id) (= status :pass)]
  [FraudCheck (= ?app-id app-id) (= status :pass)]
  =>
  (r/insert! (map->ApplicationOutcome {:app-id ?app-id
                                       :status :approved
                                       :message "Application approved"
                                       :passed-checks [{:check-type :document}
                                                       {:check-type :identity}
                                                       {:check-type :fraud}]})))

(r/defrule app-outcome-denied
  {:clara-rules/insert-types [ApplicationOutcome]}
  [Application (= ?app-id app-id)]
  [:not [ApplicationOutcome (= ?app-id app-id) (= status :approved)]]
  [DocumentCheck (= ?app-id app-id) (= ?doc-check-status status)]
  [IdentityCheck (= ?app-id app-id) (= ?identity-check-status status)]
  [FraudCheck (= ?app-id app-id) (= ?fraud-check-status status)]
  =>
  (let [all-checks [{:check-type :document :status ?doc-check-status}
                    {:check-type :identity :status ?identity-check-status}
                    {:check-type :fraud :status ?fraud-check-status}]
        passed-checks (into []
                            (comp (filter (comp #{:pass} :status))
                                  (map :check-type))
                            all-checks)
        failed-checks (into []
                            (comp (remove (comp #{:pass} :status))
                                  (map :check-type))
                            all-checks)]
    (r/insert! (map->ApplicationOutcome {:app-id ?app-id
                                         :status :denied
                                         :message "Application denied"
                                         :passed-checks passed-checks
                                         :failed-checks failed-checks}))))

(r/defrule app-outcome-pending
  {:clara-rules/insert-types [ApplicationOutcome]}
  [Application (= ?app-id app-id)]
  [:not [ApplicationOutcome (= ?app-id app-id) (= status :approved)]]
  [:not [ApplicationOutcome (= ?app-id app-id) (= status :denied)]]
  [?doc-checks <- (acc/all) :from [DocumentCheck (= ?app-id app-id)]]
  [?id-checks <- (acc/all) :from [IdentityCheck (= ?app-id app-id)]]
  [?fraud-checks <- (acc/all) :from [FraudCheck (= ?app-id app-id)]]

  =>
  (let [all-checks [{:check-type :document
                     :complete? (boolean (seq ?doc-checks))}
                    {:check-type :identity
                     :complete? (boolean (seq ?id-checks))}
                    {:check-type :fraud
                     :complete? (boolean (seq ?fraud-checks))}]
        checks-complete (into [] (comp (filter :complete?) (map :check-type)) all-checks)
        checks-incomplete (into [] (comp (remove :complete?) (map :check-type)) all-checks)]
    (r/insert! (map->ApplicationOutcome {:app-id ?app-id
                                         :status :pending
                                         :message "Application pending"
                                         :checks-complete checks-complete
                                         :checks-incomplete checks-incomplete}))))

(r/defquery find-app-outcome
  [?app-id]
  [?outcome <- ApplicationOutcome (= ?app-id app-id)])
