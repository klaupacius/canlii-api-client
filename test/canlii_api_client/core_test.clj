(ns canlii-api-client.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [hato.client :as http]
            [canlii-api-client.core :as sut])) ; system under test

(defn- stub-request
  "Returns [captured stub] where `stub` mimics `hato.client/request`: it records
   the opts map your code built into `captured`, then returns (or throws) the
   supplied canned response. No real HTTP happens.

   `response` may be a map (returned as-is), or an Exception (thrown, to exercise
   the catch branch)."
  [response]
  (let [captured (atom nil)]
    [captured
     (fn [opts]
       (reset! captured opts)
       (if (instance? Throwable response)
         (throw response)
         response))]))

;; A throwaway client threaded into every endpoint call. Its :http-client is
;; never exercised because `http/request` is stubbed; the :api-key is what we
;; assert lands in the outgoing query params.
(def ^:private test-client (sut/make-client {:api-key "test-key"}))

(deftest make-client-validation
  (testing "a usable key produces a client carrying the merged config"
    (let [client (sut/make-client {:api-key "k" :timeout 1234})]
      (is (= "k" (:api-key client)))
      (is (= 1234 (:timeout client)))
      (is (= "https://api.canlii.org/v1" (:base-url client)))
      (is (some? (:http-client client)) "builds an http-client by default")))

  (testing "an explicit :http-client is used instead of building one"
    (let [stub-http (Object.)
          client    (sut/make-client {:api-key "k" :http-client stub-http})]
      (is (identical? stub-http (:http-client client)))))

  (testing "a missing or blank key is rejected at construction"
    (is (thrown? clojure.lang.ExceptionInfo (sut/make-client {})))
    (is (thrown? clojure.lang.ExceptionInfo (sut/make-client {:api-key ""})))
    (is (thrown? clojure.lang.ExceptionInfo (sut/make-client {:api-key "   "})))))

(deftest path-and-query-building
  (testing "interpolates a single path param and injects api_key"
    (let [[captured stub] (stub-request
                           {:status 200
                            :body   {:caseDatabases [{:databaseId "onca"}]}})]
      (with-redefs [http/request stub]
        (let [response (sut/list-case-databases test-client {:language "en"})]
          (is (true? (:success response)))
          (is (= "https://api.canlii.org/v1/caseBrowse/en/" (:url @captured)))
          (is (= "test-key" (get-in @captured [:query-params "api_key"])))
          (is (= 1 (count (get-in response [:data :caseDatabases]))))))))

  (testing "interpolates multiple path params in order"
    (let [[captured stub] (stub-request {:status 200 :body {}})]
      (with-redefs [http/request stub]
        (sut/case-metadata test-client {:language "en" :database-id "onca" :case-id "2020onca1"})
        (is (= "https://api.canlii.org/v1/caseBrowse/en/onca/2020onca1/"
               (:url @captured))))))

  (testing "kebab params become camelCase query params; nil filters are dropped"
    (let [[captured stub] (stub-request {:status 200 :body {}})]
      (with-redefs [http/request stub]
        (sut/browse-cases test-client
                          {:language            "en"
                           :database-id         "onca" ; path param, not a query param
                           :result-count        25
                           :decision-date-after "2020-01-01"
                           :published-before    nil}) ; nil -> omitted
        (let [qp (:query-params @captured)]
          (is (= "25" (str (get qp "resultCount"))))
          (is (= "2020-01-01" (get qp "decisionDateAfter")))
          (is (not (contains? qp "publishedBefore")))
          (is (not (contains? qp "databaseId"))))))))

(deftest result-mapping
  (testing "2xx -> {:success true :data body}"
    (let [[_ stub] (stub-request {:status 200 :body {:hello "world"}})]
      (with-redefs [http/request stub]
        (is (= {:success true :data {:hello "world"}}
               (sut/list-case-databases test-client {:language "en"}))))))

  (testing "non-2xx with a decoded map body -> message extracted"
    (let [[_ stub] (stub-request {:status 404 :body {:message "Not found"}})]
      (with-redefs [http/request stub]
        (is (= {:success false :error-code 404 :message "Not found"}
               (sut/list-case-databases test-client {:language "en"}))))))

  (testing "non-2xx with a JSON *string* body -> message parsed out"
    ;; hato leaves error bodies as raw strings; we must parse them ourselves
    (let [[_ stub] (stub-request {:status 400 :body "{\"message\":\"Bad request\"}"})]
      (with-redefs [http/request stub]
        (is (= {:success false :error-code 400 :message "Bad request"}
               (sut/list-case-databases test-client {:language "en"}))))))

  (testing "non-2xx with a non-JSON body -> raw text used, no throw"
    (let [[_ stub] (stub-request {:status 503 :body "  <html>Service Unavailable</html>  "})]
      (with-redefs [http/request stub]
        (is (= {:success false :error-code 503 :message "<html>Service Unavailable</html>"}
               (sut/list-case-databases test-client {:language "en"}))))))

  (testing "non-2xx with an empty body -> nil message, status still reported"
    (let [[_ stub] (stub-request {:status 500 :body ""})]
      (with-redefs [http/request stub]
        (is (= {:success false :error-code 500 :message nil}
               (sut/list-case-databases test-client {:language "en"})))))))

