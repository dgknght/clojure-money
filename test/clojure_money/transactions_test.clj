(ns clojure-money.transactions-test
  (:require [expectations :refer :all]
            [datomic.api :as d :refer [db]])
  (:use clojure-money.test-common
        clojure-money.accounts
        clojure-money.transactions))

;; When I add a transaction, it appears in the list of transactions
(expect (more-> #inst "2014-12-15" :transaction/date
                "Paycheck" :transaction/description
                (bigdec 1000) (-> :transaction/items first :transaction-item/amount)
                :transaction-item.action/debit (-> :transaction/items first :transaction-item/action)
                :transaction-item.action/credit (-> :transaction/items second :transaction-item/action))
        (let [conn (create-empty-db)]
          (add-account conn "Checking" :account.type/asset)
          (add-account conn "Salary" :account.type/income)
          (add-transaction conn
                           {:transaction/date #inst "2014-12-15"
                            :transaction/description "Paycheck"
                            :transaction/items [{:transaction-item/action :transaction-item.action/debit
                                                 :transaction-item/account "Checking"
                                                 :transaction-item/amount (bigdec 1000)}
                                                {:transaction-item/action :transaction-item.action/credit
                                                 :transaction-item/account "Salary"
                                                 :transaction-item/amount (bigdec 1000)}]})
          (first (get-transactions (d/db conn) #inst "2014-12-01" #inst "2014-12-31"))))

;; When I add a transaction, it returns an ID I can use to retrieve the transaction
(expect (more-> "Paycheck" :transaction/description
                #inst "2014-12-15" :transaction/date)
        (let [conn (create-empty-db)]
          (add-account conn "Checking" :account.type/asset)
          (add-account conn "Salary" :account.type/income)
          (let [id (add-transaction conn
                                    {:transaction/date #inst "2014-12-15"
                                     :transaction/description "Paycheck"
                                     :transaction/items [{:transaction-item/action :transaction-item.action/debit
                                                          :transaction-item/account "Checking"
                                                          :transaction-item/amount (bigdec 1000)}
                                                         {:transaction-item/action :transaction-item.action/credit
                                                          :transaction-item/account "Salary"
                                                          :transaction-item/amount (bigdec 1000)}]})]
            (get-transaction (d/db conn) id))))

;; A transaction must be in balance in order to be saved
(expect IllegalArgumentException
        (let [conn (create-empty-db)]
          (add-account conn "Checking" :account.type/asset)
          (add-account conn "Salary" :account.type/income)
          (add-transaction conn
                           {:transaction/date #inst "2014-12-15"
                            :transaction/description "Paycheck"
                            :transaction/items [{:transaction-item/action :transaction-item.action/debit
                                                 :transaction-item/account "Checking"
                                                 :transaction-item/amount (bigdec 1000)}
                                                {:transaction-item/action :transaction-item.action/credit
                                                 :transaction-item/account "Salary"
                                                 :transaction-item/amount (bigdec 500)}]})))

;; When I debit an asset account, the balance should increase
;; When I credit an income account, the balance should increase
(expect [(bigdec 1000) (bigdec 1000)]
        (let [conn (create-empty-db)]
          (add-account conn "Checking" :account.type/asset)
          (add-account conn "Salary" :account.type/income)
          (let [checking (find-account-id-by-path (d/db conn) "Checking")
                salary (find-account-id-by-path (d/db conn) "Salary")]
            (add-simple-transaction conn {:transaction/date #inst "2014-12-15"
                                          :transaction/description "Paycheck"
                                          :amount (bigdec 1000)
                                          :debit-account "Checking"
                                          :credit-account "Salary"})
            [(get-balance (d/db conn) checking) (get-balance (d/db conn) salary)])))

;; When I credit an asset account, the balance should decrease
;; When I debit an expense account, the balance should increase
(expect [(bigdec 1500) (bigdec 500)]
        (let [conn (create-empty-db)]
          (add-account conn "Checking" :account.type/asset)
          (add-account conn "Salary" :account.type/income)
          (add-account conn "Rent" :account.type/expense)
          (let [checking (find-account-id-by-path (d/db conn) "Checking")
                rent (find-account-id-by-path (d/db conn) "Rent")]
            (add-simple-transaction conn {:transaction/date #inst "2014-12-15"
                                          :transaction/description "Paycheck"
                                          :amount (bigdec 2000)
                                          :debit-account "Checking"
                                          :credit-account "Salary"})
            (add-simple-transaction conn {:transaction/date #inst "2014-12-15"
                                          :transaction/description "Rent"
                                          :amount (bigdec 500)
                                          :debit-account "Rent"
                                          :credit-account "Checking"})
            [(get-balance (d/db conn) checking) (get-balance (d/db conn) rent)])))

;; When I debit a liability account, the balance should decrease
(expect (bigdec 250)
        (let [conn (create-empty-db)]
          (add-account conn "Checking" :account.type/asset)
          (add-account conn "Salary" :account.type/income)
          (add-account conn "Rent" :account.type/expense)
          (add-account conn "Credit card" :account.type/liability)
          (let [credit-card (find-account-id-by-path (d/db conn) "Credit card")]
            (add-simple-transaction conn {:transaction/date #inst "2014-12-15"
                                          :transaction/description "Paycheck"
                                          :amount (bigdec 2000)
                                          :debit-account "Checking"
                                          :credit-account "Salary"})
            (add-simple-transaction conn {:transaction/date #inst "2014-12-16"
                                          :transaction/description "Rent"
                                          :amount (bigdec 500)
                                          :debit-account "Rent"
                                          :credit-account "Credit card"})
            (add-simple-transaction conn {:transaction/date #inst "2014-12-17"
                                          :transaction/description "Credit card"
                                          :amount (bigdec 250)
                                          :debit-account "Credit card"
                                          :credit-account "Checking"})
            (get-balance (d/db conn) credit-card))))

;; When I credit a liability account, the balance should increase
(expect (bigdec 500)
        (let [conn (create-empty-db)]
          (add-account conn "Credit card" :account.type/liability)
          (add-account conn "Rent" :account.type/expense)
          (let [credit-card (find-account-id-by-path (d/db conn) "Credit card")
                rent (find-account-id-by-path (d/db conn) "Rent")]
            (add-simple-transaction conn {:transaction/date #inst "2014-12-15"
                                          :transaction/description "Rent"
                                          :amount (bigdec 500)
                                          :debit-account "Rent"
                                          :credit-account "Credit card"})
            (get-balance (d/db conn) credit-card))))

;; When I debit an equity account, the balance should decrease
(expect (bigdec -500)
        (let [conn (create-empty-db)]
          (add-account conn "Checking" :account.type/asset)
          (add-account conn "Opening balances" :account.type/equity)
          (let [opening-balances (find-account-id-by-path (d/db conn) "Opening balances")]
            (add-simple-transaction conn {:transaction/date #inst "2014-12-15"
                                          :transaction/description "Opening balance"
                                          :amount (bigdec 500)
                                          :debit-account "Opening balances"
                                          :credit-account "Checking"})
            (get-balance (d/db conn) opening-balances))))

;; When I credit an equity account, the balance should increase
(expect (bigdec 500)
        (let [conn (create-empty-db)]
          (add-account conn "Checking" :account.type/asset)
          (add-account conn "Opening balances" :account.type/equity)
          (let [opening-balances (find-account-id-by-path (d/db conn) "Opening balances")]
            (add-simple-transaction conn {:transaction/date #inst "2014-12-15"
                                          :transaction/description "Opening balance"
                                          :amount (bigdec 500)
                                          :debit-account "Checking"
                                          :credit-account "Opening balances"})
            (get-balance (d/db conn) opening-balances))))

;; When I debit an income account, the balance should decrease
;; When I credit an expense account, the balance should decrease
(expect [(bigdec -1000) (bigdec -1000)]
        (let [conn (create-empty-db)]
          (add-account conn "Salary" :account.type/income)
          (add-account conn "Rent" :account.type/expense)
          (let [db (d/db conn)
                salary (find-account-id-by-path db "Salary")
                rent (find-account-id-by-path db "Rent")]
            (add-simple-transaction conn {:transaction/date #inst "2014-12-15"
                                          :transaction/description "Convoluted transaction for a test"
                                          :amount (bigdec 1000)
                                          :debit-account "Salary"
                                          :credit-account "Rent"})
            [(get-balance (d/db conn) salary) (get-balance (d/db conn) rent)])))

;; Adding a simple transaction should affect the account balances properly
(expect [(bigdec 1000) (bigdec 1000)]
        (let [conn (create-empty-db)]
          (add-account conn "Checking" :account.type/asset)
          (add-account conn "Salary" :account.type/income)
          (let [db (d/db conn)
                checking (find-account-id-by-path db "Checking")
                salary (find-account-id-by-path db "Salary")]
            (add-simple-transaction conn {:transaction/date #inst "2014-02-27"
                                          :transaction/description "Paycheck"
                                          :amount (bigdec 1000)
                                          :credit-account salary
                                          :debit-account checking})
            (let [db (d/db conn)]
              (vector (get-balance db checking) (get-balance db salary))))))

;; Adding a simple transaction should create the correct full transaction
(expect (more-> 2 (-> :transaction/items count))
        (let [conn (create-empty-db)]
          (add-account conn "Checking" :account.type/asset)
          (add-account conn "Salary" :account.type/income)
          (let [db (d/db conn)
                checking (find-account-id-by-path db "Checking")
                salary (find-account-id-by-path db "Salary")]
            (let [id (add-simple-transaction conn {:transaction/date #inst "2014-02-27"
                                          :transaction/description "Paycheck"
                                          :amount (bigdec 1000)
                                          :credit-account salary
                                          :debit-account checking})
                  db (d/db conn)]
              (get-transaction db id)))))

(defn calculate-account-balance-setup
  "Add transaction for calculate-account-balance tests"
  [conn]
  (add-account conn "Salary" :account.type/income)
  (add-account conn "Checking" :account.type/asset)
  (add-account conn "Groceries" :account.type/expense)
  (let [db (d/db conn)
        salary (find-account-id-by-path db "Salary")
        checking (find-account-id-by-path db "Checking")
        groceries (find-account-id-by-path db "Groceries")]
    (add-simple-transaction conn {:transaction/date #inst "2015-01-01"
                                  :transaction/description "Paycheck"
                                  :amount (bigdec 1000)
                                  :debit-account checking
                                  :credit-account salary})
    (add-simple-transaction conn {:transaction/date #inst "2015-01-04"
                                  :transaction/description "Kroger"
                                  :amount (bigdec 100)
                                  :debit-account groceries
                                  :credit-account checking})
    (add-simple-transaction conn {:transaction/date #inst "2015-01-11"
                                  :transaction/description "Kroger"
                                  :amount (bigdec 100)
                                  :debit-account groceries
                                  :credit-account checking})
    (add-simple-transaction conn {:transaction/date #inst "2015-01-12"
                                  :transaction/description "Kroger"
                                  :amount (bigdec 10)
                                  :debit-account checking
                                  :credit-account groceries})
    {:checking checking :salary salary :groceries groceries}))

;; calculate-account-balance should sum the polarized transaction item amounts from the inception to the specified date
(expect (bigdec 0)
        (let [conn (create-empty-db)
              accounts (calculate-account-balance-setup conn)]
          (calculate-account-balance (d/db conn) (:checking accounts) #inst "2014-12-31")))

(expect (bigdec 1000)
        (let [conn (create-empty-db)
              accounts (calculate-account-balance-setup conn)]
          (calculate-account-balance (d/db conn) (:checking accounts) #inst "2015-01-01")))

(expect (bigdec 900)
        (let [conn (create-empty-db)
              accounts (calculate-account-balance-setup conn)]
          (calculate-account-balance (d/db conn) (:checking accounts) #inst "2015-01-04")))

(expect (bigdec 800)
        (let [conn (create-empty-db)
              accounts (calculate-account-balance-setup conn)]
          (calculate-account-balance (d/db conn) (:checking accounts) #inst "2015-01-11")))

(expect (bigdec 810)
        (let [conn (create-empty-db)
              accounts (calculate-account-balance-setup conn)]
          (calculate-account-balance (d/db conn) (:checking accounts) #inst "2015-12-31")))

(expect (bigdec 90)
        (let [conn (create-empty-db)
              accounts (calculate-account-balance-setup conn)]
          (calculate-account-balance (d/db conn) (:groceries accounts) #inst "2015-01-11" #inst "2015-01-12")))
