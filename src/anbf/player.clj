; representation of the bot avatar

(ns anbf.player
  (:require [anbf.util :refer [enum]]
            [anbf.delegator :refer :all]
            [clojure.tools.logging :as log]))

(defn hungry?
  "Return true if hunger state is Hungry or worse"
  [{:keys [hunger]}]
  (not-any? #(= hunger %) [:normal :satiated]))

(defn weak?
  "Return true if hunger state is Weak or worse"
  [{:keys [hunger]}]
  (some #(= hunger %) [:weak :fainting]))

(defrecord Player
  [nickname
   hp
   maxhp
   pw
   maxpw
   ac
   xp
   xplvl
   hunger ; :fainting :weak :hungry :normal :satiated
   burden
   state ; stun/conf/hallu/blind/...
   stats
   alignment]
  anbf.bot.IPlayer
  (hunger [this] (enum anbf.bot.Hunger (:hunger this)))
  (isHungry [this] (boolean (hungry? this)))
  (isWeak [this] (boolean (weak? this))))

(defn new-player []
  (apply ->Player (repeat 13 nil)))

(defn update-player [player status delegator]
  ; TODO not just merge, emit events on changes, adjust nutrition by hunger...
  (merge player status))

(defn player-handler [game delegator]
  (reify BOTLHandler
    (botl [_ status]
      (swap! game update-in [:player] update-player status delegator))))
