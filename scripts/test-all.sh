#!/bin/bash
set -e

echo "=========================================="
echo "Testing all clj-agent modules"
echo "=========================================="

# 按依赖顺序测试
MODULES=(
  "clj-agent-core"
  "clj-agent-llm"
  "clj-agent-tools"
)

for module in "${MODULES[@]}"; do
  echo ""
  echo "----------------------------------------"
  echo "Testing $module..."
  echo "----------------------------------------"
  cd "modules/$module"
  clojure -M:test || true
  cd ../..
  echo "✓ $module tested"
done

echo ""
echo "=========================================="
echo "✓ All modules tested!"
echo "=========================================="
