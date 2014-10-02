(ns anbf.player
  "representation of the bot avatar"
  (:require [anbf.util :refer :all]
            [anbf.dungeon :refer :all]
            [anbf.delegator :refer :all]
            [anbf.itemid :refer :all]
            [anbf.item :refer :all]
            [clojure.tools.logging :as log]))

(defn hungry?
  "Returns hunger state if it is Hungry or worse, else nil"
  [player]
  (#{:hungry :weak :fainting} (:hunger player)))

(defn weak?
  "Returns hunger state if it is Weak or worse, else nil"
  [player]
  (#{:weak :fainting} (:hunger player)))

(defn satiated? [player]
  (= :satiated (:hunger player)))

(defrecord Player
  [nickname
   role
   race
   hp
   maxhp
   pw
   maxpw
   ac
   xp
   xplvl
   x y
   inventory ; {char => Item}
   hunger ; :fainting :weak :hungry :satiated
   burden ; :overloaded :overtaxed :strained :stressed :burdened
   intrinsics ; set
   engulfed
   trapped
   leg-hurt
   state ; subset #{:stun :conf :hallu :blind :ill}
   stat-drained
   lycantrophy
   stats ; :str :dex :con :int :wis :cha
   alignment ; :lawful :neutral :chaotic
   can-enhance]
  anbf.bot.IPlayer
  ; TODO expose stats etc.
  (alignment [this] (kw->enum anbf.bot.Alignment (:alignment this)))
  (hunger [this] (kw->enum anbf.bot.Hunger (:hunger this)))
  (isHungry [this] (boolean (hungry? this)))
  (isWeak [this] (boolean (weak? this))))

(defn new-player []
  (map->Player {}))

(defn update-player [player status]
  (->> (keys player) (select-keys status) (into player)))

(defn blind? [player]
  (:blind (:state player)))

(defn impaired? [player]
  (some (:state player) #{:conf :stun :hallu :blind}))

(defn hallu? [player]
  (:hallu (:state player)))

(defn dizzy? [player]
  (some (:state player) #{:conf :stun}))

(defn thick?
  "True if the player can't pass through narrow diagonals."
  [player]
  (not (:thick player)))

(defn light-radius [game]
  1) ; TODO check for lit lamp/candelabrum/sunsword/burning oil

(defn inventory [game]
  (-> game :player :inventory))

(defn inventory-slot
  "Return item for the inventory slot"
  [game slot]
  (get-in game [:player :inventory slot]))

(defn- have-selector [game name-or-set-or-fn]
  (cond (fn? name-or-set-or-fn) name-or-set-or-fn
        (set? name-or-set-or-fn) (some-fn
                                   (comp name-or-set-or-fn
                                         (partial item-name game))
                                   (comp name-or-set-or-fn :name))
        :else (some-fn
                (comp (partial = name-or-set-or-fn) :name)
                (comp (partial = name-or-set-or-fn)
                      (partial item-name game)))))

(defn have-all
  "Returns a lazy seq of all matching [slot items] pairs in inventory, options same as 'have'"
  [game name-or-set-or-fn]
  ;(log/debug "have-all")
  (for [[slot item :as entry] (inventory game)
        :when ((have-selector game name-or-set-or-fn) item)]
    entry))

(defn have
  "Returns the [slot item] of matching item in player's inventory or nil.
   First arg can be:
     String (name of item)
     #{String} (set of strings - item name alternatives with no preference)
     fn - predicate function to filter items"
  [game name-or-set-or-fn]
  (first (have-all game name-or-set-or-fn)))

(defn have-noncursed
  "Like 'have' but return only known-non-cursed items"
  [game name-or-set-or-fn]
  (have game (every-pred noncursed?
                         (have-selector game name-or-set-or-fn))))

(defn have-unihorn [game]
  (have game #(and (= "unicorn horn" (item-name game %))
                   (not (cursed? %)))))

(defn have-pick [game]
  ; TODO (can-wield? ...)
  (have game #(and (#{"pick-axe" "dwarvish mattock"} (item-name game %))
                   (or (not (cursed? %)) (:in-use %)))))

(defn have-key [game]
  (have game #{"skeleton key" "lock pick" "credit card"}))

(defn have-levi-on [game]
  (have game #(and (#{"boots of levitation" "ring of levitation"}
                             (item-name game %))
                   (:in-use %))))

(defn have-levi [game]
  (have game #(and (#{"boots of levitation" "ring of levitation"}
                             (item-name game %))
                   #_(can-use? %) ; TODO
                   (or (noncursed? %) (:in-use %)))))

(defn unihorn-recoverable? [{:keys [player] :as game}]
  (or (:stat-drained player)
      (some (:state player) #{:conf :stun :hallu :ill})
      (and (:blind (:state player))
           (not (have game #(and (#{"towel" "blindfold"} (item-name game %))
                                 (:in-use %)))))))

(defn can-remove? [game item]
  (not (cursed? item))) ; TODO not obstructed by cursed item / weapon

(defn wielding
  "Return the wielded item or nil"
  [game]
  (have game :wielded))

(defn initial-intrinsics [race-or-role]
  (case race-or-role
    :valkyrie #{:cold :stealth}
    :orc #{:poison}
    ; TODO rest
    #{}))

(defn have-resistance? [player resist]
  (resist (:intrinsics player)))

(defn count-candles [game]
  (reduce +
          (if-let [[_ candelabrum] (have game "Candelabrum of Invocation")]
            (:candles candelabrum))
          (for [[_ candles] (have-all game #(.contains (:name %) "candle"))]
            (:qty candles))))

(defn have-candles? [game]
  (= 7 (count-candles game)))

(def taboo-corpses #{"chickatrice" "cockatrice" "green slime" "stalker" "quantum mechanic" "elf" "human" "dwarf" "giant"})

(defn safe-corpse-type? [player {:keys [monster] :as corpse}]
  (and (or (tin? corpse)
           (have-resistance? player :poison)
           (not :poisonous (:tags monster)))
       (not taboo-corpses (:name monster))
       (not :were (:tags monster))
       (not (re-seq #" bat$" (:name monster)))
       (not (:teleport (:tags monster)))))

(defn want-to-eat? [player {:keys [monster] :as corpse}]
  (and (safe-corpse-type? player corpse)
       (or (= "wraith" (:name monster))
           (some (complement (partial have-resistance? player))
                 (:resistances-conferred monster)))))

(defn can-eat?
  "Only true for safe food"
  [player food]
  (and (= :food (typekw food))
       (or (= :orc (:race player))
           (not= "tripe ration" (:name food)))
       (not (cursed? food))
       (or (not (corpse? food))
           (safe-corpse-type? player food))))
