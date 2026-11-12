# Tools and HTTP API

## Workspace tools

Module: `im.ttalk/clj-agent-tools`

Source: [`modules/clj-agent-tools/src`](../../../modules/clj-agent-tools/src)

Namespace: `im.ttalk.agent.tools`

```clojure
(require '[im.ttalk.agent.tools :as tools]
         '[im.ttalk.agent.tools.exec :as exec])

(def workspace
  (tools/workspace
    {:root "/absolute/path/to/project"
     :exec {:backend (exec/local-backend)}}))

(def agent-tools
  (tools/workspace-tools
    workspace
    {:groups #{:files :edit :delete :exec :notes}}))
```

| Group | Tools |
|---|---|
| `:files` | `read_file`, `list_dir`, `glob`, `grep` |
| `:edit` | `write_file`, `edit_file`, `apply_patch` |
| `:delete` | `delete_path` |
| `:exec` | `exec`, `exec_command`, `write_stdin` |
| `:web-fetch` | `web_fetch` |
| `:skill` | `skill` |
| `:notes` | `notes_list`, `notes_read`, `notes_write` |

Mutating and execution tools are sensitive by default and participate in the
normal approval mechanism. `preview` uses the same planning function as execution,
so an approval prompt can show the exact diff without applying it. `close!` ends
exec sessions and releases workspace resources.

Execution is intentionally sandbox-agnostic. Wrap the command runner with
`im.ttalk.agent.tools.exec/argv-prefix-backend` when your deployment requires
bubblewrap or another external isolation command.

## Standalone HTTP client

Module: `im.ttalk/clj-http`

Source: [`modules/clj-http/src`](../../../modules/clj-http/src)

Namespace: `im.ttalk.http`

This module is independent of clj-agent's model classes and has no third-party
dependencies beyond Clojure. It exposes Ring-shaped request/response maps on top of
`java.net.http`.

```clojure
(require '[im.ttalk.http :as http])

(http/request
  {:method :post
   :url "https://api.example.com/items"
   :headers {"Authorization" "Bearer ..."}
   :body "{\"name\":\"example\"}"
   :timeout-ms 30000})

(http/request-async {:url "https://api.example.com/health"})
```

| Function | Purpose |
|---|---|
| `client` / `default-client` | Build or reuse an `HttpClient` |
| `request` / `request-async` | Synchronous or future-based request |
| `stream-lines` | Line-by-line streaming with first-byte and idle watchdogs |
| `header` / `header-values` / `media-type` | Case-insensitive header access |
| `query-string` / `with-query` / `url-encode` | URL query helpers |
| `success?` / `timeout-error?` | Response and watchdog predicates |

SSE parsing lives in `im.ttalk.http.sse`; proxy, failure, and HTTP-date helpers live
in `.proxy`, `.failure`, and `.date`. JSON encoding and retry policy are caller
concerns in this standalone module.
