(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.status
  "Whether a persisted unit is current: the comparison behind the offline bb
  `status` report, shared with any JVM host that wants the same verdict.

  A host that persists extra inputs through `:blocks` (tool versions, its own
  configuration slices) calls `unit-status` and appends its own `:reasons`
  before rendering — host-specific staleness without the explorer knowing those
  inputs exist.

  A composed unit is only as current as its sources: `unit-status` reports the
  composition's record beside each source unit's own status report, and the
  caller says where checkouts live (`:source-checkouts`) and optionally supplies
  that report itself (`:source-status-fn`, which receives each source's checkout).
  A composition cannot look for checkouts on its
  own — where a reader's checkouts live is the reader's business, as
  `clara.explorer.artifacts.manifest` says of the manifest it writes. A source
  unit whose report cannot be established — missing, or a source already on the
  walk — is unknown, never current: no comparison ran."
  (:require
   [clara.explorer.artifacts.layout :as layout]
   [clara.explorer.artifacts.git :as git]
   [clara.explorer.artifacts.shared.registry :as shared-registry]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [schema.core :as s])
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

(defn- ->manifest-path
  "Path of the manifest file inside unit dir `dir`."
  [dir]
  (str dir "/" (:manifest layout/artifact-files)))

(defn- read-manifest-or-nil
  "The parsed manifest in `dir`, or nil when it cannot be read."
  [dir]
  (try
    (edn/read-string {:default (fn [_tag v] v)} (slurp (->manifest-path dir)))
    (catch Exception _ nil)))

(defn- read-manifest!
  "The parsed manifest in `dir`, throwing when it cannot be read."
  [dir]
  (or (read-manifest-or-nil dir)
      (throw (ex-info (format "No readable manifest in unit dir: %s" (str dir))
                      {:dir (str dir) :manifest (->manifest-path dir)}))))

(defn- ->dir-key
  "A comparable key for one unit dir: repeated separators collapsed and a
  trailing one dropped, so spellings of one dir match in `:seen`. Lexical only —
  not a canonical path: it resolves no symlinks or `.`/`..` and never touches the
  filesystem, since a source unit's dir may be gone."
  [dir]
  (str (io/file (str dir))))

;; ===========================================================================
;; age
;; ===========================================================================

(defn- ->age-days
  "Days from `:updated` (`YYYY-MM-DD`) to `today`, or nil when unparseable."
  [updated today]
  (try
    (int (.between ChronoUnit/DAYS (LocalDate/parse ^CharSequence (str updated)) today))
    (catch Exception _ nil)))

(defn- ->age-reason
  "The `:age-exceeded` reason when `:updated` is older than the policy's
  `:max-age-days`, else nil. Skipped when either is absent or unparseable."
  [updated max-age-days today]
  (when (and (some? max-age-days) (number? max-age-days))
    (when-let [age (->age-days updated today)]
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

;; ===========================================================================
;; source units
;; ===========================================================================

(defn- ->checkout-comparison
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
   :branch (git/get-attached-branch checkout)
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

(defn- ->sha-reasons
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

(defn- ->source-unit-status
  "Result map for a source unit (no `:analysis-run :mode`). `compared` is
  `->checkout-comparison` when `--checkout` was given, else nil."
  [base manifest compared today]
  (let [source (:source manifest)
        age-reason (->age-reason (:updated manifest)
                                 (get-in manifest [:staleness :max-age-days])
                                 today)
        reasons (cond-> (->sha-reasons source compared)
                  (= "dirty" (:working-tree source))
                  (conj {:check :generated-dirty
                         :working-tree (:working-tree source)})

                  (and (some? compared) (not (:clean? compared)))
                  (conj {:check :checkout-dirty
                         :checkout (:checkout compared)
                         :informational true})

                  (some? age-reason)
                  (conj age-reason))]
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

(defn- resolve-source-locator
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
  (let [locator (resolve-source-locator manifest unit-key)]
    (assoc locator :dir (layout/->unit-dir (assoc locator :root root)))))

(defn- ->composition-report
  "The composition's side of one source: `:recorded` is the sha the
  composition recorded, `:unit-sha` the sha the source unit's manifest holds
  now (nil when that unit or its manifest is gone), and `:verdict` whether the
  two agree."
  [recorded unit-sha]
  {:recorded (:sha recorded)
   :unit-sha unit-sha
   :verdict (if (and (some? unit-sha) (= (:sha recorded) unit-sha))
              :current
              :stale)})

(defn- ->composition-reason
  "The composition's reason when its recorded sha no longer matches the source
  unit's manifest: `:source-sha-drift` when there is a sha to compare,
  `:source-missing` when the unit (or its manifest) is gone. Nothing when the
  two agree."
  [unit-key {:keys [verdict recorded unit-sha]}]
  (when (not= :current verdict)
    (if (some? unit-sha)
      {:check :source-sha-drift
       :unit-key unit-key
       :recorded recorded
       :current unit-sha}
      {:check :source-missing
       :unit-key unit-key})))

(defn- ->unit-unknown-reason
  "The `:unit-unknown` reason for a source whose own report could not be
  established at all, `why` naming the absence (`:source-cycle`,
  `:unit-report-missing`). An absence is unknown, never current: nothing was
  compared."
  [unit-key why]
  {:check :unit-unknown
   :unit-key unit-key
   :reasons [{:check why :unit-key unit-key}]})

(defn- ->source-unit-reason
  "A source unit's own contribution: its report's verdict, with that report's
  reasons nested so a reader sees which source decided it and why. A
  `:current` report contributes nothing."
  [unit-key report]
  (case (:verdict report)
    :stale {:check :unit-stale
            :unit-key unit-key
            :reasons (:reasons report)}
    :unknown {:check :unit-unknown
              :unit-key unit-key
              :reasons (:reasons report)}
    nil))

(defn- report?
  "Whether `report` is a usable source status report: a map whose `:verdict` is
  one of the three. Anything else — nil, or a map a host left the verdict off —
  establishes nothing about the unit, so `check-source` reports it as missing
  rather than folding an absent verdict into a current source."
  [report]
  (and (map? report)
       (contains? #{:current :stale :unknown} (:verdict report))))

(defn- check-source
  "Both comparisons for one composed source, as a `SourceEntry` plus its
  top-level reasons: the composition's record against the source unit's
  manifest, beside that unit's own status report."
  [{:keys [manifest root source-checkouts source-status-fn status-fn seen unit-key recorded]}]
  (let [{:keys [repo variant dir]} (get-source-unit manifest root unit-key)
        source-manifest (read-manifest-or-nil dir)
        unit-sha (get-in source-manifest [:source :sha])
        source-mode (get-in source-manifest [:analysis-run :mode])
        composition (->composition-report recorded unit-sha)
        cycled? (contains? seen (->dir-key dir))
        checkout (get source-checkouts repo)
        report (when (and (some? unit-sha) (not cycled?))
                 (if (some? source-mode)
                   (status-fn {:dir dir
                               :manifest source-manifest
                               :root root
                               :source-checkouts source-checkouts
                               :source-status-fn source-status-fn
                               :seen (conj seen (->dir-key dir))})
                   (if (some? source-status-fn)
                     (source-status-fn (cond-> {:unit-key unit-key
                                                :repo repo
                                                :dir dir
                                                :checkout checkout}
                                         (seq variant) (assoc :variant variant)))
                     (status-fn (cond-> {:dir dir
                                         :manifest source-manifest
                                         :seen (conj seen (->dir-key dir))}
                                  (some? checkout) (assoc :checkout checkout))))))
        report (when (report? report) report)
        composition-reason (->composition-reason unit-key composition)
        own-reason (when (some? unit-sha)
                     (cond
                       cycled? (->unit-unknown-reason unit-key :source-cycle)
                       (nil? report) (->unit-unknown-reason unit-key :unit-report-missing)
                       :else (->source-unit-reason unit-key report)))
        reasons (cond-> []
                  (some? composition-reason) (conj composition-reason)
                  (some? own-reason) (conj own-reason))]
    {:entry (cond-> {:unit-key unit-key
                     :composition composition
                     :verdict (->verdict reasons)}
              (some? report) (assoc :unit-status report))
     :reasons reasons}))

(defn- ->composed-unit-status
  "Result map for a composed unit (`:analysis-run :mode` present with
  `:staleness :sources`). Current only when every source passes both
  comparisons: the composition's record still matches the source unit, and
  that unit's own report says it still matches its code."
  [{:keys [base manifest root source-checkouts source-status-fn status-fn seen]}]
  (let [sources (get-in manifest [:staleness :sources])
        mode (get-in manifest [:analysis-run :mode])]
    (if-not (seq sources)
      (assoc base
             :kind :aggregate
             :mode mode
             :verdict :unknown
             :reasons [{:check :aggregate-no-sources}])
      (let [checked (mapv (fn [[unit-key recorded]]
                            (check-source {:manifest manifest
                                           :root root
                                           :source-checkouts source-checkouts
                                           :source-status-fn source-status-fn
                                           :status-fn status-fn
                                           :seen seen
                                           :unit-key (str unit-key)
                                           :recorded recorded}))
                          (sort-by key sources))
            reasons (into [] (mapcat :reasons) checked)]
        (assoc base
               :kind :aggregate
               :mode mode
               :verdict (->verdict reasons)
               :reasons reasons
               :sources (mapv :entry checked))))))

;; ===========================================================================
;; schemas
;; ===========================================================================

(s/defschema StatusVerdict
  "Every verdict, at every level of a `StatusReport`: whether the unit drifted,
  could not be compared, or is current."
  (s/enum :current :stale :unknown))

(s/defschema StatusReason
  "One entry of a `StatusReport`'s `:reasons`. Open, because a host's
  `:source-status-fn` report may carry checks only the host knows, which
  `clara.explorer.artifacts.status-report/reason-summary` renders through the
  host's own method."
  {:check s/Keyword
   s/Keyword s/Any})

(s/defschema StatusVariant
  "A source unit's `:variant`: the ordered `[axis value]` vector naming its
  place under `_variants/<repo>/`, always ending in `[:ref …]`."
  [[(s/one s/Keyword "axis") (s/one s/Str "value")]])

(s/defschema SourceComposition
  "The composition's side of one `StatusReport` `:sources` entry: the sha the
  composition recorded against the sha the source unit's manifest holds now
  (nil when that unit or its manifest is gone), and whether the two agree."
  {:recorded s/Str
   :unit-sha (s/maybe s/Str)
   :verdict StatusVerdict})

(s/defschema HostStatusReport
  "A host's own source-unit report, as a `:source-status-fn` returns it: any map
  carrying at least a `:verdict`. This namespace's own per-source reports are
  full `StatusReport` maps, which satisfy this shape too."
  {(s/required-key :verdict) StatusVerdict
   s/Keyword s/Any})

(s/defschema SourceEntry
  "One `StatusReport` `:sources` entry: the two comparisons for one source —
  the composition's record beside the source unit's own status report — and
  their fold. `:unit-status` is absent when the source unit is gone or its
  report was not usable; it is a `HostStatusReport` — a full `StatusReport`
  from this namespace, or the host's own report — so a composition as a
  source nests whole."
  {:unit-key s/Str
   :composition SourceComposition
   :verdict StatusVerdict
   (s/optional-key :unit-status) HostStatusReport})

(s/defschema StatusReport
  "What `unit-status` returns: the unit's identity from its manifest, the
  `:verdict` those comparisons decide, what decided it (`:reasons`,
  informational entries marked), and — for a composed unit — both comparisons
  per source (`:sources`)."
  {:dir s/Str
   :repo s/Str
   (s/optional-key :variant) StatusVariant
   :kind (s/enum :source :aggregate)
   (s/optional-key :mode) s/Any
   :source {s/Keyword s/Any}
   :updated s/Str
   :staleness {s/Keyword s/Any}
   (s/optional-key :compared) {:checkout s/Str
                               :ref s/Str
                               :sha (s/maybe s/Str)}
   :verdict StatusVerdict
   :reasons [StatusReason]
   (s/optional-key :sources) [SourceEntry]})

(s/defschema SourceStatusFnArg
  "What a `:source-status-fn` is called with, once per source unit: the unit's
  key, repo, and dir, plus the caller's checkout for that source's repo — nil
  when the caller named none, leaving the host free to use its own default."
  {:unit-key s/Str
   :repo s/Str
   :dir s/Str
   :checkout (s/maybe s/Str)
   (s/optional-key :variant) StatusVariant})

(s/defschema StatusOptions
  "Options for `unit-status`: the unit `:dir`, an already-read `:manifest`
  that skips the read, `:checkout`/`:ref` comparing a source unit against a
  checkout, and `:root`, `:source-checkouts`, and `:source-status-fn` judging
  a composed unit's sources. `:seen` is the walk of unit dirs that led here;
  the public call never passes it — recursion threads it, so a composition
  that reaches itself is reported rather than recursed into."
  {:dir s/Str
   (s/optional-key :manifest) {s/Keyword s/Any}
   (s/optional-key :checkout) s/Str
   (s/optional-key :ref) s/Str
   (s/optional-key :root) s/Str
   (s/optional-key :source-checkouts) {s/Str s/Str}
   (s/optional-key :source-status-fn) (s/=> StatusReport SourceStatusFnArg)
   (s/optional-key :seen) #{s/Str}})

;; ===========================================================================
;; entry point
;; ===========================================================================

(defn- ->unit-status
  "The comparison `unit-status` reports, given that the arguments applying to
  this unit's kind have already been vetted by `assert-kind-args!`. `:seen` is
  the walk of unit dirs that led here; it is seeded with this dir, so a
  composition that (directly or through another unit) contains itself is
  reported rather than recursed into."
  [{:keys [dir manifest checkout ref root source-checkouts source-status-fn seen]}]
  (let [dir (str dir)
        manifest (or manifest (read-manifest! dir))
        mode (get-in manifest [:analysis-run :mode])
        seen (or seen #{(->dir-key dir)})
        base (cond-> {:dir dir
                      :repo (:repo manifest)
                      :source (:source manifest)
                      :updated (:updated manifest)
                      :staleness (:staleness manifest)}
               (:variant manifest) (assoc :variant (:variant manifest)))]
    (if (some? mode)
      (->composed-unit-status
       {:base base
        :manifest manifest
        :root (or root (layout/->default-root dir
                                              (select-keys manifest [:repo :variant])))
        :source-checkouts source-checkouts
        :source-status-fn source-status-fn
        :status-fn ->unit-status
        :seen seen})
      (->source-unit-status base manifest
                            (when (some? checkout)
                              (->checkout-comparison checkout ref))
                            (LocalDate/now)))))

(defn- ->invalid-args-ex
  "An argument-guard failure: a usage error a host command line can tell apart
  from a failed comparison. Every guard throws through here, so `ex-data` always
  carries `::invalid-args`."
  [message data]
  (ex-info message (assoc data ::invalid-args true)))

(defn- assert-kind-args!
  "Reject the arguments that do not apply to `manifest`'s kind: a composed unit
  has one checkout per source, and a source unit has no sources to locate or
  judge. Each message names both the keyword and the flag, since one guard
  serves a host and the bb `status` subcommand. Every throw carries
  `::invalid-args`, so a host command line can report a usage error (exit 2)
  without matching message text."
  [dir manifest {:keys [checkout ref root source-checkouts source-status-fn]}]
  (if (some? (get-in manifest [:analysis-run :mode]))
    (if (seq (get-in manifest [:staleness :sources]))
      (do
        (when (some? checkout)
          (throw (->invalid-args-ex
                  "A composed unit has one checkout per source: pass :source-checkouts (--source-checkout), not :checkout (--checkout)"
                  {:dir (str dir) :checkout (str checkout)})))
        (when (some? ref)
          (throw (->invalid-args-ex
                  "A composed unit has one checkout per source: :ref (--ref) names a commit in one checkout, so it applies only with :checkout (--checkout) on a source unit"
                  {:dir (str dir) :ref (str ref)}))))
      (do
        (when (some? checkout)
          (throw (->invalid-args-ex
                  "An aggregate with no :staleness :sources has no sources and no checkout: :checkout (--checkout) does not apply"
                  {:dir (str dir) :checkout (str checkout)})))
        (when (some? ref)
          (throw (->invalid-args-ex
                  "An aggregate with no :staleness :sources has no sources and no checkout: :ref (--ref) does not apply"
                  {:dir (str dir) :ref (str ref)})))
        (when (some? source-checkouts)
          (throw (->invalid-args-ex
                  "An aggregate with no :staleness :sources has no sources and no checkout: :source-checkouts (--source-checkout) does not apply"
                  {:dir (str dir)})))
        (when (some? source-status-fn)
          (throw (->invalid-args-ex
                  "An aggregate with no :staleness :sources has no sources and no checkout: :source-status-fn does not apply"
                  {:dir (str dir)})))))
    (do
      (when (some? root)
        (throw (->invalid-args-ex
                "A source unit has no sources to locate: :root (--root) applies only to a composed unit"
                {:dir (str dir) :root (str root)})))
      (when (or (some? source-checkouts) (some? source-status-fn))
        (throw (->invalid-args-ex
                "A source unit has no sources to judge: :source-checkouts (--source-checkout) and :source-status-fn apply only to a composed unit"
                {:dir (str dir)}))))))

(s/defn unit-status :- StatusReport
  "Is the unit in `dir` current? Takes `StatusOptions`, returns a `StatusReport`."
  [{:keys [dir manifest] :as opts} :- StatusOptions]
  (let [manifest (or manifest (read-manifest! (str dir)))]
    (assert-kind-args! dir manifest opts)
    (->unit-status (assoc opts :manifest manifest))))

