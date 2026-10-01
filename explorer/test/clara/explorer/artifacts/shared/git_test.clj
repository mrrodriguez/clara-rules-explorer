(ns clara.explorer.artifacts.shared.git-test
  "Detached-checkout branch resolution over real temp git repos.

  No network: remote-tracking refs are created with `update-ref` /
  `symbolic-ref`, which is exactly what a fetch would have left behind.
  Skipped, loudly, when `git` is not on PATH."
  (:require
   [clara.explorer.artifacts.shared.git :as git]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

;; ===========================================================================
;; temp repos
;; ===========================================================================

(defn- git-available?
  []
  (try (zero? (:exit (sh/sh "git" "--version")))
       (catch java.io.IOException _ false)))

(defn- make-temp-dir
  [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-tree
  [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(defn- repo-fixture-works?
  "Whether this environment lets a test create a git repo. Some sandboxes
  deny writes under `.git/` paths, in which case these tests cannot establish
  their fixture at all."
  []
  (try
    (let [dir (make-temp-dir "clara-git-probe")]
      (try
        (zero? (:exit (sh/sh "git" "init" "-q" "-b" "main" :dir dir)))
        (finally (delete-tree dir))))
    (catch Exception _ false)))

(defn- sh!
  "Run `git args` in `dir`, asserting success and returning trimmed stdout."
  [dir & args]
  (let [{:keys [exit err out]} (apply sh/sh "git" (concat args [:dir dir]))]
    (is (zero? exit) (format "git %s failed: %s" (str/join " " args) err))
    (str/trim out)))

(defn- commit!
  "An empty commit on the current branch, returning its sha."
  [dir msg]
  (sh! dir "-c" "user.email=git-test@example.com" "-c" "user.name=git-test"
       "commit" "--allow-empty" "-q" "-m" msg)
  (sh! dir "rev-parse" "HEAD"))

(defn- make-repo!
  "A temp repo with one commit on `main`, as `{:dir :sha}`."
  []
  (let [dir (make-temp-dir "clara-git-test")]
    (sh! dir "init" "-q" "-b" "main")
    {:dir dir :sha (commit! dir "one")}))

(defn- point-origin!
  "Fake a fetch: `refs/remotes/origin/<branch>` at `sha`, with `origin/HEAD`
  pointing at `default-branch`."
  [dir sha branch default-branch]
  (sh! dir "update-ref" (str "refs/remotes/origin/" branch) sha)
  (sh! dir "symbolic-ref" "refs/remotes/origin/HEAD"
       (str "refs/remotes/origin/" default-branch)))

(defn- with-repo*
  "Run `(f repo)` with a fresh `{:dir :sha}` repo, deleting it afterwards."
  [f]
  (let [repo (make-repo!)]
    (try (f repo) (finally (delete-tree (:dir repo))))))

;; ===========================================================================
;; resolution
;; ===========================================================================

(deftest detached-checkout-branch-resolution-test
  (if-not (and (git-available?) (repo-fixture-works?))
    (println "SKIPPING git-test — git is not on PATH, or this environment cannot create git repos")
    (do
      (testing "an attached checkout names its own branch"
        (with-repo* (fn [repo]
                      (is (= "main" (:branch (git/get-git-info (:dir repo))))))))

      (testing "a detached checkout at the remote default records that branch"
        (with-repo* (fn [repo]
                      (point-origin! (:dir repo) (:sha repo) "main" "main")
                      (sh! (:dir repo) "checkout" "-q" "--detach" "HEAD")
                      (is (= "main" (:branch (git/get-git-info (:dir repo))))))))

      (testing "a detached worktree records the same branch as a checkout"
        (with-repo* (fn [repo]
                      (point-origin! (:dir repo) (:sha repo) "main" "main")
                      (let [wt (str (io/file (make-temp-dir "clara-git-wt") "wt"))]
                        (try
                          (sh! (:dir repo) "worktree" "add" "--detach" wt "HEAD")
                          (is (= "main" (:branch (git/get-git-info wt))))
                          (finally
                            (sh! (:dir repo) "worktree" "remove" "--force" wt)
                            (delete-tree (.getParent (io/file wt)))))))))

      (testing "a detached and an attached checkout of one commit agree"
        (with-repo* (fn [repo]
                      (point-origin! (:dir repo) (:sha repo) "main" "main")
                      (let [attached (git/get-git-info (:dir repo))]
                        (sh! (:dir repo) "checkout" "-q" "--detach" "HEAD")
                        (is (= attached (git/get-git-info (:dir repo))))))))

      (testing "past the default, the first sorted remote-tracking ref wins"
        (with-repo* (fn [repo]
                      (let [{:keys [dir sha]} repo
                            other (commit! dir "two")]
                        (point-origin! dir sha "main" "main")
                        (sh! dir "update-ref" "refs/remotes/origin/zz-top" other)
                        (sh! dir "update-ref" "refs/remotes/origin/aa-branch" other)
                        (sh! dir "checkout" "-q" "--detach" other)
                        (is (= "aa-branch" (:branch (git/get-git-info dir))))))))

      (testing "a commit only a local branch points at records nil"
        (with-repo* (fn [repo]
          ;; `main` still points at the commit: local branches say nothing
          ;; about what a detached commit was synced against.
                      (sh! (:dir repo) "checkout" "-q" "--detach" "HEAD")
                      (is (nil? (:branch (git/get-git-info (:dir repo))))))))

      (testing "a commit nothing points at records nil"
        (with-repo* (fn [repo]
                      (sh! (:dir repo) "checkout" "-q" "--detach" "HEAD")
                      (sh! (:dir repo) "update-ref" "-d" "refs/heads/main")
                      (is (nil? (:branch (git/get-git-info (:dir repo))))))))

      (testing "never the literal HEAD"
        (with-repo* (fn [repo]
                      (sh! (:dir repo) "checkout" "-q" "--detach" "HEAD")
                      (is (not= "HEAD" (:branch (git/get-git-info (:dir repo)))))))))))

(deftest git-info-boundaries-test
  (if-not (git-available?)
    (println "SKIPPING git-boundaries-test — git is not on PATH")
    (do
      (testing "a missing dir is nil"
        (is (nil? (git/get-git-info "/no/such/dir/clara-git-test"))))

      (testing "a non-repo dir is nil"
        (let [dir (make-temp-dir "clara-git-test")]
          (try
            (is (nil? (git/get-git-info dir)))
            (finally (delete-tree dir)))))

      (testing "the runner's own checkout never reports the literal HEAD"
        (let [info (git/get-git-info (System/getProperty "user.dir"))]
          (if (nil? info)
            (println "SKIPPING own-checkout assertion — test is not running in a git checkout")
            (is (not= "HEAD" (:branch info)))))))))
