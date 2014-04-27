(ns anbf.action
  (:require [clojure.tools.logging :as log]))

(defprotocol Action
  (trigger [this]
           "Returns the string to write to NetHack to perform the action."))

(extend-type anbf.bot.IAction
  Action
  (trigger [this] (.trigger this)))

(def vi-directions
  {7 \y 8 \k 9 \u
   4 \h      6 \l
   1 \b 2 \j 3 \n})

(defmacro ^:private defaction [action args & impl]
  `(do (defrecord ~action ~args anbf.bot.IAction ~@impl)
       (defn ~(symbol (str \- action)) ~args
         (~(symbol (str action \.)) ~@args))))

(defaction Move [dir]
  (trigger [this]
    (str (or (vi-directions dir)
             (throw (IllegalArgumentException.
                      (str "Invalid direction: " dir)))))))

(defaction Pray []
  (trigger [this]
    "#pray\n"))

(gen-class :name anbf.bot.Actions
           :methods [^:static [Move [int] anbf.bot.IAction]
                     ^:static [Pray [] anbf.bot.IAction]])