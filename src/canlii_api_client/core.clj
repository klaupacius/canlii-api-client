(ns canlii-api-client.core
  "A client for the CanLII REST API (Canadian court decisions and legislation).

  Build a client once with `make-client` (or `client-from-env`) and pass it as
  the first argument to every endpoint function.

  Every endpoint returns a result map rather than throwing:

    - success:         {:success true,  :data <decoded JSON body>}
    - HTTP error:      {:success false, :error-code <status int>, :message <str>}
    - transport error: {:success false, :error-code :exception,
                        :error-category :timeout|:connection|:transport,
                        :message <str>}

  A successful call is `(:success result)`; an integer `:error-code` always means
  the server responded. Option keys are kebab-case keywords and are translated to
  the API's camelCase query parameters automatically."
  (:require [hato.client :as http]
            [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private defaults
  {:base-url "https://api.canlii.org/v1"
   :timeout  5000})

;; -----
;; Internal helper functions
;; -----

(defn- build-path
  "Interpolate the `{placeholder}` segments of `path` using `params`.

  Returns a `[url remaining-params]` pair where `remaining-params` is `params`
  with every key consumed as a path segment removed, leaving the rest to become
  query parameters. Throws `ex-info` if a placeholder has no matching key."
  [path params]
  (let [consumed-params (volatile! #{})
        result          (str/replace
                         path
                         #"\{(\w+)\}"
                         (fn [[_ placeholder]]
                           (let [k (csk/->kebab-case-keyword placeholder)]
                             (if-let [v (get params k)]
                               (do (vswap! consumed-params conj k)
                                   (str v))
                               (throw (ex-info "Missing path parameter"
                                               {:placeholder  placeholder
                                                :expected-key k
                                                :params       params}))))))]
    [result (apply dissoc params @consumed-params)]))

(defn- kebab-keys->camel-query-params
  "Convert kebab-case option keys in `m` into camelCase string query-param keys,
  dropping any entry whose value is nil."
  [m]
  (->> m
       (remove (fn [[_ v]] (nil? v)))
       (into {} (map (fn [[k v]] [(csk/->camelCaseString k) v])))))

(defn- try-parse-json
  "Parse `s` as JSON with keyword keys, returning nil on any failure rather than
  throwing."
  [s]
  (try
    (json/parse-string s true)
    (catch Exception _ nil)))

(defn- error-message
  "Extract a human-readable message from an error response `body`.

  hato leaves the body of a non-2xx response as a raw string, so this copes with
  a JSON error payload, a plain text or HTML body, or an already-decoded map, and
  returns nil when no message can be found."
  [body]
  (cond
    (map? body)    (:message body)
    (string? body) (or (:message (try-parse-json body))
                       (not-empty (str/trim body)))
    :else          nil))

(defn- classify-exception
  "Classify a client-side exception `e` as `:timeout`, `:connection`, or
  `:transport`, so callers can distinguish a transport failure (the request never
  reached the server) from an HTTP error status."
  [e]
  (cond
    (instance? java.net.http.HttpTimeoutException e) :timeout
    (instance? java.net.ConnectException e)          :connection
    :else                                            :transport))

;; -----
;; Client construction and base request function
;; -----

(defn make-client
  "Build a CanLII API client to pass as the first argument to every endpoint.

  Options:
    :api-key     - CanLII API key (required; blank keys are rejected)
    :base-url    - API base URL; defaults to the public CanLII endpoint
    :timeout     - connect and request timeout in ms; defaults to 5000
    :http-client - a pre-built hato client to use instead of building one

  Throws `ex-info` when `:api-key` is missing or blank."
  [{:keys [api-key base-url timeout http-client]
    :or   {base-url (:base-url defaults)
           timeout  (:timeout defaults)}}]
  (when (str/blank? api-key)
    (throw (ex-info "An :api-key is required to build a client" {})))
  {:api-key     api-key
   :base-url    base-url
   :timeout     timeout
   :http-client (or http-client
                    (http/build-http-client
                     {:connect-timeout timeout
                      :redirect-policy :always}))})

(defn client-from-env
  "Build a client using the API key from the CANLII_API_KEY environment variable.

  Accepts the same option map as `make-client` for any non-key overrides."
  ([] (client-from-env {}))
  ([opts] (make-client (assoc opts :api-key (System/getenv "CANLII_API_KEY")))))

(defn- request
  "Issue `method` against `path` (relative to the client's base URL) with
  `params`, normalising the outcome into the standard result map.

  Placeholders in `path` are interpolated from `params`; the leftover params
  become camelCase query parameters, with the API key appended automatically."
  [{:keys [api-key base-url timeout http-client]} method path params]
  (let [[url query-params] (build-path (str base-url path) params)
        opts               {:http-client       http-client
                            :method            method
                            :url               url
                            :accept            :json
                            :as                :json
                            :timeout           timeout
                            :throw-exceptions? false
                            :query-params      (-> query-params
                                                   kebab-keys->camel-query-params
                                                   (assoc "api_key" api-key))}]
    (try
      (let [{:keys [status body]} (http/request opts)]
        (if (<= 200 status 299)
          {:success true
           :data    body}
          {:success    false
           :error-code status
           :message    (error-message body)}))
      (catch Exception e
        {:success        false
         :error-code     :exception
         :error-category (classify-exception e)
         :message        (.getMessage e)}))))

;; -----
;; Public API functions
;; -----

(defn list-case-databases
  "List the available court and tribunal databases for `:language` (\"en\" or \"fr\")."
  [client {:keys [language]}]
  (request client :get "/caseBrowse/{language}/" {:language language}))

(defn browse-cases
  "Browse decisions in a caselaw database.

  Options:
    :language     - \"en\" or \"fr\" (required)
    :database-id  - database to browse, e.g. \"onca\" (required)
    :offset       - index of the first result; defaults to 0
    :result-count - number of results to return; defaults to 10

  Optional inclusive date filters (each \"YYYY-MM-DD\"):
    :published-before / :published-after
    :modified-before / :modified-after
    :changed-before / :changed-after
    :decision-date-before / :decision-date-after"
  [client
   {:keys [language
           database-id
           offset
           result-count
           published-before
           published-after
           modified-before
           modified-after
           changed-before
           changed-after
           decision-date-before
           decision-date-after]
    :or   {offset       0
           result-count 10}}]
  (request client :get "/caseBrowse/{language}/{databaseId}/"
           {:language             language
            :database-id          database-id
            :offset               offset
            :result-count         result-count
            :published-before     published-before
            :published-after      published-after
            :modified-before      modified-before
            :modified-after       modified-after
            :changed-before       changed-before
            :changed-after        changed-after
            :decision-date-before decision-date-before
            :decision-date-after  decision-date-after}))

(defn case-metadata
  "Fetch metadata for a single case identified by `:language`, `:database-id`,
  and `:case-id`."
  [client {:keys [language database-id case-id]}]
  (request client :get "/caseBrowse/{language}/{databaseId}/{caseId}/"
           {:language    language
            :database-id database-id
            :case-id     case-id}))

(defn cited-cases
  "List the cases cited by the case identified by `:database-id` and `:case-id`.

  Only English is supported, so the language is always \"en\"."
  [client {:keys [database-id case-id]}]
  (request client :get "/caseCitator/{language}/{databaseId}/{caseId}/citedCases"
           {:language    "en"
            :database-id database-id
            :case-id     case-id}))

(defn cited-legislations
  "List the legislation cited by the case identified by `:database-id` and
  `:case-id`.

  Only English is supported, so the language is always \"en\"."
  [client {:keys [database-id case-id]}]
  (request client :get "/caseCitator/{language}/{databaseId}/{caseId}/citedLegislations"
           {:language    "en"
            :database-id database-id
            :case-id     case-id}))

(defn citing-cases
  "List the cases that cite the case identified by `:database-id` and `:case-id`.

  Only English is supported, so the language is always \"en\"."
  [client {:keys [database-id case-id]}]
  (request client :get "/caseCitator/{language}/{databaseId}/{caseId}/citingCases"
           {:language    "en"
            :database-id database-id
            :case-id     case-id}))

(defn list-legislation-databases
  "List the available legislation and regulation databases for `:language`
  (\"en\" or \"fr\")."
  [client {:keys [language]}]
  (request client :get "/legislationBrowse/{language}/"
           {:language language}))

(defn browse-legislation
  "List the legislation in the database identified by `:language` and
  `:database-id`."
  [client {:keys [language database-id]}]
  (request client :get "/legislationBrowse/{language}/{databaseId}/"
           {:language    language
            :database-id database-id}))

(defn legislation-metadata
  "Fetch metadata for a single statute or regulation identified by `:language`,
  `:database-id`, and `:legislation-id`."
  [client {:keys [language database-id legislation-id]}]
  (request client :get "/legislationBrowse/{language}/{databaseId}/{legislationId}/"
           {:language       language
            :database-id    database-id
            :legislation-id legislation-id}))

