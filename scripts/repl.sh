#!/bin/bash
# 启动包含 examples 的 REPL
#
# 用法:
#   ./scripts/repl.sh              # 启动 REPL
#   ./scripts/repl.sh <example>    # 加载指定 example 后进入 REPL
#
# 示例:
#   ./scripts/repl.sh simpleagent_examples
#   ./scripts/repl.sh kernel_test

cd "$(dirname "$0")/.."

if [ -n "$1" ]; then
  EXAMPLE="examples/${1%.clj}.clj"
  if [ ! -f "$EXAMPLE" ]; then
    echo "Example not found: $EXAMPLE"
    echo "Available examples:"
    ls examples/*.clj 2>/dev/null | sed 's|examples/||;s|\.clj$||' | sed 's/^/  /'
    exit 1
  fi
  exec clj -M -e "(load-file \"$EXAMPLE\")" -r
else
  exec clj -M -r
fi
