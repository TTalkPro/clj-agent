"use client";

/**
 * clj-agent 的 AG-UI runtime × CopilotKit 前端。
 *
 * 这个页面存在的唯一理由：**把「暂停等审批」渲染出来**。
 *
 * 服务端那半边（`examples/copilotkit/demo_server.clj`）不需要为它改任何东西——
 * 敏感工具暂停时，事件流里本来就有一个**没有结果的 tool call**（TOOL_CALL_START /
 * ARGS / END，然后 run 以 paused 收口）。CopilotKit 的 `useHumanInTheLoop` 正好
 * 认这个形状：同名注册一个前端工具，它就把那次调用渲染成一张卡片，并在
 * `status === "executing"` 时给你 `respond()`。
 *
 * 于是审批 UI 是**前端应用的事**，协议上不需要任何私货：
 *
 *   模型要调 wipe-database
 *     → 服务端 gate 判 :pause，发出 tool call 但不执行，run 以 paused 收口
 *     → 这里渲染「同意 / 拒绝」
 *     → respond("approved") → CopilotKit 把结果作为新一轮发回
 *     → 服务端认出「挂起的是**服务端**工具」→ 按**决策**恢复（真的去执行它）
 *
 * 对比：如果挂起的是**前端**工具（浏览器自己执行的那种），同一条路的载荷才是
 * 「工具结果」本身。两者的区别在服务端路由里判（见 http_kit_routes.clj）。
 */

import {
  CopilotChat,
  CopilotKitProvider,
  useHumanInTheLoop,
} from "@copilotkit/react-core/v2";
import { useState } from "react";

const RUNTIME_URL =
  process.env.NEXT_PUBLIC_RUNTIME_URL || "http://localhost:4002/api/copilotkit";

function ApprovalCard({ title, args, status, respond, result }: any) {
  const [choice, setChoice] = useState<string | null>(null);

  const decide = (decision: string) => {
    setChoice(decision);
    respond?.(decision);
  };

  const box: React.CSSProperties = {
    border: "1px solid #e5e7eb",
    borderRadius: 8,
    padding: 12,
    margin: "8px 0",
    background: "#fff",
  };

  return (
    <div style={box}>
      <div style={{ fontWeight: 600, marginBottom: 6 }}>⚠️ {title}</div>
      <pre style={{ fontSize: 12, background: "#f4f4f5", padding: 8, borderRadius: 6 }}>
        {JSON.stringify(args ?? {}, null, 2)}
      </pre>

      {status === "executing" && !choice && (
        <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
          <button
            onClick={() => decide("approved")}
            style={{ padding: "6px 14px", borderRadius: 6, border: "none", background: "#09090b", color: "#fff", cursor: "pointer" }}
          >
            同意执行
          </button>
          <button
            onClick={() => decide("rejected")}
            style={{ padding: "6px 14px", borderRadius: 6, border: "1px solid #e5e7eb", background: "#fff", cursor: "pointer" }}
          >
            拒绝
          </button>
        </div>
      )}

      {status === "inProgress" && (
        <div style={{ fontSize: 12, color: "#71717a", marginTop: 6 }}>正在请求审批…</div>
      )}
      {choice && !result && (
        <div style={{ fontSize: 12, color: "#71717a", marginTop: 6 }}>
          已{choice === "approved" ? "同意" : "拒绝"}，继续执行中…
        </div>
      )}
      {result && (
        <div style={{ fontSize: 12, color: "#16a34a", marginTop: 6 }}>结果：{String(result)}</div>
      )}
    </div>
  );
}

function Approvals() {
  // 与服务端 `deftool wipe-database {:sensitive true}` **同名注册**。
  // 注意：这不是要在前端新增一个工具——服务端已经有同名工具了，同名声明会在
  // ChatClient 装配期撞「工具名唯一」校验。所以服务端路由会把与服务端工具重名的
  // 前端声明**剔除**，只留它的渲染意图（见 http_kit_routes.clj 的 collide 处理）。
  useHumanInTheLoop({
    name: "wipe-database",
    description: "清空数据库（危险操作，需人工批准）",
    render: (props: any) => <ApprovalCard title="需要审批：清空数据库" {...props} />,
  });
  return null;
}

export default function Home() {
  return (
    <CopilotKitProvider runtimeUrl={RUNTIME_URL}>
      <Approvals />
      <div style={{ height: "100vh", display: "flex", flexDirection: "column" }}>
        <div style={{ padding: "10px 16px", borderBottom: "1px solid #e5e7eb", fontSize: 13 }}>
          clj-agent AG-UI runtime · <code>{RUNTIME_URL}</code>
          <span style={{ color: "#71717a" }}>　试试「请调用 wipe-database 工具清空数据库，confirm 传 YES」</span>
        </div>
        <div style={{ flex: 1, minHeight: 0 }}>
          <CopilotChat />
        </div>
      </div>
    </CopilotKitProvider>
  );
}
