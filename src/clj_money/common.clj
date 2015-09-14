(ns clj-money.common
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d :refer [q]])
  (:gen-class))

(def earliest-date #inst "1900-01-01")

(defn get-ident
  "Returns the identifier for the database entity with the specified id"
  [db id]
  (first (d/q '[:find [?ident]
                :in $ ?id
                :where [?id :db/ident ?ident]]
              db,
              id)))

(def left-side?
  (d/function '{:lang :clojure
                :params [account]
                :code (contains?
                        #{:account.type/asset :account.type/expense}
                        (:account/type account))}))

(def right-side?
  (d/function '{:lang :clojure
                :params [account]
                :code (not (clj-money.common/left-side? account))}))

(def polarizer
  (d/function '{:lang :clojure
                :params [account action]
                :code (if (or (and (clj-money.common/left-side? account) (= :transaction-item.action/debit action))
                              (and (clj-money.common/right-side? account) (= :transaction-item.action/credit action)))
                        1
                        -1)}))

(def adjust-balance
  (d/function '{:lang :clojure
                :params [db id amount action]
                :code (let [e (d/entity db id)
                            account (d/touch e)
                            pol (clj-money.common/polarizer account action)
                            current-balance (:account/balance account)
                            polarized-amount (* pol amount)
                            new-balance (+ current-balance polarized-amount)]
                        [[:db/add id :account/balance new-balance]])}))

(def schema (load-file "resources/datomic/schema.edn"))

(def settings (load-file "config/settings.edn"))

(def uri (:datomic-uri settings))

(defn init-database
  []
  (log/info "creating the database at " uri "...")
  (d/create-database uri)
  (log/info "created database at " uri)
  (let [c (d/connect uri)]
    (d/transact c schema)
    (log/info "created the schema in database at " uri)))

(defn entity-map->hash-map
  "Accepts an EntityMap and returns a run-of-the-mill hash map"
  [entity]
  (assoc (into {} entity)
         :db/id
         (:db/id entity)))

(defn hydrate-entity
  "Given an ID, returns an entity map with all the entity details"
  [db id]
  (->> id
       first
       (d/entity db)
       (d/touch)))