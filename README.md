# canlii-api-client

A small Clojure client for the [CanLII REST API](https://github.com/canlii/API_documentation) —
read-only access to Canadian court decisions and legislation.

You need a CanLII API key (request one through CanLII's
[feedback form](https://www.canlii.org/en/feedback/feedback.html)).

## Installation

TODO

## Usage

Build a client once, then pass it to each endpoint function. The client carries
your API key, base URL, and timeout.

```clojure
(require '[canlii-api-client.core :as canlii])

;; Pass the key explicitly...
(def client (canlii/make-client {:api-key "your-api-key"}))

;; ...or read it from the CANLII_API_KEY environment variable:
(def client (canlii/client-from-env))

(canlii/list-case-databases client {:language "en"})
;; => {:success true :data {:caseDatabases [{:databaseId "onca" ...} ...]}}
```

`make-client` also accepts `:base-url`, `:timeout` (milliseconds), and a
pre-built hato `:http-client` to override the defaults.

### Result shape

Every endpoint returns a map rather than throwing. On success:

```clojure
{:success true
 :data    <decoded JSON body>}
```

On an HTTP error the `:error-code` is the integer status, with the server's
message when available:

```clojure
{:success false :error-code 404 :message "..."}
```

On a transport failure (the request never reached the server) `:error-code` is
the keyword `:exception` and `:error-category` is one of `:timeout`,
`:connection`, or `:transport`:

```clojure
{:success false :error-code :exception :error-category :timeout :message "..."}
```

So a successful call is `(:success result)`, and an integer `:error-code` always
means the server responded.

### Endpoints

All options are keywords in `kebab-case`; they are translated to the API's
`camelCase` query parameters for you.

| Function | Required options | Notes |
|---|---|---|
| `list-case-databases` | `:language` | `"en"` or `"fr"` |
| `browse-cases` | `:language`, `:database-id` | `:offset` (default 0) and `:result-count` (default 10); optional date filters below |
| `case-metadata` | `:language`, `:database-id`, `:case-id` | |
| `cited-cases` | `:database-id`, `:case-id` | English only |
| `citing-cases` | `:database-id`, `:case-id` | English only |
| `cited-legislations` | `:database-id`, `:case-id` | English only |
| `list-legislation-databases` | `:language` | |
| `browse-legislation` | `:language`, `:database-id` | |
| `legislation-metadata` | `:language`, `:database-id`, `:legislation-id` | |

`browse-cases` accepts these optional date filters (all `YYYY-MM-DD`, inclusive):
`:published-before`/`:published-after`, `:modified-before`/`:modified-after`,
`:changed-before`/`:changed-after`, and
`:decision-date-before`/`:decision-date-after`.

```clojure
(canlii/browse-cases client
                     {:language            "en"
                      :database-id         "onca"
                      :result-count        25
                      :decision-date-after "2020-01-01"})

(canlii/cited-cases client {:database-id "onca" :case-id "2020onca1"})
```

## Development

Run the tests (kaocha):

```bash
clojure -M:test                                          # all tests
clojure -M:test --focus canlii-api-client.core-test/result-mapping  # one test
```

Run the CI pipeline and build a JAR into `target/`:

```bash
clojure -T:build ci
```

Install the JAR locally (run `ci` first):

```bash
clojure -T:build install
```

Deploy to Clojars (run `ci` first; needs `CLOJARS_USERNAME` and
`CLOJARS_PASSWORD`):

```bash
clojure -T:build deploy
```

## License

Copyright © 2026 Jostein

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0).
