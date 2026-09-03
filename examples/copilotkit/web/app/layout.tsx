import type { ReactNode } from "react";
import "@copilotkit/react-core/v2/styles.css";

export const metadata = { title: "clj-agent × CopilotKit" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="zh">
      <body style={{ margin: 0, fontFamily: "system-ui, sans-serif" }}>{children}</body>
    </html>
  );
}
