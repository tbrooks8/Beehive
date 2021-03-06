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

(ns beehive.threadpool-test
  (:require [clojure.test :refer :all]
            [beehive.hive :as beehive]
            [beehive.threadpool :as threadpool]
            [beehive.future :as f]
            [beehive.metrics :as metrics]
            [beehive.semaphore :as semaphore]
            [beehive.hive :as hive])
  (:import (java.io IOException)
           (java.util.concurrent CountDownLatch ExecutionException)
           (net.uncontended.precipice.timeout PrecipiceTimeoutException)))

(set! *warn-on-reflection* true)

(def service nil)

(defn- start-and-stop [f]
  (let [threadpool (beehive/lett [rejected-class #{:max-concurrency}]
                     (-> (beehive/beehive "" threadpool/result-class rejected-class)
                         (beehive/set-result-counts (metrics/total-counts threadpool/result-class))
                         (beehive/set-rejected-counts (metrics/total-counts rejected-class))
                         (beehive/add-backpressure
                           :semaphore (semaphore/semaphore 1 :max-concurrency))
                         beehive/map->hive
                         (threadpool/threadpool 1)))]
    (alter-var-root
      #'service
      (fn [_] threadpool)))
  (f)
  (threadpool/shutdown service))


(use-fixtures :each start-and-stop)

(defn- block-fn [result ^CountDownLatch latch]
  (fn [] (.await latch) result))

(defn- success-fn [result]
  (fn [] result))

(defn- error-fn [e]
  (fn [] (throw e)))

(deftest submit-test
  (testing "Submit action returns CLJ future wrapping result"
    (let [latch (CountDownLatch. 1)
          f (threadpool/submit service (block-fn 64 latch))]
      (is (not (realized? f)))
      (is (:pending? f))
      (is (= :not-done (deref f 100 :not-done)))
      (.countDown latch)
      (is (= 64 @f))
      (is (= :success (:result f)))
      (is (not (:rejected? f)))))
  (testing "Submitted action can return error"
    (let [exception (IOException.)
          f (threadpool/submit service (error-fn exception) 10000)]
      (f/await! f)
      (is (= exception (:value f)))
      (is (not (:rejected? f)))
      (is (= :error (:result f)))
      (try
        @f
        (is false)
        (catch ExecutionException e
          (is (= exception (.getCause e)))))))
  (testing "Submitted action can timeout"
    (let [latch (CountDownLatch. 1)
          f (threadpool/submit service (block-fn 1 latch) 50)]
      (f/await! f)
      (is (not (:rejected? f)))
      (is (= :timeout (:result f)))
      (.countDown latch)
      (is (instance? PrecipiceTimeoutException (:value f)))
      (try
        @f
        (is false)
        (catch ExecutionException e
          (is (instance? PrecipiceTimeoutException (.getCause e)))))))

  (testing "If concurrency level exhausted, action rejected"
    (let [latch (CountDownLatch. 1)
          _ (threadpool/submit service (block-fn 1 latch))
          f (threadpool/submit service (success-fn 1))]
      (is (= :max-concurrency (:rejected-reason f)))
      (is (:rejected? f))
      (is (nil? (:result f)))
      (.countDown latch))))

(deftest callback-test
  (testing "Test that future callback is executed"
    (let [result (atom nil)
          blocker (CountDownLatch. 1)
          f (threadpool/submit service (success-fn 64))]
      (f/on-complete
        f (fn [r] (reset! result r) (.countDown blocker)))
      (.await blocker)
      (is (= {:failure? false
              :result :success
              :success? true
              :value 64} @result)))
    (let [result (atom nil)
          blocker (CountDownLatch. 1)
          e (RuntimeException.)
          f (threadpool/submit service (error-fn e))]
      (f/on-complete
        f (fn [r] (reset! result r) (.countDown blocker)))
      (.await blocker)
      (is (= {:failure? true
              :result :error
              :success? false
              :value e} @result)))
    (let [action-blocker (CountDownLatch. 1)
          result (atom nil)
          blocker (CountDownLatch. 1)
          f (threadpool/submit service (block-fn 64 action-blocker) 10)]
      (f/on-complete
        f (fn [r] (reset! result r) (.countDown blocker)))
      (.await blocker)
      (is (instance? PrecipiceTimeoutException (:value @result)))
      (.countDown action-blocker))))

(deftest metrics-test
  (testing "Testing that metrics are updated with result of action"
    (let [threadpool (beehive/lett [rejected-class #{}]
                       (-> (beehive/beehive "" threadpool/result-class rejected-class)
                           (beehive/set-result-counts (metrics/total-counts threadpool/result-class))
                           (beehive/set-rejected-counts (metrics/total-counts rejected-class))
                           beehive/map->hive
                           (threadpool/threadpool 1)))
          latch (CountDownLatch. 1)
          result-metrics (beehive/result-counts threadpool)]
      (f/await! (threadpool/submit threadpool (success-fn 1)))
      (f/await! (threadpool/submit threadpool (error-fn (IOException.))))
      (f/await! (threadpool/submit threadpool (block-fn 1 latch) 10))
      (.countDown latch)
      (is (= 1 (:count (first (metrics/count-seq result-metrics :success)))))
      (is (= 1 (:count (first (metrics/count-seq result-metrics :timeout)))))
      (is (= 1 (:count (first (metrics/count-seq result-metrics :error)))))
      (threadpool/shutdown threadpool)))
  (testing "Testing that rejection reasons are updated"
    (let [threadpool (beehive/lett [rejected-class #{:max-concurrency}]
                       (-> (beehive/beehive "" threadpool/result-class rejected-class)
                           (beehive/set-result-counts (metrics/total-counts threadpool/result-class))
                           (beehive/set-rejected-counts (metrics/total-counts rejected-class))
                           (beehive/add-backpressure
                             :semaphore (semaphore/semaphore 1 :max-concurrency))
                           beehive/map->hive
                           (threadpool/threadpool 1)))
          latch (CountDownLatch. 1)
          rejected-metrics (hive/rejected-counts threadpool)]
      (threadpool/submit threadpool (block-fn 1 latch))
      (threadpool/submit threadpool (success-fn 1))
      (.countDown latch)
      ;(is (= 1 (metrics/get-count1 rejected-metrics :max-concurrency)))
      (threadpool/shutdown threadpool))))

(defn- assert-not-zero
  [{:keys [latency-50 latency-90 latency-99 latency-99-9 latency-99-99
           latency-99-999 latency-max latency-mean]}]
  (is (not (or (nil? latency-50) (= latency-50 0))))
  (is (not (or (nil? latency-90) (= latency-90 0))))
  (is (not (or (nil? latency-99) (= latency-99 0))))
  (is (not (or (nil? latency-99-9) (= latency-99-9 0))))
  (is (not (or (nil? latency-99-99) (= latency-99-99 0))))
  (is (not (or (nil? latency-99-999) (= latency-99-999 0))))
  (is (not (or (nil? latency-max) (= latency-max 0))))
  (is (not (or (nil? latency-mean) (= latency-mean 0.0)))))

(defn- assert-zero
  [{:keys [latency-50 latency-90 latency-99 latency-99-9 latency-99-99
           latency-99-999 latency-max latency-mean]}]
  (is (= latency-50 0))
  (is (= latency-90 0))
  (is (= latency-99 0))
  (is (= latency-99-9 0))
  (is (= latency-99-99 0))
  (is (= latency-99-999 0))
  (is (= latency-max 0))
  (is (= latency-mean 0.0)))

;(deftest latency-test
;  (let [hive (beehive/beehive
;               ""
;               (threadpool/threadpool-results
;                 (metrics/count-metrics)
;                 (metrics/latency-metrics (.toNanos TimeUnit/HOURS 1) 2))
;               (beehive/create-back-pressure
;                 #{}
;                 (metrics/count-metrics)))
;        threadpool (threadpool/threadpool 1 hive)
;        latch (CountDownLatch. 1)
;        latency-metrics (:latency-metrics hive)]
;    (testing "Testing that success latency is updated"
;      (f/await! (service/submit threadpool (success-fn 1)))
;      (let [success-latency (metrics/latency latency-metrics :success)
;            error-latency (metrics/latency latency-metrics :error)
;            timeout-latency (metrics/latency latency-metrics :timeout)]
;        (assert-not-zero success-latency)
;        (assert-zero error-latency)
;        (assert-zero timeout-latency)))
;
;    (testing "Testing that error latency is updated"
;      (f/await! (service/submit threadpool (error-fn (IOException.))))
;      (let [success-latency (metrics/latency latency-metrics :success)
;            error-latency (metrics/latency latency-metrics :error)
;            timeout-latency (metrics/latency latency-metrics :timeout)]
;        (assert-not-zero error-latency)
;        (assert-zero success-latency)
;        (assert-zero timeout-latency)))
;
;    (testing "Testing that timeout latency is updated"
;      (f/await! (service/submit threadpool (block-fn 1 latch) 10))
;      (.countDown latch)
;      (let [success-latency (metrics/latency latency-metrics :success)
;            error-latency (metrics/latency latency-metrics :error)
;            timeout-latency (metrics/latency latency-metrics :timeout)]
;        (assert-not-zero timeout-latency)
;        (assert-zero success-latency)
;        (assert-zero error-latency)))))

