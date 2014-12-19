(ns clojure-money.transactions-test
  (:require [expectations :refer :all]
            [clojure-money.core :refer [conn]]
            [clojure-money.core-test :refer [create-empty-db]]
            [clojure-money.accounts :refer :all]
            [clojure-money.transactions :refer :all]))

;; When I add a transaction, it appears in the list of transactions
(expect (more-> #datetime "2014-12-15" :transaction/date
                "Paycheck" :transaction/description
                (BigDecimal. 1000) (-> :transaction/items first :transaction-item/amount)
                :transaction-item.action/debit (-> :transaction/items first :transaction-item/action))
        (with-redefs [conn (create-empty-db)]
          (do
            (add-account "Checking" :account.type/asset)
            (add-account "Salary" :account.type/income)
            (add-transaction #datetime "2014-12-15"
                             "Paycheck"
                             [[:transaction-item.action/debit "Checking" (BigDecimal. 1000)]
                              [:transaction-item.action/credit "Salary" (BigDecimal. 1000)]])
            (first (get-transactions #datetime "2014-12-01" #datetime "2014-12-31")))))

;; When I add a transaction, it should affect the balance of the referenced accounts

;; A transaction must be in balance in order to be saved
