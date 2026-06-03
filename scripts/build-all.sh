#!/bin/bash
set -e

echo "=========================================="
echo "Building all clj-agent modules"
echo "=========================================="

# 按依赖顺序构建
MODULES=(
  "clj-agent-core"
  "clj-agent-llm"
  "clj-agent-tools"
)

for module in "${MODULES[@]}"; do
  echo ""
  echo "----------------------------------------"
  echo "Building $module..."
  echo "----------------------------------------"
  cd "modules/$module"
  clojure -T:build clean
  clojure -T:build jar
  cd ../..
  echo "✓ $module built successfully"
done

echo ""
echo "=========================================="
echo "✓ All modules built successfully!"
echo "=========================================="
echo ""
echo "Generated JAR files:"
find modules -name "*.jar" -type f
