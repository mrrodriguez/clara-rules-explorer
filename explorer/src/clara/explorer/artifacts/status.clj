(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.status
  "Whether a persisted unit is current: the comparison behind the offline bb
  `status` report, shared with any JVM host that wants the same verdict.

  A host that persists extra inputs through `:blocks` (tool versions, its own
  configuration slices) calls `unit-status` and appends its own `:reasons`
  before rendering — host-specific staleness without the explorer knowing those
  inputs exist.

  A composed unit is only as current as its sources: `unit-status` reports the
  composition's record beside each source unit's own status report, and the
  caller says where checkouts live (`:source-checkouts`) or supplies that report
  itself (`:source-status-fn`). A composition cannot look for checkouts on its
  own — where a reader's checkouts live is the reader's business, as
  `clara.explorer.artifacts.manifest` says of the manifest it writes."
  (:require
   [clara.explorer.artifacts.layout :as layout]
   [clara.explorer.artifacts.git :as git]
   [clara.explorer.artifacts.shared.registry :as shared-registry]
   [clojure.edn :as edn]
   [clojure.string :as str])
  (:import
   (java.time LocalDate)
   (java.time.temporal ChronoUnit)))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; remotes
;; ===========================================================================

(defn normalize-remote
  "Remote url for comparison: trimmed, with the scheme, `user@`, `.git` suffix,
  and trailing slashes stripped, and scp-like `host:path` rewritten to
  `host/path`. Two checkouts of one repo compare equal across the url spellings
  people actually use; a port in scp position is not distinguished."
  [url]
  (-> (str url)
      str/trim
      (str/replace #"/+$" "")
      (str/replace #"(?i)\.git$" "")
      (str/replace #"^[a-zA-Z][a-zA-Z0-9+.-]*://" "")
      (str/replace #"^[^/@]+@" "")
      (str/replace #"^([^/:]+):" "$1/")))

(defn- remote-match?
  "Whether `recorded` and `current` name the same remote after
  `normalize-remote`. Two absent remotes match; one absent remote never does."
  [recorded current]
  (if (or (str/blank? recorded) (str/blank? current))
    (and (str/blank? recorded) (str/blank? current))
    (= (normalize-remote recorded) (normalize-remote current))))

;; ===========================================================================
;; manifest IO
;; ===========================================================================

(defn- manifest-path
  "Path of the manifest file inside unit dir `dir`."
  [dir]
  (str dir "/" (:manifest layout/artifact-files)))

(defn- read-manifest-or-nil
  "The parsed manifest in `dir`, or nil when it cannot be read."
  [dir]
  (try
    (edn/read-string {:default (fn [_tag v] v)} (slurp (manifest-path dir)))
    (catch Exception _ nil)))

(defn- read-manifest!
  "The parsed manifest in `dir`, throwing when it cannot be read."
  [dir]
  (or (read-manifest-or-nil dir)
      (throw (ex-info (format "No readable manifest in unit dir: %s" (str dir))
                      {:dir (str dir) :manifest (manifest-path dir)}))))

;; ===========================================================================
;; age
;; ===========================================================================

(defn- age-days
  "Days from `:updated` (`YYYY-MM-DD`) to `today`, or nil when unparseable."
  [updated today]
  (try
    (int (.between ChronoUnit/DAYS (LocalDate/parse ^CharSequence (str updated)) today))
    (catch Exception _ nil)))

(defn- age-reason
  "The `:age-exceeded` reason when `:updated` is older than the policy's
  `:max-age-days`, else nil. Skipped when either is absent or unparseable."
  [updated max-age-days today]
  (when (and (some? max-age-days) (number? max-age-days))
    (when-let [age (age-days updated today)]
      (when (> age max-age-days)
        {:check :age-exceeded
         :updated updated
         :max-age-days max-age-days
         :age-days age}))))

;; ===========================================================================
;; verdicts
;; ===========================================================================

(def ^:private stale-checks
  "Reason `:check`s that make a unit stale."
  #{:remote-mismatch :sha-drift :generated-dirty :age-exceeded
    :source-sha-drift :source-missing :unit-stale})

(def ^:private unknown-checks
  "Reason `:check`s that leave the verdict unknown — the comparison could not
  be made, which is not the same as the unit having drifted."
  #{:sha-not-compared :ref-unresolvable :checkout-not-a-repo
    :aggregate-no-sources :checkout-off-ref :unit-unknown})

(defn- ->verdict
  "`:stale` when any reason says the unit drifted, `:unknown` when any says the
  comparison could not be made, `:current` otherwise. Informational reasons
  (e.g. `:checkout-dirty`) affect neither."
  [reasons]
  (let [checks (into #{} (map :check) reasons)]
    (cond
      (seq (filter stale-checks checks)) :stale
      (seq (filter unknown-checks checks)) :unknown
      :else :current)))

(defn- merge-verdicts
  "The strictest of `verdicts`: `:stale` over `:unknown` over `:current`, the
  same precedence `->verdict` applies to reasons. Used where two reports of one
  source (its composition record and its own status) decide one verdict."
  [verdicts]
  (cond
    (some #{:stale} verdicts) :stale
    (some #{:unknown} verdicts) :unknown
    :else :current))

;; ===========================================================================
;; source units
;; ===========================================================================

(defn- checkout-comparison
  "What `--checkout`/`--ref` resolves to: the checkout path, the ref name, the
  sha it resolves to (nil when it resolves to nothing), the checkout's origin,
  the branch it is on (nil when detached), and whether its tree is clean. A nil
  sha with a readable remote means the ref is unknown there; nil for both means
  the checkout is missing or not a repo.

  `:ref-given?` records whether the caller named a ref: without one the
  comparison is `HEAD` on whatever branch the checkout is on, which says nothing
  about a unit analyzed on another branch — see `off-ref?`."
  [checkout ref]
  {:checkout (str checkout)
   :ref (or ref "HEAD")
   :ref-given? (some? ref)
   :sha (git/ref-sha checkout (or ref "HEAD"))
   :branch (git/attached-branch checkout)
   :remote (git/remote-url checkout)
   :clean? (git/clean-tree? checkout)})

(defn- off-ref?
  "Whether `compared`'s checkout is on a branch the unit was not analyzed on, so
  its sha says nothing about whether the unit drifted. Only meaningful without
  an explicit `:ref` — an explicit ref names the commit to compare — and only
  when both branches are known: a detached checkout names no branch, and a unit
  whose commit belongs to no remote branch leaves nothing to compare."
  [source compared]
  (let [recorded (:branch source)
        current (:branch compared)]
    (and (not (:ref-given? compared))
         (some? recorded)
         (some? current)
         (not= recorded current))))

(defn- sha-reasons
  "Reasons comparing the manifest's recorded `:source` against `compared`
  (nil when no `--checkout` was given)."
  [source compared]
  (cond
    (nil? compared)
    [{:check :sha-not-compared}]

    (and (nil? (:sha compared)) (nil? (:remote compared)))
    [{:check :checkout-not-a-repo
      :checkout (:checkout compared)}]

    (not (remote-match? (:remote source) (:remote compared)))
    [{:check :remote-mismatch
      :recorded (:remote source)
      :current (:remote compared)}]

    (nil? (:sha compared))
    [{:check :ref-unresolvable
      :ref (:ref compared)
      :checkout (:checkout compared)}]

    (off-ref? source compared)
    [{:check :checkout-off-ref
      :recorded (:branch source)
      :current (:branch compared)}]

    (not= (:sha source) (:sha compared))
    [{:check :sha-drift
      :recorded (:sha source)
      :current (:sha compared)}]

    :else []))

(defn- source-unit-status
  "Result map for a source unit (no `:analysis-run :mode`). `compared` is
  `checkout-comparison` when `--checkout` was given, else nil."
  [base manifest compared today]
  (let [source (:source manifest)
        reasons (vec (concat (sha-reasons source compared)
                             (when (= "dirty" (:working-tree source))
                               [{:check :generated-dirty
                                 :working-tree (:working-tree source)}])
                             (when (and (some? compared) (not (:clean? compared)))
                               [{:check :checkout-dirty
                                 :checkout (:checkout compared)
                                 :informational true}])
                             (keep identity
                                   [(age-reason (:updated manifest)
                                                (get-in manifest [:staleness :max-age-days])
                                                today)])))]
    (cond-> (assoc base
                   :kind :source
                   :verdict (->verdict reasons)
                   :reasons reasons)
      (some? compared) (assoc :compared (select-keys compared [:checkout :ref :sha])))))

;; ===========================================================================
;; composed units
;; ===========================================================================

(defn- parse-unit-key
  "`repo[@variant path]` back into `{:repo :variant}`. A composition's key is its
  whole path (`layout/compose-subdir` names its name freely, `@` included), so
  it is never split. Any other key splits on the last `@`, since values encode
  `@`; the variant half decodes via `layout/path->variant`."
  [unit-key]
  (let [s (str unit-key)
        i (when-not (str/starts-with? s (str layout/compose-subdir "/"))
            (str/last-index-of s "@"))]
    (if i
      {:repo (subs s 0 i)
       :variant (layout/path->variant (subs s (inc i)))}
      {:repo s})))

(defn- source-locator
  "How to find `unit-key`'s unit dir: its `{:repo :variant}`, taken from the
  manifest's `:analysis-run :units` entry when one matches, else parsed back
  out of the key."
  [manifest unit-key]
  (or (some #(when (= unit-key (shared-registry/unit-key %))
               (select-keys % [:repo :variant]))
            (get-in manifest [:analysis-run :units]))
      (parse-unit-key unit-key)))

(defn- get-source-unit
  "Where `unit-key`'s unit lives: the `{:repo :variant}` its locator names,
  plus the dir that locator gives under `root`. The dir is a path, not a
  promise the unit is there — reading its manifest is what answers that."
  [manifest root unit-key]
  (let [locator (source-locator manifest unit-key)]
    (assoc locator :dir (layout/->unit-dir (assoc locator :root root)))))

(defn- composition-report
  "Link B for one source: `:recorded` is the sha the composition recorded,
  `:unit-sha` the sha the source unit's manifest holds now (nil when that unit
  or its manifest is gone), and `:verdict` whether the two agree."
  [recorded unit-sha]
  {:recorded (:sha recorded)
   :unit-sha unit-sha
   :verdict (if (and (some? unit-sha) (= (:sha recorded) unit-sha))
              :current
              :stale)})

(defn- source-status-reasons
  "The top-level reasons one source contributes: link B's reason when the
  composition's record no longer matches the unit, then link A's, nested under
  `:unit-stale`/`:unit-unknown` so a reader sees which source's own report
  decided it and why."
  [unit-key composition unit-status]
  (let [link-b (when (not= :current (:verdict composition))
                 [(if (some? (:unit-sha composition))
                    {:check :source-sha-drift
                     :unit-key unit-key
                     :recorded (:recorded composition)
                     :current (:unit-sha composition)}
                    {:check :source-missing
                     :unit-key unit-key})])
        link-a (case (:verdict unit-status)
                 :stale [{:check :unit-stale
                          :unit-key unit-key
                          :reasons (:reasons unit-status)}]
                 :unknown [{:check :unit-unknown
                            :unit-key unit-key
                            :reasons (:reasons unit-status)}]
                 nil)]
    (vec (concat link-b link-a))))

(defn- check-source
  "One composed source judged on both links: `:composition` against the source
  unit's manifest under `root` now, `:unit-status` from `status-fn` — absent
  when the unit is gone, since there is no unit to check, and in that case
  `status-fn` is not called. The two reports' verdicts fold into this source's
  `:verdict`."
  [manifest root status-fn unit-key recorded]
  (let [{:keys [repo variant dir]} (get-source-unit manifest root unit-key)
        unit-sha (some-> dir read-manifest-or-nil (get-in [:source :sha]))
        composition (composition-report recorded unit-sha)
        unit-status (when (some? unit-sha)
                      (status-fn (cond-> {:unit-key unit-key
                                          :repo repo
                                          :dir dir}
                                   (seq variant) (assoc :variant variant))))]
    {:unit-key unit-key
     :composition composition
     :unit-status unit-status
     :verdict (merge-verdicts (keep :verdict [composition unit-status]))
     :reasons (source-status-reasons unit-key composition unit-status)}))

(defn- composed-unit-status
  "Result map for a composed unit (`:analysis-run :mode` present with
  `:staleness :sources`). Current only if both links hold for every source: the
  composition's record still matches the source unit, and `status-fn`'s report
  on that unit says it still matches its code."
  [base manifest root status-fn]
  (let [sources (get-in manifest [:staleness :sources])
        mode (get-in manifest [:analysis-run :mode])]
    (if-not (seq sources)
      (assoc base
             :kind :aggregate
             :mode mode
             :verdict :unknown
             :reasons [{:check :aggregate-no-sources}])
      (let [checked (mapv (fn [[unit-key recorded]]
                            (check-source manifest root status-fn (str unit-key) recorded))
                          (sort-by key sources))
            reasons (into [] (mapcat :reasons) checked)]
        (assoc base
               :kind :aggregate
               :mode mode
               :verdict (->verdict reasons)
               :reasons reasons
               :sources (mapv (fn [{:keys [unit-status] :as entry}]
                                (cond-> (dissoc entry :reasons)
                                  (nil? unit-status) (dissoc :unit-status)))
                              checked))))))

;; ===========================================================================
;; entry point
;; ===========================================================================

(defn- ->source-status-fn
  "The per-source report function a composed unit uses: `source-status-fn` when
  the caller gave one, else `status-fn` (this namespace's `unit-status*`, passed
  in to avoid a forward reference) applied to each source unit with its repo's
  checkout. `:source-checkouts` goes down with it, so a source that is itself a
  composition checks its own sources the same way."
  [source-status-fn source-checkouts status-fn]
  (or source-status-fn
      (fn [{:keys [repo dir]}]
        (status-fn (cond-> {:dir dir
                            :source-checkouts source-checkouts}
                     (get source-checkouts repo)
                     (assoc :checkout (get source-checkouts repo)))))))

(defn- unit-status*
  "The comparison `unit-status` reports, given that the arguments applying to
  this unit's kind have already been vetted: `:checkout` is ignored for a
  composed unit and `:source-checkouts`/`:source-status-fn` for a source unit,
  where the public entry point rejects them instead."
  [{:keys [dir manifest checkout ref root source-checkouts source-status-fn]}]
  (let [dir (str dir)
        manifest (or manifest (read-manifest! dir))
        mode (get-in manifest [:analysis-run :mode])
        base (cond-> {:dir dir
                      :repo (:repo manifest)
                      :source (:source manifest)
                      :updated (:updated manifest)
                      :staleness (:staleness manifest)}
               (:variant manifest) (assoc :variant (:variant manifest)))]
    (if (some? mode)
      (composed-unit-status base manifest
                            (or root (layout/->default-root dir
                                                            (select-keys manifest [:repo :variant])))
                            (->source-status-fn source-status-fn source-checkouts unit-status*))
      (source-unit-status base manifest
                          (when (some? checkout)
                            (checkout-comparison checkout ref))
                          (LocalDate/now)))))

(defn unit-status
  "Is the unit in `dir` current? Returns the report map:

    {:dir       the unit dir, as given
     :repo      the manifest's `:repo`
     :variant   the manifest's top-level `:variant`, when it has one
     :kind      `:source`, or `:aggregate` with `:mode`
     :source    the manifest's `:source` git identity
     :updated   the manifest's `:updated`
     :staleness the manifest's `:staleness` policy
     :compared  `{:checkout :ref :sha}`, when `--checkout` was given
     :verdict   `:current`, `:stale`, or `:unknown`
     :reasons   what decided the verdict (informational entries marked)
     :sources   both links per source, composed units only}

  A source unit is current when its manifest's `:source :sha` is the sha its
  checkout resolves to. A composed unit's manifest records a sha per source
  unit; being current needs two things of every source, and this function
  reports both — the composition's record (`:composition`) and that source
  unit's own report (`:unit-status`), under `:sources`:

  - link B: the source unit's manifest still holds the sha the composition
    recorded;
  - link A: that source unit is itself current with its code — which the
    composition cannot know, since a checkout is the reader's to name.

  `:manifest` skips the read from `dir` (tests, hosts holding it already).
  `:checkout`/`:ref` compare a source unit against a checkout (`:ref` defaults
  to `HEAD`, which compares only when the checkout is on the unit's recorded
  branch; an explicit `:ref` names the commit to compare). `:checkout` on an
  aggregate throws, since it has one checkout per source, and `:root` on a
  source unit throws, since it has no sources to locate.

  A composed unit takes at most one of `:source-checkouts` and
  `:source-status-fn`; both throw. `:source-checkouts` maps a repo name to a
  checkout path, and each source unit is checked against its repo's checkout —
  two sources of one repo share it. `:source-status-fn` is
  `(fn [{:keys [unit-key repo variant dir]}] -> status-report)`, called once per
  source unit whose manifest exists: a host uses it when its own report for a
  source unit carries more than this namespace's checks. Neither applies to a
  source unit."
  [{:keys [dir manifest checkout root source-checkouts source-status-fn] :as opts}]
  (let [manifest (or manifest (read-manifest! (str dir)))
        aggregate? (some? (get-in manifest [:analysis-run :mode]))]
    (if aggregate?
      (do
        (when (some? checkout)
          (throw (ex-info "A composed unit has one checkout per source: pass :source-checkouts (--source-checkout), not :checkout (--checkout)"
                          {:dir (str dir) :checkout (str checkout)})))
        (when (and (some? source-checkouts) (some? source-status-fn))
          (throw (ex-info "Give :source-checkouts or :source-status-fn, not both"
                          {:dir (str dir)}))))
      (do
        (when (some? root)
          (throw (ex-info "A source unit has no sources to locate: :root (--root) applies only to a composed unit"
                          {:dir (str dir) :root (str root)})))
        (when (or (some? source-checkouts) (some? source-status-fn))
          (throw (ex-info "A source unit has no sources to judge: :source-checkouts (--source-checkout) and :source-status-fn apply only to a composed unit"
                          {:dir (str dir)})))))
    (unit-status* (assoc opts :manifest manifest))))
