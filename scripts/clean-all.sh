#!/bin/bash
set -e

echo "=========================================="
echo "Cleaning all clj-agent modules"
echo "=========================================="

for module in modules/*; do
  if [ -d "$module" ]; then
    echo "Cleaning $module..."
    cd "$module"
    rm -rf target
    cd ../..
  fi
done

echo "✓ All modules cleaned"
