(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.shared.status
  "Whether a persisted unit is current: the comparison behind the offline bb
  `status` report, shared with any JVM host that wants the same verdict.

  A host that persists extra inputs through `:blocks` (tool versions, its own
  configuration slices) calls `unit-status` and appends its own `:reasons`
  before rendering — host-specific staleness without the explorer knowing those
  inputs exist.

  bb-safe by construction: `layout`, `shared.git`, `shared.registry`,
  `clojure.edn`, `clojure.string`, and `java.time` only. Manifest reads are
  plain `slurp`; git reads go through `clara.explorer.artifacts.shared.git`,
  so bb and the JVM resolve refs the same way."
  (:require
   [clara.explorer.artifacts.layout :as layout]
   [clara.explorer.artifacts.shared.git :as git]
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
    :source-sha-drift :source-missing})

(def ^:private unknown-checks
  "Reason `:check`s that leave the verdict unknown — the comparison could not
  be made, which is not the same as the unit having drifted."
  #{:sha-not-compared :ref-unresolvable :checkout-not-a-repo
    :aggregate-no-sources})

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

(defn- checkout-comparison
  "What `--checkout`/`--ref` resolves to: the checkout path, the ref name, the
  sha it resolves to (nil when it resolves to nothing), the checkout's origin,
  and whether its tree is clean. A nil sha with a readable remote means the ref
  is unknown there; nil for both means the checkout is missing or not a repo."
  [checkout ref]
  {:checkout (str checkout)
   :ref (or ref "HEAD")
   :sha (git/ref-sha checkout (or ref "HEAD"))
   :remote (git/remote-url checkout)
   :clean? (git/clean-tree? checkout)})

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
  "`repo[@branch]` back into `{:repo :branch}`. Splits on the last `@`, so a
  `@` inside the repo survives."
  [unit-key]
  (let [s (str unit-key)]
    (if-let [i (str/last-index-of s "@")]
      {:repo (subs s 0 i)
       :branch (let [branch (subs s (inc i))]
                 (when-not (str/blank? branch) branch))}
      {:repo s})))

(defn- source-locator
  "How to find `unit-key`'s unit dir: its `{:repo :branch}`, taken from the
  manifest's `:analysis-run :units` entry when one matches, else parsed back
  out of the key."
  [manifest unit-key]
  (or (some #(when (= unit-key (shared-registry/unit-key %))
               (select-keys % [:repo :branch]))
            (get-in manifest [:analysis-run :units]))
      (parse-unit-key unit-key)))

(defn- source-current-sha
  "The `:source :sha` the source unit's own manifest under `root` records now,
  or nil when that unit (or its manifest) is gone."
  [root {:keys [repo branch]}]
  (some-> (read-manifest-or-nil (layout/unit-dir {:root root
                                                  :repo repo
                                                  :branch branch}))
          (get-in [:source :sha])))

(defn- check-source
  "One composed source's comparison: `current`, `sha-drift`, or `missing`,
  with the reason the non-current cases contribute."
  [manifest root unit-key recorded]
  (let [locator (source-locator manifest unit-key)
        current (source-current-sha root locator)]
    (merge {:source unit-key
            :recorded (:sha recorded)
            :current current}
           (cond
             (nil? current) {:verdict :missing
                             :reason {:check :source-missing :source unit-key}}
             (= (:sha recorded) current) {:verdict :current}
             :else {:verdict :sha-drift
                    :reason {:check :source-sha-drift
                             :source unit-key
                             :recorded (:sha recorded)
                             :current current}}))))

(defn- composed-unit-status
  "Result map for a composed unit (`:analysis-run :mode` present with
  `:staleness :sources`). Current only if every source is."
  [base manifest root]
  (let [sources (get-in manifest [:staleness :sources])
        mode (get-in manifest [:analysis-run :mode])]
    (if-not (seq sources)
      (assoc base
             :kind :aggregate
             :mode mode
             :verdict :unknown
             :reasons [{:check :aggregate-no-sources}])
      (let [checked (mapv (fn [[unit-key recorded]]
                            (check-source manifest root (str unit-key) recorded))
                          (sort-by key sources))
            reasons (into [] (keep :reason) checked)]
        (assoc base
               :kind :aggregate
               :mode mode
               :verdict (->verdict reasons)
               :reasons reasons
               :sources (mapv #(dissoc % :reason) checked))))))

;; ===========================================================================
;; entry point
;; ===========================================================================

(defn unit-status
  "Is the unit in `dir` current? Returns the report map:

    {:dir       the unit dir, as given
     :repo      the manifest's `:repo`
     :label     the manifest's top-level `:branch`, when it has one
     :kind      `:source`, or `:aggregate` with `:mode`
     :source    the manifest's `:source` git identity
     :updated   the manifest's `:updated`
     :staleness the manifest's `:staleness` policy
     :compared  `{:checkout :ref :sha}`, when `--checkout` was given
     :verdict   `:current`, `:stale`, or `:unknown`
     :reasons   what decided the verdict (informational entries marked)
     :sources   per-source comparisons, composed units only}

  `:manifest` skips the read from `dir` (tests, hosts holding it already).
  `:checkout`/`:ref` compare a source unit against a checkout (`:ref` defaults
  to `HEAD`); `:checkout` on an aggregate throws, since it has one checkout
  per source. `:root` locates a composed unit's sources and defaults to `dir`
  with the manifest's `:repo` (and `branches/<label>`) stripped from the end."
  [{:keys [dir manifest checkout ref root]}]
  (let [dir (str dir)
        manifest (or manifest (read-manifest! dir))
        mode (get-in manifest [:analysis-run :mode])
        base (cond-> {:dir dir
                      :repo (:repo manifest)
                      :source (:source manifest)
                      :updated (:updated manifest)
                      :staleness (:staleness manifest)}
               (:branch manifest) (assoc :label (:branch manifest)))]
    (if (some? mode)
      (do
        (when (some? checkout)
          (throw (ex-info "A composed unit has one checkout per source: pass --root, not --checkout"
                          {:dir dir :checkout (str checkout)})))
        (composed-unit-status base manifest
                              (or root (layout/default-root dir (select-keys manifest [:repo :branch])))))
      (source-unit-status base manifest
                          (when (some? checkout)
                            (checkout-comparison checkout ref))
                          (LocalDate/now)))))
