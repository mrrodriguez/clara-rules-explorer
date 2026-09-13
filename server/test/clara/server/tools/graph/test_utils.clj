(ns clara.server.tools.graph.test-utils)

(defn capture-taps
  "Runs thunk `f` with a tap registered and returns `{:result … :events …}`.

   `events` is the vector of tapped events matching `event?` (default: all), collected until `done?`
  holds over them (default: `seq`, meaning at least one match) or `timeout-ms` (default: 2000)
  elapses. The tap is removed only *after* the wait.

   `f` runs exactly once; its return value is captured as `:result` so a call can assert on both its
  output and the events it tapped.

  `tap>` delivery is asynchronous since Clojure 1.11: `(tap> x)` enqueues the event on a
  background thread rather than invoking taps synchronously. Tests asserting on tapped events must
  therefore wait for that thread, and must not `remove-tap` before the wait — a tap removed while
  events are still queued silently drops them. `capture-taps` encapsulates both concerns."
  ([f] (capture-taps f (constantly true) seq 2000))
  ([f event?] (capture-taps f event? seq 2000))
  ([f event? done?] (capture-taps f event? done? 2000))
  ([f event? done? timeout-ms]
   (let [acc (atom [])
         tap-fn (fn [e] (swap! acc conj e))]
     (add-tap tap-fn)
     (try
       (let [result (f)
             events (loop [deadline (+ (System/nanoTime)
                                       (* timeout-ms 1000000))]
                      (let [kept (filterv event? @acc)]
                        (if (or (done? kept)
                                (> (System/nanoTime) deadline))
                          kept
                          (do (Thread/sleep 10)
                              (recur deadline)))))]
         {:result result
          :events events})
       (finally
         (remove-tap tap-fn))))))
