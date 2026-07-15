#!/bin/bash
set -e

echo "=========================================="
echo "Testing all clj-agent modules"
echo "=========================================="

# 按依赖顺序测试（client 曾长期缺席本列表——CI matrix 有它，本脚本没有，
# 于是本地 test-all 静默跳过整个 Agent 运行时）
MODULES=(
  "clj-agent-core"
  "clj-agent-client"
  "clj-agent-provider"
)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
failed=()

for module in "${MODULES[@]}"; do
  echo ""
  echo "----------------------------------------"
  echo "Testing $module..."
  echo "----------------------------------------"
  if (cd "$ROOT/modules/$module" && clojure -M:test); then
    echo "✓ $module passed"
  else
    echo "✗ $module FAILED"
    failed+=("$module")
  fi
done

echo ""
echo "=========================================="
if [ ${#failed[@]} -eq 0 ]; then
  echo "✓ All modules passed!"
else
  echo "✗ Failed modules: ${failed[*]}"
  exit 1
fi
echo "=========================================="
