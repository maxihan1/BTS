#!/usr/bin/env bash
# 경로 목록 파일을 읽어 한 줄에 하나씩 낸다 — 읽는 쪽이 여럿이라 파싱은 한 벌로 둔다.
#
# 사용. read-protected-paths.sh [protected|secret|secret-owners]   (기본 protected)
#   protected      배포가 **지우면 안 되는** 것 — rsync `--delete` 제외 목록
#   secret         파이프라인이 **읽으면 안 되는** 것 — chown 뒤 되돌릴 경로 목록
#   secret-owners  위와 같은 목록에 **소유자 uid** 를 붙여 낸다 (`경로<TAB>uid`)
#
# ★★secret 에 소유자를 적는 이유 (2026-09-14 실측). 종전에는 `chown -R root:root` 로
#   **일괄** 되돌렸다. 그런데 `infra/secrets` 의 JWT 서명키는 **백엔드 컨테이너
#   (uid 999)가 읽어야 한다.** root 400 이 되자 운영 백엔드가 기동에서 죽었다 —
#   `PEM 파일 파싱 실패 ... (Permission denied) (EC-19)`.
#
#   목록의 뜻은 「젠킨스가 읽으면 안 된다」인데 구현은 「root 말고 아무도 못 읽는다」
#   였다. 의도보다 범위가 넓었고, 젠킨스만 막으려다 백엔드까지 막았다.
#
#   처방은 권한을 푸는 것이 아니라 **소유자를 정확히 지정하는 것**이다. JWT 키를
#   uid 999 소유로 두면 백엔드는 읽고 젠킨스(uid 1000)는 못 읽는다 — 원래 의도
#   그대로다. 그래서 목록이 「무엇을」에 더해 「누구에게」까지 말한다.
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
  protected)     BASE=protected-paths.txt ;;
  secret)        BASE=secret-paths.txt ;;
  secret-owners) BASE=secret-paths.txt ;;
  *) echo "❌ 알 수 없는 목록 종류 '$KIND' — protected · secret · secret-owners" >&2; exit 2 ;;
esac

F="$(cd "$(dirname "$0")" && pwd)/$BASE"
[ -f "$F" ] || { echo "❌ $F 이 없다 — 무엇을 지키는지 모른다" >&2; exit 1; }

# ★필드 분리에 glob 이 끼지 않게 꺼 둔다. 경로에 `*` 가 없더라도, 있는 날 조용히
#   디렉터리 목록으로 부풀어 엉뚱한 것을 chown 하는 자리를 만들지 않는다.
set -f

N=0
while IFS= read -r line; do
  line="${line%%#*}"          # 인라인 주석 제거
  # 공백으로 필드를 나눈다 — 경로에 공백은 없다. 첫 필드가 경로, 둘째가 소유자 uid.
  # shellcheck disable=SC2086
  set -- $line
  [ "$#" -ge 1 ] || continue
  P="$1"
  OWNER="${2:-}"
  case "$KIND" in
    protected | secret)
      printf '%s\n' "$P"
      ;;
    secret-owners)
      # ★소유자가 없으면 **죽는다.** 기본값을 root 로 두면 종전 고장이 그대로 돌아온다 —
      #   적기를 잊은 줄 하나가 조용히 전부 잠그는 쪽으로 흐른다.
      [ -n "$OWNER" ] || {
        echo "❌ '$P' 에 소유자 uid 가 없다 — 누가 읽어야 하는지 모른다." >&2
        echo "   형식. <경로> <uid>   예) infra/secrets 999" >&2
        exit 1
      }
      case "$OWNER" in
        '' | *[!0-9]*)
          echo "❌ '$P' 의 소유자 '$OWNER' 가 숫자 uid 가 아니다 — 이름은 호스트마다 다르다." >&2
          exit 1
          ;;
      esac
      printf '%s\t%s\n' "$P" "$OWNER"
      ;;
  esac
  N=$((N + 1))
done < "$F"

# 0건은 「지킬 것이 없다」가 아니라 「파싱이 깨졌다」다. 부르는 쪽이 그것을 빈 제외 목록으로
# 받아 `--delete` 를 돌리면 지워지고, 빈 복구 목록으로 받으면 자격증명이 노출된 채 남는다.
[ "$N" -gt 0 ] || { echo "❌ $F 에서 읽어낸 경로가 0건이다 — 파싱이 깨졌다" >&2; exit 1; }
