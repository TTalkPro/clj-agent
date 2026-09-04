# `/threads/:id` 的 DELETE 跨源过不去 —— 写端点等于不存在

## 症状

浏览器端删一条会话，只得到一句 `Failed to fetch`（没有状态码、没有响应体，
**看起来像网络断了**）。预检本身是 204：

```
HTTP/1.1 204 No Content
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, OPTIONS      ← DELETE 不在里面
```

## 位置

`examples/copilotkit/http_kit_routes.clj:44` `cors-headers`
—— `Access-Control-Allow-Methods` 只有 `GET, POST, OPTIONS`。

而 `handle-threads`（同文件 `:427`）**实现了** DELETE：

```clojure
(and thread-id (= :delete method))
(do (rt/forget! runtime thread-id) …)
```

## 怎么撞上的

```bash
curl -s -X OPTIONS -D- -o /dev/null \
  -H "Origin: http://localhost:3002" \
  -H "Access-Control-Request-Method: DELETE" \
  http://localhost:4002/api/copilotkit/threads/x
```

浏览器里则是：会话抽屉上放一颗删除钮 → 点 → `Failed to fetch`。

## 影响面

- **谁会撞**：任何跨源的浏览器客户端（`:3002` 的 dev server 就是）。
  服务端到服务端不受影响 —— 所以 curl 直接 DELETE 是通的，**只有浏览器过不去**。
- **撞了会怎样**：功能在服务端存在、在客户端不可用，而报文完全不提 CORS。
  我们最后是把删除钮**撤了**（宁可没有，也不摆一颗点了必失败的）。
- 同理受影响的还有 `POST /threads/:id`（改名）与 `…/archive` —— 那两条方法在
  名单里，不受此限；只有 DELETE 这一条被卡。

## 建议

`Allow-Methods` 加上 `DELETE`（`cors-headers` 那一行）。docstring 里已经写了
「真部署换成你的域名白名单」，方法名单同理属于那一格，跟着改就行。
