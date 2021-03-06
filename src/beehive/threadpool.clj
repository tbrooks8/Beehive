;; Copyright 2014 Timothy Brooks
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;; http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns beehive.threadpool
  (:require [beehive.enums :as enums]
            [beehive.future :as f]
            [beehive.hive]
            [beehive.hive :as hive])
  (:import (java.util.concurrent ExecutorService Executors TimeoutException)
           (beehive.hive BeehiveCompletable)
           (net.uncontended.precipice ExecutionContext)
           (net.uncontended.precipice.concurrent PrecipicePromise)
           (net.uncontended.precipice.timeout PrecipiceTimeoutException
                                              TimeoutService
                                              Timeout DelayQueueTimeoutService)
           (net.uncontended.precipice.threadpool CancellableTask
                                                 CancellableTask$ResultToStatus
                                                 CancellableTask$ThrowableToStatus)))

(set! *warn-on-reflection* true)

(def result-class (enums/generate-result-class
                    {:success true :error false :timeout false}))

(def key->enum (enums/enum-class-to-keyword->enum result-class))

(def success-converter
  (let [success (:success key->enum)]
    (reify CancellableTask$ResultToStatus
      (resultToStatus [this result]
        success))))

(def error-converter
  (let [error (:error key->enum)
        timeout (:timeout key->enum)]
    (reify CancellableTask$ThrowableToStatus
      (throwableToStatus [this throwable#]
        (if (instance? TimeoutException throwable#)
          timeout
          error)))))

(def ^TimeoutService timeout-service DelayQueueTimeoutService/DEFAULT_TIMEOUT_SERVICE)

(deftype BeehiveTimeout [^CancellableTask task]
  Timeout
  (timeout [this]
    (.cancel task (:timeout key->enum) (PrecipiceTimeoutException.))))

(defn submit1 [{:keys [thread-pool]} fn timeout-millis promise]
  (let [^PrecipicePromise precipice-promise (.completable ^BeehiveCompletable promise)
        ^ExecutionContext context precipice-promise
        task (CancellableTask. success-converter error-converter fn precipice-promise)]
    (.execute ^ExecutorService thread-pool task)
    (when timeout-millis
      (.scheduleTimeout
        timeout-service (->BeehiveTimeout task)
        timeout-millis
        (.startNanos context)))
    (f/->BeehiveFuture (.future precipice-promise))))

(defn submit
  ([thread-pool fn] (submit thread-pool fn nil))
  ([beehive fn timeout-millis]
   (let [{:keys [rejected?] :as promise} (hive/acquire-promise beehive 1)]
     (if rejected?
       (f/rejected-future (:rejected-reason promise))
       (submit1 beehive fn timeout-millis promise)))))

(defn shutdown [{:keys [thread-pool guard-rail]}]
  (.shutdown ^ExecutorService thread-pool))

(defn threadpool [beehive pool-size]
  (when-not (= (:result-key->enum beehive) key->enum)
    (throw (IllegalArgumentException.
             (str "Threadpool beehive must use result class: " result-class))))
  (assoc beehive :thread-pool (Executors/newFixedThreadPool pool-size)))
