# HTTP Server API

Module: `im.ttalk/clj-agent-server`, `im.ttalk/clj-agent-server-jetty`

Source: [`modules/clj-agent-server/src`](../../../modules/clj-agent-server/src)

The server module is written to the Ring SPEC. It produces handlers and streaming
response bodies; it does not depend on a particular HTTP server implementation.

## Route assembly

Namespaces: `im.ttalk.agent.server.agui`, `im.ttalk.agent.server.mcp`,
`im.ttalk.agent.server.routing`

```clojure
(require '[im.ttalk.agent.server.agui :as agui]
         '[im.ttalk.agent.server.mcp :as mcp-http]
         '[im.ttalk.agent.server.routing :as routing])

(def handler
  (routing/router
    (into (agui/routes "/api/agent" host)
          (mcp-http/routes "/mcp" mcp-server))))
```

AG-UI endpoints include:

```text
GET    {prefix}/info
POST   {prefix}/agent/:id/run
POST   {prefix}/agent/:id/connect
POST   {prefix}/agent/:id/stop/:threadId
GET    {prefix}/threads
GET    {prefix}/threads/:id/messages
GET    {prefix}/threads/:id/events
GET    {prefix}/threads/:id/state
PATCH  {prefix}/threads/:id
POST   {prefix}/threads/:id/archive
DELETE {prefix}/threads/:id
```

These thread endpoints require a configured `:thread-store`. The handler returns
501 for a store that does not implement a requested capability slot.

MCP over Streamable HTTP uses `POST {prefix}`; route support is provided by
`im.ttalk.agent.server.mcp`.

## Host and security

`im.ttalk.agent.server.agui.host` owns run pumps, replay rings, heartbeat shutdown,
and cancellation hooks. CORS is deny-by-default when an Origin is present; use
`im.ttalk.agent.server.agui.http/origin-allow` to configure explicit origins.

`im.ttalk.agent.server.guard` supplies local-loopback Host checking and token
authentication through headers, bearer tokens, or query parameters. Bind non-loopback
interfaces only with an explicit security plan.

## Ring adapters

The default host is `im.ttalk.agent.server.jetty`:

```clojure
(require '[im.ttalk.agent.server.jetty :as jetty])

(def server (jetty/start handler {:port 3000}))
(jetty/base-address server)
(jetty/stop! server)
```

Jetty supports Ring SPEC streaming natively. Adapter-side shims for http-kit and
Undertow are available in `im.ttalk.agent.server.adapter.http-kit` and
`.adapter.undertow` because those servers do not implement the SPEC streaming body
protocol.
