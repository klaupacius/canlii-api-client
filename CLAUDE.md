# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Clojure client library for the [CanLII REST API](https://api.canlii.org) (Canadian legal databases — court cases and legislation). The README and `build.clj` pom metadata still contain `deps-new` template placeholders ("FIXME: my new library."); the actual implementation lives in `src/canlii_api_client/core.clj`.

## Commands

```bash
# Run tests (kaocha)
clojure -M:test

# Run a single test by id (namespaced symbol)
clojure -M:test --focus canlii-api-client.core-test/list-case-databases-test

# Build pipeline: run tests, then build JAR into target/
clojure -T:build ci

# Install JAR locally / deploy to Clojars (requires `ci` first)
clojure -T:build install
clojure -T:build deploy   # needs CLOJARS_USERNAME / CLOJARS_PASSWORD
```

Note: the `:test` alias runs kaocha (`-m kaocha.runner`), but `build.clj`'s `test` task invokes `cognitect.test-runner` — be aware these are two different runners if results differ. Formatting follows `.cljfmt.edn` (`{:align-associative? true}`); maps in this codebase are vertically aligned.

The `CANLII_API_KEY` environment variable must be set for live requests (it is read once at namespace load into `api-config`).

## Architecture

All public functions are thin wrappers over a single private `request` pipeline. To add or change an endpoint, you almost always only touch `core.clj`:

1. **`build-path`** — takes a URL template with `{placeholder}` segments and the params map. It converts each placeholder to a kebab-case keyword, looks it up in params, substitutes it, and throws `ex-info` if missing. It returns `[interpolated-url remaining-params]` — params consumed as path segments are removed so the rest become query params.
2. **`kebab-keys->camel-query-params`** — drops nil-valued params, then converts remaining kebab keys to camelCase strings (the API's query-param convention). This is why public fns can accept optional filters as nils and they simply disappear.
3. **`request`** — assembles the hato request, always appends `api_key`, sets `:throw-exceptions? false`, and normalizes every outcome into a uniform result map: `{:success true :data body}` or `{:success false :error-code N :message ...}`. Both HTTP error statuses and thrown exceptions are funneled into this shape, so callers never see raw exceptions.

**Key conventions worth preserving:**
- Public fns take a single map and destructure it; they translate kebab-case Clojure keys into the API's mixed path/query naming. Callers never deal with URLs or camelCase.
- The HTTP client is held in `*http-client*`, a `^:dynamic` var. Tests rebind it via `binding` to inject a stub client — **do not** replace this with a plain def, or the test seam breaks.
- The `caseCitator/*` endpoints (`cited-cases`, `cited-legislations`, `citing-cases`) hardcode `:language "en"` because the API only supports English there.

## Testing approach

`core_test.clj` builds a real hato client but swaps the `::hato.middleware/send` interceptor for a stub handler (`stub-client`), keeping JSON encode/decode middleware intact while making zero network calls. Tests assert on the captured request (interpolated URL, presence of `api_key`) and on the decoded response shape. Follow this pattern for new endpoint tests rather than mocking `http/request` directly.
