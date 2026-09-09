#!/bin/bash
# 젠킨스 기동 래퍼 — 손으로 적으면 두 목록이 되는 값을 정본에서 읽어 주입한다
#
# ## 왜 compose 를 직접 부르지 않나
#
# 두 값이 저장소 정본에서 나와야 한다.
#   · `NODE_VERSION` ← `.nvmrc` (로컬·CI 가 같이 읽는 그 파일)
#   · `DOCKER_GID`   ← 호스트 `getent group docker` (머신마다 다르다)
# `.env` 에 적게 하면 사람이 옮겨 적고, 정본이 바뀌어도 따라오지 않는다.
# compose 파일에 `:?` 를 걸어 **비면 죽게** 해 두었으므로, 이 스크립트를 건너뛰면 기동이 실패한다.
#
# 사용.
#   ./bootstrap.sh up      기동 (이미지 빌드 포함)
#   ./bootstrap.sh down    정지
#   ./bootstrap.sh lock    설치된 플러그인 실제 버전을 plugins.lock.txt 로 고정
#   ./bootstrap.sh logs    로그 따라가기
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE=("docker" "compose" "-f" "$SCRIPT_DIR/docker-compose.jenkins.yml" "--env-file" "$SCRIPT_DIR/.env")

if [ ! -f "$SCRIPT_DIR/.env" ]; then
  echo "❌ $SCRIPT_DIR/.env 가 없다. cp .env.example .env 후 관리자 비밀번호를 채워라." >&2
  exit 1
fi

# ── 정본에서 읽는다 ──────────────────────────────────────────────────────────
NODE_VERSION="$(tr -d '[:space:]' < "$REPO_ROOT/.nvmrc")"
[ -n "$NODE_VERSION" ] || { echo "❌ .nvmrc 가 비었다" >&2; exit 1; }

DOCKER_GID="$(getent group docker | cut -d: -f3)"
[ -n "$DOCKER_GID" ] || { echo "❌ docker 그룹이 없다 — 이 호스트에서 DooD 는 불가능하다" >&2; exit 1; }

export NODE_VERSION DOCKER_GID

# ★비밀번호가 비었는지 여기서 본다. compose 의 `:?` 는 **미설정**만 잡고 빈 문자열은 통과시켜서,
#   비밀번호 없는 관리자 계정이 조용히 만들어진다.
if ! grep -qE '^JENKINS_ADMIN_PASSWORD=.+' "$SCRIPT_DIR/.env"; then
  echo "❌ .env 의 JENKINS_ADMIN_PASSWORD 가 비었다. 사람이 직접 채워라." >&2
  exit 1
fi

case "${1:-up}" in
  up)
    echo "→ NODE_VERSION=$NODE_VERSION (.nvmrc) · DOCKER_GID=$DOCKER_GID (getent)"
    "${COMPOSE[@]}" up -d --build
    echo "→ 기동 대기…"
    for i in $(seq 1 60); do
      if curl -fsS -o /dev/null http://127.0.0.1:18081/login 2>/dev/null; then
        echo "✅ 젠킨스 응답 (${i}회차) — http://127.0.0.1:18081/"
        # ★루프백 전용인지 확인한다. host 네트워크라 여기가 유일한 방어선이다.
        if ss -tlnp 2>/dev/null | grep -q '0\.0\.0\.0:18081'; then
          echo "🚨 18081 이 0.0.0.0 에 붙었다 — JENKINS_OPTS 를 확인하고 즉시 내려라" >&2
          exit 2
        fi
        echo "✅ 18081 루프백 전용 확인"
        exit 0
      fi
      sleep 5
    done
    echo "❌ 5분 안에 안 떴다"; "${COMPOSE[@]}" logs --tail 50; exit 1
    ;;
  down)  "${COMPOSE[@]}" down ;;
  logs)  "${COMPOSE[@]}" logs -f --tail 100 ;;
  lock)
    # 설치된 실제 버전을 뽑는다. plugins.txt 는 「무엇이 필요한가」, 이 파일은 「무엇이 깔렸나」다.
    docker exec bts-jenkins bash -c \
      'ls /var/jenkins_home/plugins/*.jpi 2>/dev/null | while read -r p; do
         n=$(basename "$p" .jpi)
         v=$(unzip -p "$p" META-INF/MANIFEST.MF 2>/dev/null | tr -d "\r" | sed -n "s/^Plugin-Version: //p")
         echo "$n:$v"
       done' | sort > "$SCRIPT_DIR/plugins.lock.txt"
    echo "✅ $(wc -l < "$SCRIPT_DIR/plugins.lock.txt") 개 고정 → plugins.lock.txt"
    ;;
  *) echo "사용. $0 {up|down|logs|lock}" >&2; exit 1 ;;
esac
