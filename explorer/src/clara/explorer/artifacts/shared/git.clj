(ns ^{:clara-rules-explorer/bb-loaded true} clara.explorer.artifacts.shared.git
  "Git reads shared by the JVM manifest writer and the babashka `status`
  report: a checkout's identity (remote + sha + branch + working-tree), with
  remote-branch resolution for detached checkouts.

  `clara.explorer.artifacts.manifest/get-git-info` delegates to `get-git-info`
  here, so the detached-checkout resolution has a single implementation.

  A missing dir or a non-repo surfaces as nil — `git rev-parse` exits non-zero
  there — so no filesystem checks are needed."
  (:require
   [clojure.java.shell :as sh]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; git reads
;; ===========================================================================

(defn- git
  "Trimmed stdout of `git -C dir args`, or nil when git exits non-zero
  (missing dir, not a repo, unknown ref)."
  [dir & args]
  (let [{:keys [exit out]} (apply sh/sh "git" "-C" (str dir) args)]
    (when (zero? exit)
      (str/trim (str out)))))

;; ===========================================================================
;; checkout reads — the seam `clara.explorer.artifacts.shared.status` compares
;; a unit against through, so bb and the JVM resolve refs the same way.
;; ===========================================================================

(defn ref-sha
  "The sha `ref` (default `HEAD`) resolves to in `dir`'s checkout, or nil when
  it resolves to nothing there."
  [dir ref]
  (git dir "rev-parse" (or ref "HEAD")))

(defn remote-url
  "The checkout's `origin` remote url, or nil when it has none."
  [dir]
  (git dir "remote" "get-url" "origin"))

(defn clean-tree?
  "Whether `dir`'s checkout has no uncommitted changes."
  [dir]
  (str/blank? (git dir "status" "--porcelain")))

(defn- strip-remote
  "Remote-tracking ref → branch name: `origin/main` becomes `main`.
  `origin/HEAD` is not a branch, so it strips to nil rather than to `HEAD`."
  ^String [ref-name remote]
  (let [prefix (str remote "/")]
    (when (and (seq ref-name) (str/starts-with? ref-name prefix))
      (let [stripped (subs ref-name (count prefix))]
        (when (and (seq stripped) (not= "HEAD" stripped))
          stripped)))))

(defn- default-branch-at
  "The remote's default branch name when it points at `sha`, else nil.
  `git symbolic-ref --short refs/remotes/origin/HEAD` names e.g. `origin/main`,
  which is kept only if it resolves to the checkout's own sha."
  ^String [dir sha remote]
  (when-let [ref-name (git dir "symbolic-ref" "--short"
                           (format "refs/remotes/%s/HEAD" remote))]
    (when (= sha (git dir "rev-parse" ref-name))
      (strip-remote ref-name remote))))

(defn- any-branch-at
  "First sorted remote-tracking branch on `remote` pointing at the checkout's
  commit, sans remote alias. Nil when none does — a local branch that happens
  to point at a detached commit says nothing about what the commit was synced
  against, so only `refs/remotes` refs are considered."
  ^String [dir remote]
  (->> (str/split-lines (or (git dir "for-each-ref" "--points-at" "HEAD"
                                 "--format=%(refname:short)"
                                 (format "refs/remotes/%s/" remote))
                            ""))
       (keep #(strip-remote (str/trim %) remote))
       sort
       first))

(defn- resolve-branch
  "The branch the analyzed commit belongs to. An attached checkout names its
  own branch, pushed or not. A detached checkout (`rev-parse --abbrev-ref HEAD`
  reporting the literal `HEAD`) resolves to the remote branch — on the checkout's
  `origin` — pointing at the commit: the remote default first, then the first
  sorted remote-tracking ref. `HEAD` is not a branch, so an unresolvable commit
  records nil."
  ^String [dir sha remote branch]
  (if (and (seq branch) (not= "HEAD" branch))
    branch
    (or (default-branch-at dir sha remote)
        (any-branch-at dir remote))))

(defn get-git-info
  "State-of-the-world for a repo dir, or nil if `dir` is missing / not a git
  repo.

  Records the checkout's *identity* (remote + sha + branch), never its location:
  manifests are meant to be committed beside the artifacts they describe, so a
  local filesystem path would be noise to every reader but the machine that
  wrote it. Where a reader's own checkout lives is their own to answer.

  `:branch` names the branch the analyzed commit belongs to — the checkout's
  own branch when attached, the remote branch pointing at the commit when
  detached — and is nil when no remote branch points at it."
  [dir]
  (when-let [sha (ref-sha dir "HEAD")]
    {:remote (remote-url dir)
     :sha sha
     :sha-short (subs sha 0 7)
     :branch (resolve-branch dir sha "origin"
                             (git dir "rev-parse" "--abbrev-ref" "HEAD"))
     :working-tree (if (clean-tree? dir) "clean" "dirty")}))
