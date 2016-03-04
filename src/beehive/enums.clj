(ns beehive.enums
  (:import (beehive.enums EnumBuilder)))

(set! *warn-on-reflection* true)

(defn result-assertions [ks]
  (doseq [k ks]
    (assert (not (.contains (name k) "$FAILURE$"))
            "Result keyword cannot contain the string \"$FAILURE$\".")))

(defn enum-assertions [ks]
  (doseq [k ks]
    (assert (not (.contains (name k) "$DASH$"))
            "Enum keyword cannot contain the string \"$DASH$\".")))

(defn- enum-string [k]
  (.replace (name k) "-" "$DASH$"))

(defn- result-enum-string [k s?]
  (str (enum-string k) (when-not s? "$FAILURE$")))

(defn generate-rejected-enum [rejected-keys]
  (enum-assertions rejected-keys)
  (let [key->enum-string (into {} (map (fn [k]
                                         [k (enum-string k)])
                                       rejected-keys))
        cpath (EnumBuilder/buildRejectedEnum (or (vals key->enum-string) []))
        cpath (symbol cpath)]
    {:cpath cpath
     :key->enum-string key->enum-string}))

(defn generate-result-enum [result->success?]
  (let [ks (keys result->success?)]
    (result-assertions ks)
    (enum-assertions ks))
  (let [key->enum-string (into {} (map (fn [[k s?]]
                                         [k (result-enum-string k s?)])
                                       result->success?))
        cpath (EnumBuilder/buildResultEnum (or (vals key->enum-string) []))
        cpath (symbol cpath)]
    {:cpath cpath
     :key->enum-string key->enum-string}))

(defn enum-form [cpath string]
  `(. ~cpath ~(symbol string)))

(defmacro create-type-map [key->enum-string cpath]
  `(do
     (-> {}
         ~@(map (fn [[k es]]
                  (list assoc k `(. ~cpath ~(symbol es))))
                key->enum-string))))

(defmacro rejected-keys->enum [rejected-keys]
  (let [{:keys [key->enum-string cpath]} (generate-rejected-enum rejected-keys)]
    `(create-type-map ~key->enum-string ~cpath)))

(defmacro result-keys->enum [result->success?]
  (let [{:keys [key->enum-string cpath]} (generate-result-enum result->success?)]
    `(create-type-map ~key->enum-string ~cpath)))