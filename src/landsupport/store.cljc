(ns landsupport.store
  "SSoT for the land-transport-infrastructure-support operations actor
  (ISIC 5221 -- service activities incidental to land transportation:
  toll-road/bridge/tunnel/parking-facility/bus-terminal operation
  support), behind a `Store` protocol so the backend is a swap, not a
  rewrite -- the same seam every prior `cloud-itonami-isic-*` actor in
  this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/landsupport/store_contract_test.clj), which is the whole point:
  the actor, the Land Transport Support Governor and the audit ledger
  never know which SSoT they run on.

  IMPORTANT scope note: this actor is an OPERATIONS COORDINATION actor,
  not a structural-safety authority or facility-equipment controller.
  Every op it can ever commit carries `:effect :propose` -- it never
  performs a real toll-lane dispatch, recovery job, or structural-
  safety-clearance finalization itself (see `landsupport.governor`).
  A facility's `:registered?`/`:verified?` flags represent an
  INDEPENDENT external permit/registration authority's determination
  (e.g. a toll-road concession registry, a bus-terminal operating
  license) -- this actor never sets them itself, it only reads them."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [langchain.db :as d]))

(defprotocol Store
  (facility [s id])
  (all-facilities [s])
  (facility-record-log [s facility-id] "append-only :log-facility-record entries for a facility")
  (maintenance-schedule-log [s facility-id] "append-only :schedule-facility-maintenance proposals for a facility")
  (concern-log [s facility-id] "append-only :flag-structural-safety-concern entries for a facility")
  (supply-order-log [s facility-id] "append-only :coordinate-supply-order proposals for a facility")
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-facilities [s facilities] "replace/seed the facility directory (map id->facility)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained facility set covering the governor's HARD
  checks (an unregistered facility, an unverified facility, a facility
  with an unresolved structural-safety concern already on file) so the
  actor + tests run offline."
  []
  {:facilities
   {"facility-1" {:id "facility-1" :facility-name "Sakura Tollway Plaza 3"
                  :facility-type :toll-road
                  :registered? true :verified? true
                  :structural-safety-concern-unresolved? false
                  :jurisdiction "JPN"}
    "facility-2" {:id "facility-2" :facility-name "Riverside Parking Facility"
                  :facility-type :parking-facility
                  :registered? true :verified? true
                  :structural-safety-concern-unresolved? false
                  :jurisdiction "USA"}
    "facility-3" {:id "facility-3" :facility-name "Unregistered Overflow Lot"
                  :facility-type :parking-facility
                  :registered? false :verified? false
                  :structural-safety-concern-unresolved? false
                  :jurisdiction "USA"}
    "facility-4" {:id "facility-4" :facility-name "田中橋 Overpass Toll Gantry"
                  :facility-type :bridge
                  :registered? true :verified? true
                  :structural-safety-concern-unresolved? true
                  :jurisdiction "JPN"}
    "facility-5" {:id "facility-5" :facility-name "Pending-Verification Bus Terminal"
                  :facility-type :bus-terminal
                  :registered? true :verified? false
                  :structural-safety-concern-unresolved? false
                  :jurisdiction "GBR"}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- append-entry [coll entry] (conj (vec coll) entry))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (facility [_ id] (get-in @a [:facilities id]))
  (all-facilities [_] (sort-by :id (vals (:facilities @a))))
  (facility-record-log [_ facility-id] (get-in @a [:facility-records facility-id] []))
  (maintenance-schedule-log [_ facility-id] (get-in @a [:maintenance-schedules facility-id] []))
  (concern-log [_ facility-id] (get-in @a [:concerns facility-id] []))
  (supply-order-log [_ facility-id] (get-in @a [:supply-orders facility-id] []))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [op path value]}]
    (let [facility-id (first path)]
      (case op
        :log-facility-record
        (swap! a update-in [:facility-records facility-id] append-entry value)

        :schedule-facility-maintenance
        (swap! a update-in [:maintenance-schedules facility-id] append-entry value)

        :flag-structural-safety-concern
        (swap! a (fn [state]
                   (-> state
                       (update-in [:concerns facility-id] append-entry value)
                       (assoc-in [:facilities facility-id :structural-safety-concern-unresolved?] true))))

        :coordinate-supply-order
        (swap! a update-in [:supply-orders facility-id] append-entry value)

        nil))
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-facilities [s facilities] (when (seq facilities) (swap! a assoc :facilities facilities)) s))

