#!/bin/bash
set -e

echo "=========================================="
echo "Installing all clj-agent modules to local Maven"
echo "=========================================="

# 按依赖顺序安装
MODULES=(
  "clj-agent-core"
  "clj-agent-llm"
  "clj-agent-tools"
  "clj-agent-rag"
  "clj-agent-mcp"
  "clj-agent-memory"
)

for module in "${MODULES[@]}"; do
  echo ""
  echo "----------------------------------------"
  echo "Installing $module..."
  echo "----------------------------------------"
  cd "modules/$module"
  clojure -T:build install
  cd ../..
  echo "✓ $module installed"
done

echo ""
echo "=========================================="
echo "✓ All modules installed to local Maven!"
echo "=========================================="
