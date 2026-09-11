#!/usr/bin/env bash
# protected-paths.txt 를 읽어 경로를 한 줄에 하나씩 낸다 — 읽는 쪽이 둘이라 파싱도 한 벌로 둔다.
#
# ★왜 목록뿐 아니라 **파싱까지** 한 벌인가. 목록만 파일로 빼고 파싱을 양쪽에 복제하면
#   한쪽의 주석 처리나 공백 처리가 달라지는 순간 두 벌이 된다 — 목록을 나눈 의미가 없다.
#   그리고 그 차이는 「배포가 지운다」와 「자격증명이 노출된다」로 다르게 발현해서
#   증상만 보고는 같은 원인이라고 생각하기 어렵다.
#
# ★이 스크립트가 판별식의 **실행 대상**이다. 소스를 읽는 검사가 아니라 실제로 돌려서
#   무엇이 나오는지 본다. 계약. scripts/workflow/deploy-protected-paths-single-source.test.ts
set -euo pipefail

F="$(cd "$(dirname "$0")" && pwd)/protected-paths.txt"
[ -f "$F" ] || { echo "❌ $F 이 없다 — 무엇을 지키는지 모른다" >&2; exit 1; }

N=0
while IFS= read -r line; do
  line="${line%%#*}"                                  # 인라인 주석 제거
  line="$(printf '%s' "$line" | tr -d '[:space:]')"   # 공백 제거 (경로에 공백은 없다)
  [ -n "$line" ] || continue
  printf '%s\n' "$line"
  N=$((N + 1))
done < "$F"

# 0건은 「보호할 것이 없다」가 아니라 「파싱이 깨졌다」다. 부르는 쪽이 그것을 빈 제외
# 목록으로 받아 `--delete` 를 돌리면 지워진다 — 침묵하지 않고 실패한다.
[ "$N" -gt 0 ] || { echo "❌ $F 에서 읽어낸 경로가 0건이다 — 파싱이 깨졌다" >&2; exit 1; }