(defn seed-db
  "A MemStore seeded with the demo facility set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :facility-records {} :maintenance-schedules {}
                           :concerns {} :supply-orders {} :ledger []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (log entries, ledger facts) are stored as EDN
  strings so `langchain.db` doesn't expand them into sub-entities --
  the same convention every sibling actor's store uses."
  {:facility/id                    {:db/unique :db.unique/identity}
   :facility-record/seq            {:db/unique :db.unique/identity}
   :maintenance-schedule/seq       {:db/unique :db.unique/identity}
   :concern/seq                    {:db/unique :db.unique/identity}
   :supply-order/seq               {:db/unique :db.unique/identity}
   :ledger/seq                     {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- facility->tx [{:keys [id facility-name facility-type registered? verified?
                             structural-safety-concern-unresolved? jurisdiction]}]
  (cond-> {:facility/id id}
    facility-name (assoc :facility/facility-name facility-name)
    facility-type (assoc :facility/facility-type facility-type)
    (some? registered?) (assoc :facility/registered? registered?)
    (some? verified?) (assoc :facility/verified? verified?)
    (some? structural-safety-concern-unresolved?)
    (assoc :facility/structural-safety-concern-unresolved? structural-safety-concern-unresolved?)
    jurisdiction (assoc :facility/jurisdiction jurisdiction)))

(def ^:private facility-pull
  [:facility/id :facility/facility-name :facility/facility-type
   :facility/registered? :facility/verified?
   :facility/structural-safety-concern-unresolved? :facility/jurisdiction])

(defn- pull->facility [m]
  (when (:facility/id m)
    {:id (:facility/id m) :facility-name (:facility/facility-name m)
     :facility-type (:facility/facility-type m)
     :registered? (boolean (:facility/registered? m))
     :verified? (boolean (:facility/verified? m))
     :structural-safety-concern-unresolved? (boolean (:facility/structural-safety-concern-unresolved? m))
     :jurisdiction (:facility/jurisdiction m)}))

(defn- entry-log [conn attr-ns facility-id]
  (->> (d/q [:find '?s '?e :where
             ['?k (keyword attr-ns "facility-id") facility-id]
             ['?k (keyword attr-ns "seq") '?s]
             ['?k (keyword attr-ns "entry") '?e]]
            (d/db conn))
       (sort-by first)
       (mapv (comp dec* second))))

(defrecord DatomicStore [conn]
  Store
  (facility [_ id]
    (pull->facility (d/pull (d/db conn) facility-pull [:facility/id id])))
  (all-facilities [_]
    (->> (d/q '[:find [?id ...] :where [?e :facility/id ?id]] (d/db conn))
         (map #(pull->facility (d/pull (d/db conn) facility-pull [:facility/id %])))
         (sort-by :id)))
  (facility-record-log [_ facility-id] (entry-log conn "facility-record" facility-id))
  (maintenance-schedule-log [_ facility-id] (entry-log conn "maintenance-schedule" facility-id))
  (concern-log [_ facility-id] (entry-log conn "concern" facility-id))
  (supply-order-log [_ facility-id] (entry-log conn "supply-order" facility-id))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [op path value]}]
    (let [facility-id (first path)
          attr-ns (case op
                    :log-facility-record "facility-record"
                    :schedule-facility-maintenance "maintenance-schedule"
                    :flag-structural-safety-concern "concern"
                    :coordinate-supply-order "supply-order"
                    nil)]
      (when attr-ns
        (let [next-n (count (entry-log conn attr-ns facility-id))]
          (d/transact! conn
                       [{(keyword attr-ns "facility-id") facility-id
                         (keyword attr-ns "seq") next-n
                         (keyword attr-ns "entry") (enc value)}])
          (when (= op :flag-structural-safety-concern)
            (d/transact! conn [{:facility/id facility-id
                                :facility/structural-safety-concern-unresolved? true}])))))
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-facilities [s facilities]
    (when (seq facilities) (d/transact! conn (mapv facility->tx (vals facilities)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:facilities ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [facilities]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-facilities s facilities))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo facility set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
