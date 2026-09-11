#!/usr/bin/env bash
# 경로 목록 파일을 읽어 한 줄에 하나씩 낸다 — 읽는 쪽이 여럿이라 파싱은 한 벌로 둔다.
#
# 사용. read-protected-paths.sh [protected|secret]   (기본 protected)
#   protected  배포가 **지우면 안 되는** 것 — rsync `--delete` 제외 목록
#   secret     파이프라인이 **읽으면 안 되는** 것 — chown 뒤 root 로 되돌릴 목록
#
# ★왜 둘로 나뉘나. `backups/` 는 지워지면 안 되지만 배포가 **거기에 DB 덤프를 쓴다** —
#   읽기·쓰기가 필요하다. `.env` 는 지워져도 안 되고 읽혀서도 안 된다. 의미가 다르다.
#   2026-09-11 에 둘을 한 목록으로 다뤘더니 chown 복구가 backups 까지 root 로 잠가
#   배포 5단계 DB 덤프가 쓰기 불가로 죽을 상태가 됐다.
#
# ★왜 목록만 나누고 파싱은 안 나누나. 목록을 파일로 빼고 파싱을 복제하면 한쪽의 주석이나
#   공백 처리가 달라지는 순간 다시 두 벌이 된다 — 나눈 의미가 없다.
#
# ★이 스크립트가 판별식의 **실행 대상**이다. 소스를 읽는 검사가 아니라 실제로 돌려서
#   무엇이 나오는지 본다. 계약. scripts/workflow/deploy-protected-paths-single-source.test.ts
set -euo pipefail

KIND="${1:-protected}"
case "$KIND" in
  protected) BASE=protected-paths.txt ;;
  secret)    BASE=secret-paths.txt ;;
  *) echo "❌ 알 수 없는 목록 종류 '$KIND' — protected 또는 secret" >&2; exit 2 ;;
esac

F="$(cd "$(dirname "$0")" && pwd)/$BASE"
[ -f "$F" ] || { echo "❌ $F 이 없다 — 무엇을 지키는지 모른다" >&2; exit 1; }

N=0
while IFS= read -r line; do
  line="${line%%#*}"                                  # 인라인 주석 제거
  line="$(printf '%s' "$line" | tr -d '[:space:]')"   # 공백 제거 (경로에 공백은 없다)
  [ -n "$line" ] || continue
  printf '%s\n' "$line"
  N=$((N + 1))
done < "$F"

# 0건은 「지킬 것이 없다」가 아니라 「파싱이 깨졌다」다. 부르는 쪽이 그것을 빈 제외 목록으로
# 받아 `--delete` 를 돌리면 지워지고, 빈 복구 목록으로 받으면 자격증명이 노출된 채 남는다.
[ "$N" -gt 0 ] || { echo "❌ $F 에서 읽어낸 경로가 0건이다 — 파싱이 깨졌다" >&2; exit 1; }
