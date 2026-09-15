(ns clara.server.tools.graph.rules.loan-outcome-notices
  "Downstream loan-disposition ruleset reacting to the application outcome
   produced by `clara.server.tools.graph.rules.loan-app-rules`.

   This namespace is deliberately NOT part of the `loan-app-ruleset` example:
   a session built with only this namespace matches the
   `:loan-app/application-outcome` keyword type but, with no upstream rules
   loaded, has no matching facts on its own. Its annotations still record the
   consume/produce contract, which is what the multi-ruleset artifact registry
   needs to see."
  (:require [clara.rules :as r]))

;; NOTE: Leaving these fact types inline, mirroring
;; clara.server.tools.graph.rules.loan-app-rules, to show a second ruleset
;; defining its own produced fact types alongside the consumed upstream ones.

(defrecord ApprovalNotice [app-id message notice-id])

(defrecord DenialNotice [app-id message reasons notice-id])

(r/defrule notice-approved-app
  {:clara-rules/insert-types [ApprovalNotice]}
  [:loan-app/application-outcome
   [{:keys [app-id status]}]
   (= ?app-id app-id)
   (= status :approved)]
  =>
  (r/insert! (map->ApprovalNotice {:app-id ?app-id
                                   :message "Loan approved"
                                   :notice-id (str "approval-" ?app-id)})))

(r/defrule notice-denied-app
  {:clara-rules/insert-types [DenialNotice]}
  [:loan-app/application-outcome
   [{:keys [app-id status failed-checks]}]
   (= ?app-id app-id)
   (= ?reasons failed-checks)
   (= status :denied)]
  =>
  (r/insert! (map->DenialNotice {:app-id ?app-id
                                 :message "Loan denied"
                                 :reasons ?reasons
                                 :notice-id (str "denial-" ?app-id)})))

(r/defquery find-approval-notices
  [?app-id]
  [?notice <- ApprovalNotice (= ?app-id app-id)])

(r/defquery find-denial-notices
  [?app-id]
  [?notice <- DenialNotice (= ?app-id app-id)])
