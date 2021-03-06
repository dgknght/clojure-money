(ns clj-money.transactions
  (:require [datomic.api :as d :refer [tempid q db transact pull-many pull]]
            [clojure.tools.logging :as log]
            [clojure.pprint :refer [pprint]]
            [clojure.set :refer [difference union]]
            [clj-money.common :refer [meta-filter]]
            [clj-time.core :as t]
            [clj-time.coerce :as coerce])
  (:use clj-money.common
        clj-money.accounts
        [clj-money.util :as util])
  (:gen-class))

(def max-date (t/date-time 9999 12 31))
(def min-date (t/date-time 1000  1  5))

(defn resolve-action
  "Looks up a transaction item action from a db/id"
  [db action]
  (if (keyword? action)
    action
    (get-ident db (:db/id action))))

(defn resolve-transaction-item-enums
  "Looks up references in a list of transaction item maps"
  [db item]
  (update item
          :transaction-item/action
         #(resolve-action db %)))

(defn resolve-transaction-items-enums
  "Looks up references in a list of transaction items"
  [db items]
  (mapv #(resolve-transaction-item-enums db %) items))

(defn resolve-transaction-enums
  "Looks up references in transaction map"
  [db transaction]
  (update transaction :transaction/items #(resolve-transaction-items-enums db %)))

(defn resolve-transactions-enums
  "Looks up references in a list of transaction maps"
  [db transactions]
  (map #(resolve-transaction-enums db %) transactions))

(defn get-transaction
  "Returns a transaction, given a transaction id"
  [db id]
  (d/touch (d/entity db id)))

(defn find-transaction-item
  "Given an id, finds the transaction item"
  [db id]
  (->> (d/entity db id)
       d/touch))

(defn lookup-transaction-items
  "Given a transaction, looks up the transaction items"
  [db transaction]
  (update transaction
          :transaction/items
          #(->> % (map :db/id) (pull-many db '[*]))))

(defn get-transactions
  "Returns all transactions"
  ([db]
   (->> (d/q
          '[:find ?t
            :where [?t :transaction/date _]]
          db)
        (map first)
        (pull-many db '[*])
        (map #(lookup-transaction-items db %))
        (resolve-transactions-enums db))))

(defn prepare-transaction-item-query-result
  "Accepts the raw results on a transaction item query, which is a sequence of tuples
  containing the transaction item id and the transaction id, in that order, and
  prepares it for return to the caller"
  [db account-id {sort-order :sort-order :or {sort-order :desc}} raw-result]
  (let [sort-compare (if (= :asc sort-order)
                       compare
                       #(compare %2 %1))]
    (->> raw-result
         (map (fn [tuple]
                (map #(pull db '[*] %) tuple)))
         (sort-by #(-> % first :transaction-item/index) sort-compare))))

(def default-get-account-transaction-item-options {:sort-order :desc
                                                   :start-date min-date
                                                   :end-date max-date
                                                   :inclusive? true})

(defn get-account-transaction-items
  "Returns tramsaction items referencing the specified account.

  The date should be specified as a clj-time (joda) date time. It will be converted to
  a java date for the purpose of the query."
  ([db account-id] (get-account-transaction-items db account-id {}))
  ([db account-id options]
   (let [{:keys [start-date end-date inclusive?]} (merge default-get-account-transaction-item-options options)
         query (if inclusive?
                 '[:find ?ti ?t
                   :in $ ?account-id ?start-date ?end-date
                   :where [?ti :transaction-item/account ?account-id]
                   [?t :transaction/items ?ti]
                   [?t :transaction/date ?transaction-date]
                   [(<= ?start-date ?transaction-date)]
                   [(>= ?end-date ?transaction-date)]]
                 '[:find ?ti ?t
                   :in $ ?account-id ?start-date ?end-date
                   :where [?ti :transaction-item/account ?account-id]
                   [?t :transaction/items ?ti]
                   [?t :transaction/date ?transaction-date]
                   [(< ?start-date ?transaction-date)]
                   [(> ?end-date ?transaction-date)]])]
     (->>  (d/q
             query
             db
             account-id
             (coerce/to-date start-date)
             (coerce/to-date end-date))
          (prepare-transaction-item-query-result db account-id options)))))

(defn item-total
  "returns the total of the debit or credit actions"
  [action items]
  (->> items
       (clojure.core/filter #(= action (:transaction-item/action %)))
       (map :transaction-item/amount)
       (reduce +)))

(defn credit-debit-balanced?
  "Returns a boolean value indicating whether or not the credit and debit totals are equal in the specified items"
  [items]
  (let [credit-total (item-total :transaction-item.action/credit items)
        debit-total (item-total :transaction-item.action/debit items)]
    (= credit-total debit-total)))

(defn validate-transaction-data
  "Throws an exception if any of the data is invalid"
  [data]
  (let [errors (reduce (fn [list [test-fn test-msg]]
                          (if (test-fn data) (conj list test-msg)))
                       []
                       [[#(nil? (:transaction/date %)) ":transaction/date must be specified"]
                        [#(nil? (:transaction/description %)) ":transaction/description must be specified"]
                        [#(not (credit-debit-balanced? (:transaction/items %))) "The transaction items must have balanced debit and credit totals"]])]
    (if (seq errors) (throw (IllegalArgumentException. (apply str (concat ["The transaction data is not valid: "] errors)))))))

(defn get-last-transaction-item-before
  [db account-id transaction-date]
  (ffirst (get-account-transaction-items db account-id {:start-date min-date
                                                        :end-date transaction-date
                                                        :sort-order :desc
                                                        :inclusive? false})))

(defn get-transaction-items-after
  [db account-id transaction-date]
  (get-account-transaction-items db account-id {:start-date transaction-date
                                                :sort-order :asc}))

(defn polarized-amount
  "Given a transaction item, returns the amount by which the corresponding
  account balance changes as a result of the transaction item"
  ([db item]
   (polarized-amount db (resolve-account db (:transaction-item/account item) item)))
  ([db account {amount :transaction-item/amount action :transaction-item/action}]
   (* amount (polarizer account action))))

(defn process-item
  "Processes a single transaction item, updating the given
  context with a datoms for the item balance and index values,
  and also updates the last-balance and last-index values.

  The context is a map with the keys:
    :db
    :datoms
    :last-index
    :last-balance"
  [account
   {:keys [last-balance last-index db]
    :as context}
   {amount              :transaction-item/amount
    action              :transaction-item/action
    id                  :db/id
    :as                 item}]
  (let [pol         (polarizer account (resolve-action db action))
        adjustment  (* pol amount)
        new-balance (+ last-balance adjustment)
        new-index   (+ last-index 1)]
    (-> context
        (update :datoms #(conj % [:db/add id
                                     :transaction-item/balance new-balance]
                                  [:db/add id
                                   :transaction-item/index new-index]))
        (assoc :last-balance new-balance)
        (assoc :last-index new-index))))

(defn process-items
  [context account items]
  (reduce (partial process-item account)
          context
          items))

(defn init-item-processing-context
  "Initializes the processing context for a sequence of items
  from an account.

  The context contains the following:

    :db           The database
    :last-index   The basis index used to seed the index for items to be processed
    :last-balance The last balance to seed the balance for the items to be processed
    :start-date   The start-date for the items to be processed
    :datoms       A vector to hold the result of the processed items"
  [db account-id {transaction-date :transaction/date
                  transaction-id :db/id
                  :as transaction}]
  (let [reified-transaction (if-not (map? transaction-id)
                              (get-transaction db transaction-id))
        start-date (if reified-transaction
                     (->> [transaction reified-transaction]
                          (map :transaction/date)
                          (map coerce/to-long)
                          (apply min)
                          coerce/from-long)
                     transaction-date)
        basis-item (or (get-last-transaction-item-before db account-id start-date)
                       {:transaction-item/index -1N
                        :transaction-item/balance 0M})]
    {:db db
     :last-index (:transaction-item/index basis-item)
     :last-balance (:transaction-item/balance basis-item)
     :start-date start-date
     :datoms []}))

(defn transaction-item-balance-adjustments
  "Given all transaction items for an account withing a transaction,
  account-id, and transaction date, returns the datoms necessary to adjust
  balances for the item and the account.

  When processing a deferenced account, include in the 'except' parameter
  the IDs of the transaction items that no longer reference this account"
  ([db account-token items transaction] (transaction-item-balance-adjustments db account-token items transaction #{}))
  ([db account-token items transaction except]
   (let [{account-id :db/id :as account} (resolve-account db account-token)
         context (init-item-processing-context db account-id transaction)
         unique-ids (->> items
                         (map :db/id)
                         (remove map?)
                         (into #{})
                         (union except))
         all-items (->> (get-transaction-items-after db account-id (:start-date context))

                        ; remove updated items from the existing items
                        (remove #(unique-ids (-> % first :db/id)))

                        ; add transaction to updated items so each list member is the same shape
                        (concat (map #(vector % transaction) items))

                        ; sort by transaction date
                        (sort-by (comp :transaction/date second))

                        ; strip off the transactions (leave the transaction items)
                        (map first))
         {:keys [last-balance
                 datoms]} (process-items context account all-items)
         account-datom (with-meta [:db/add account-id :account/balance last-balance] {:entity-type :account})]
     (cons account-datom datoms))))

(defn transaction-item-group-adjustments
  "Processes all transaction items in a transaction having the save account

  Accepts and returns a context with the following values
    :db             - The database state before the transaction is applied
    :datoms         - Datoms ready to be transacted to the data store
    :transaction    - The transaction to being processed"
  [context [account-id items]]
  (let [datoms (transaction-item-balance-adjustments (:db context)
                                                     account-id
                                                     items
                                                     (:transaction context))]
    (update context :datoms concat datoms)))

(defn referenced-accounts
  "Given a transaction, returns the referenced accounts"
  [transaction]
  (->> transaction
       :transaction/items
       (map :transaction-item/account)
       (map #(if (:db/id %) (:db/id %) %))
       (into #{})))

(defn process-dereferenced-accounts
  "Given a context with a list of datoms, returns the context including
  the datoms necessary to account for any accounts that used to be referenced
  by the transaction, but no longer are.

  The method works with the same context as transaction-item-group-adjustments."
  [{:keys [datoms transaction db] :as context}]
  (if (map? (:db/id transaction)) ; no need to process a new transaction
    context
    (let [original-trans (get-transaction db (:db/id transaction))
          original-account-ids (referenced-accounts original-trans)
          new-account-ids (referenced-accounts transaction)
          dereferenced-accounts (difference original-account-ids new-account-ids)
          datoms (mapcat (fn [account-id]
                           (transaction-item-balance-adjustments db account-id [] transaction (->> original-trans
                                                                                                   :transaction/items
                                                                                                   (filter #(= account-id (:db/id (:transaction-item/account %))))
                                                                                                   (map :db/id)
                                                                                                   (into #{}))))
                         dereferenced-accounts)]
      (update context :datoms concat datoms))))

(defn process-parent-accounts
  "Given a context with list of datoms, returns the context including
  the datoms necessary to account for adjustments to parent accounts
  based on changes in leaf accounts.

  The function words with the same context as transaction-item-group-adjustments."
  [{:keys [db] :as context}]
  (let [account-datoms (meta-filter :entity-type :account (:datoms context))
        account-deltas (map (fn [[_ account-id _ balance]]
                              (let [account (find-account db account-id)
                                    delta (- balance (:account/balance account))]
                                (vector account balance)))
                            account-datoms)
        parent-deltas (mapcat (fn [[account delta]]
                                (map #(vector % delta) (rest (get-account-with-parents db account))))
                              account-deltas)
        aggregated-deltas (reduce (fn [result [account delta]]
                                    (if (result account)
                                      (update result account + delta)
                                      (assoc result account delta)))
                                  {}
                                  parent-deltas)
        datoms (map (fn [[account delta]]
                      [:db/add (:db/id account) :account/children-balance (+ delta (:account/children-balance account))])
                    aggregated-deltas)]
    (update context :datoms concat datoms)))

(defn balance-adjustment-datoms
  "Calculates the adjustment datoms for the specified transaction"
  [db {items :transaction/items :as transaction}]
  (let [context {:db db
                 :datoms []
                 :transaction transaction}]
    (->> items
         (group-by :transaction-item/account)
         (reduce transaction-item-group-adjustments context)
         process-dereferenced-accounts
         process-parent-accounts
         :datoms)))

(defn append-balance-adjustment-datoms
  "Appends the datomic transaction commands necessary to adjust balances 
  for the transaction"
  [db transaction]
  (cons transaction (balance-adjustment-datoms db transaction)))

(defn resolve-transaction-item-data
  "Resolves references inside transaction item data"
  [db data]
  (update data :transaction-item/account #(:db/id (resolve-account db %))))

(defn resolve-transaction-data
  "Resolves references inside transaction data"
  [data db]
  (assoc-in data [:transaction/items] (map #(resolve-transaction-item-data db %) (:transaction/items data))))

(defn append-temp-id
  "Appends a temporary id to the specified map, if one is not already present"
  [value]
  (if (:db/id value)
    value
    (assoc value :db/id (d/tempid :db.part/user))))

(defn prepare-transaction-data
  "Takes the raw transaction data and makes it ready use with d/transact"
  [db data]
  (-> data
      (resolve-transaction-data db)
      (update :transaction/date #(coerce/to-date %))
      (update :transaction/items (fn [items]
                                   (map #(append-temp-id %) items)))))

(defn add-transaction
  "Adds a new transaction to the system"
  [conn {items :transaction/items :as data}]
  (validate-transaction-data data)
  (let [db (d/db conn)
        new-id (d/tempid :db.part/user)
        tx-data (->> data
                     (prepare-transaction-data db)
                     (merge {:db/id new-id})
                     (append-balance-adjustment-datoms db))
        result @(d/transact conn tx-data)
        tempids (:tempids result)]
    (d/resolve-tempid (d/db conn) tempids new-id)))

(defn remove-balance-and-index
  "Removes the existing balance and index attributes from line
  items so that they can be recalculated as part of an update"
  [transaction]
  (update-in transaction
             [:transaction/items]
             (fn [items]
               (map #(dissoc % :transaction-item/balance :transaction-item/index) items))))

(defn update-transaction
  "Updates an existing transaction in the system"
  [conn data]
  (let [db (d/db conn)
        tx-data (->> data
                     (prepare-transaction-data db)
                     (remove-balance-and-index)
                     (append-balance-adjustment-datoms db))]
    @(d/transact conn tx-data)))

(defn add-simple-transaction
  "Add a two-item transaction, crediting one account and debiting another"
  [conn {:keys [amount debit-account credit-account] :as data}]
  (let [transaction-tx-data (-> data
                                (dissoc :amount :credit-account :debit-account)
                                (assoc :transaction/items [{:transaction-item/action :transaction-item.action/debit
                                                            :transaction-item/account debit-account
                                                            :transaction-item/amount amount}
                                                           {:transaction-item/action :transaction-item.action/credit
                                                            :transaction-item/account credit-account
                                                            :transaction-item/amount amount}]))]
    (add-transaction conn transaction-tx-data)))

(defn calculate-account-balance
  "Given an account ID, totals the transaction item values for the specified account through the specified date"
  ([db account as-of-date] (calculate-account-balance db account earliest-date as-of-date))
  ([db account from to]
  (let [account (d/touch (d/entity db account))
        amounts (d/q '[:find ?amount ?action-name ?i ; The ?i value isn't used, but without it, duplicate rows were not returned
                       :in $ ?from ?to ?account-id
                       :where [?t :transaction/date ?transaction-date]
                       [?t :transaction/items ?i]
                       [?i :transaction-item/account ?account-id]
                       [?i :transaction-item/amount ?amount]
                       [?i :transaction-item/action ?action]
                       [?action :db/ident ?action-name]
                       [(<= ?transaction-date ?to)]
                       [(>= ?transaction-date ?from)]]
                     db
                     from
                     to
                     (:db/id account))]
    (reduce (fn [result [amount action]]
              (+ result (* amount (polarizer account action))))
            (bigdec 0)
            amounts))))

(defn recalculate-account-balance
  "Recalculates the balances for the specified account, also recalculating
  balances for any existing child accounts.

  The input arguments are
    context - a map containing
      :db       - connection to the data store
      :accounts - all accounts in the system
      :datoms   - The list of datoms to be applied to the data store"
  [{:keys [db] :as context} account]
  (let [children (filter #(= account (:account/parent %)) (:accounts context))
        context (reduce #(recalculate-account-balance %1 %2)
                        context
                        children)
        items (map first (get-account-transaction-items db (:db/id account) {:sort-order :asc}))
        {:keys [datoms last-balance]} (process-items {:db db :datoms [] :last-index -1N :last-balance 0M}
                                                     account
                                                     items)

        ; use the datoms already created to calculate the children balance for the current account
        children-balance (reduce + 0M (map (fn [child]
                                             (->> (:datoms context)
                                                  (filter (fn [[_ id attr]]
                                                            (and (#{:account/balance :account/children-balance} attr)
                                                                 (= (:db/id child) id))))
                                                  (map #(% 3))
                                                  (reduce + 0M)))
                                           children))
        account-datoms [[:db/add (:db/id account) :account/balance last-balance]
                        [:db/add (:db/id account) :account/children-balance children-balance]]]
    (update context :datoms concat account-datoms datoms)))

(defn recalculate-all-account-balances
  "Recalculates all item indexes, item balances, account balances and account children balances"
  [conn]
  (let [db (d/db conn)
        accounts (all-accounts db)
        root-accounts (remove #(:account/parent %) accounts)
        {:keys [datoms]} (reduce recalculate-account-balance {:db db
                                                              :accounts accounts
                                                              :datoms []} root-accounts)]
    (d/transact conn datoms)))