(deftest transport-failure-classification
  (testing "a generic exception -> :error-code :exception, category :transport"
    (let [[_ stub] (stub-request (Exception. "boom"))]
      (with-redefs [http/request stub]
        (is (= {:success false :error-code :exception
                :error-category :transport :message "boom"}
               (sut/list-case-databases test-client {:language "en"}))))))

  (testing "a request timeout is classified as :timeout"
    (let [[_ stub] (stub-request (java.net.http.HttpTimeoutException. "request timed out"))]
      (with-redefs [http/request stub]
        (let [response (sut/list-case-databases test-client {:language "en"})]
          (is (= :exception (:error-code response)))
          (is (= :timeout (:error-category response)))))))

  (testing "a connection failure is classified as :connection"
    (let [[_ stub] (stub-request (java.net.ConnectException. "Connection refused"))]
      (with-redefs [http/request stub]
        (let [response (sut/list-case-databases test-client {:language "en"})]
          (is (= :exception (:error-code response)))
          (is (= :connection (:error-category response))))))))

(deftest citator-endpoints
  (testing "cited-cases hardcodes language=en and builds the citedCases path"
    (let [[captured stub] (stub-request {:status 200 :body {:citedCases []}})]
      (with-redefs [http/request stub]
        (let [response (sut/cited-cases test-client {:database-id "onca" :case-id "2020onca1"})]
          (is (true? (:success response)))
          (is (= "https://api.canlii.org/v1/caseCitator/en/onca/2020onca1/citedCases"
                 (:url @captured)))))))

  (testing "language is forced to en even if a caller passes one"
    (let [[captured stub] (stub-request {:status 200 :body {}})]
      (with-redefs [http/request stub]
        ;; :language is not in the destructuring, so it is ignored entirely
        (sut/citing-cases test-client {:database-id "onca" :case-id "2020onca1" :language "fr"})
        (is (= "https://api.canlii.org/v1/caseCitator/en/onca/2020onca1/citingCases"
               (:url @captured))))))

  (testing "cited-legislations builds the citedLegislations path"
    (let [[captured stub] (stub-request {:status 200 :body {}})]
      (with-redefs [http/request stub]
        (sut/cited-legislations test-client {:database-id "onca" :case-id "2020onca1"})
        (is (= "https://api.canlii.org/v1/caseCitator/en/onca/2020onca1/citedLegislations"
               (:url @captured)))))))

(deftest legislation-endpoints
  (testing "list-legislation-databases interpolates language"
    (let [[captured stub] (stub-request {:status 200 :body {:legislationDatabases []}})]
      (with-redefs [http/request stub]
        (let [response (sut/list-legislation-databases test-client {:language "en"})]
          (is (true? (:success response)))
          (is (= "https://api.canlii.org/v1/legislationBrowse/en/"
                 (:url @captured)))))))

  (testing "browse-legislation interpolates language and database-id"
    (let [[captured stub] (stub-request {:status 200 :body {}})]
      (with-redefs [http/request stub]
        (sut/browse-legislation test-client {:language "en" :database-id "ons"})
        (is (= "https://api.canlii.org/v1/legislationBrowse/en/ons/"
               (:url @captured))))))

  (testing "legislation-metadata interpolates all three path params"
    (let [[captured stub] (stub-request {:status 200 :body {}})]
      (with-redefs [http/request stub]
        (sut/legislation-metadata test-client
                                  {:language       "en"
                                   :database-id    "ons"
                                   :legislation-id "rso-1990-c-w11"})
        (is (= "https://api.canlii.org/v1/legislationBrowse/en/ons/rso-1990-c-w11/"
               (:url @captured)))))))

(deftest missing-path-param-throws
  (testing "a missing {placeholder} surfaces as ex-info before any request"
    (let [[captured stub] (stub-request {:status 200 :body {}})]
      (with-redefs [http/request stub]
        (is (thrown? clojure.lang.ExceptionInfo
                     (sut/case-metadata test-client {:language "en" :database-id "onca"})))
        (is (nil? @captured) "no request should be attempted")))))
