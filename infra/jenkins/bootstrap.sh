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

# ★★deploy key 를 컨테이너의 jenkins 가 읽을 수 있게 만든다.
#
# 이 줄이 없으면 **가장 나쁜 형태로 실패한다.** 키 파일이 root 소유면 컨테이너의 jenkins(uid
# 1000)가 못 읽고, JCasC 의 `${readFile:}` 는 그때 오류를 내지 않고 **빈 문자열로 대체**한다.
#   WARNING … Error looking up file '/var/jenkins_conf/deploy-key' … Will default to empty string
# 그러면 자격증명이 `/manage/credentials` 에 **정상 등록된 것으로 보이는데** 개인키가 비어 있고,
# 실패는 한참 뒤 클론 단계에서 `Permission denied (publickey)` 로 나타난다
# (2026-09-09 실측 — 「등록됐다」와 「쓸 수 있다」는 다르다).
#
# uid 를 상수로 적지 않는다. 이미지의 jenkins uid 가 바뀌면 그 숫자가 두 번째 목록이 된다 —
# 컨테이너에게 직접 묻는다. 컨테이너가 아직 없으면(최초 기동) 이미지에서 묻는다.
DEPLOY_KEY="$SCRIPT_DIR/.deploy-key"
if [ -f "$DEPLOY_KEY" ]; then
  JENKINS_UID="$(docker run --rm --entrypoint id bts-jenkins:local -u 2>/dev/null \
    || docker run --rm --entrypoint id jenkins/jenkins:lts-jdk21 -u 2>/dev/null)"
  if [ -n "$JENKINS_UID" ]; then
    chown "$JENKINS_UID" "$DEPLOY_KEY"
    chmod 600 "$DEPLOY_KEY"
    echo "→ deploy key 소유권 uid=$JENKINS_UID (컨테이너 jenkins) · 600"
  else
    echo "⚠️ jenkins uid 를 못 구했다 — deploy key 를 컨테이너가 못 읽으면 클론이 실패한다" >&2
  fi
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
  job)
    # 잡 정의를 저장소 파일에서 적용한다. UI·API 로만 만든 잡은 저장소에 흔적이 없어
    # 컨트롤러를 잃으면 무엇이 걸려 있었는지 아무도 모른다.
    BRANCH="${2:-main}"
    . "$SCRIPT_DIR/.env"
    AUTH="$JENKINS_ADMIN_ID:$JENKINS_ADMIN_PASSWORD"
    CJ="$(mktemp)"; XML="$(mktemp)"
    CRUMB="$(curl -s -u "$AUTH" -c "$CJ" http://127.0.0.1:18081/crumbIssuer/api/json \
      | sed -n 's/.*"crumb":"\([^"]*\)".*/\1/p')"
    sed "s|@BRANCH@|*/${BRANCH}|" "$SCRIPT_DIR/job-bts-ci.xml" > "$XML"

    # ★`charset=utf-8` 이 없으면 한글이 깨진다. Jenkins(Stapler)는 charset 미지정 본문을
    #   ISO-8859-1 로 읽는다 — JVM 이 UTF-8 이어도(file.encoding=UTF-8) 소용없다.
    #   2026-09-09 실측. `—`(E2 80 94)가 `303 242 &#x80; &#x94;` 로 저장돼 설명이
    #   「BTS CI â íì´íë¼ì¸…」이 됐다. 증상이 UI 에서만 보여서 원인을 엉뚱한 데서 찾기 쉽다.
    #
    # 있으면 갱신, 없으면 생성. 두 경로를 나눠야 기존 빌드 이력이 보존된다.
    if curl -sf -u "$AUTH" -o /dev/null http://127.0.0.1:18081/job/bts-ci/api/json; then
      URL="http://127.0.0.1:18081/job/bts-ci/config.xml"
    else
      URL="http://127.0.0.1:18081/createItem?name=bts-ci"
    fi
    CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST -u "$AUTH" -b "$CJ" \
      -H "Jenkins-Crumb: $CRUMB" -H 'Content-Type: application/xml; charset=utf-8' \
      --data-binary "@$XML" "$URL")"
    rm -f "$CJ" "$XML"
    case "$CODE" in
      200|302) echo "✅ 잡 적용 (브랜치 */${BRANCH})" ;;
      *) echo "❌ 잡 적용 실패 HTTP $CODE" >&2; exit 1 ;;
    esac

    # ★★적용 직후 **파라미터 없는** 빌드를 한 번 돌린다. 자동화가 아니라 복구다.
    #
    # 이 XML 에는 `parameters`·`triggers` 가 없다(정본이 Jenkinsfile 이라 일부러 비웠다).
    # 그래서 적용하는 순간 젠킨스가 학습해 뒀던 그 둘이 지워지고, 다음 호출이
    # `buildWithParameters` HTTP 400 으로 죽는다. 증상은 「FULL 이 갑자기 없는 파라미터가
    # 됐다」이고 원인과 전혀 안 닮았다.
    #
    # ★주석으로는 못 막는다는 것이 실증됐다 — 2026-09-09 에 이 함정을 XML 주석에 적어 두고도
    #   같은 날 **또 밟았다.** 그래서 사람이 기억할 일을 스크립트가 하게 옮긴다.
    #   `/build` 다. `buildWithParameters` 가 아니다 — 지워진 파라미터를 넘길 수 없다.
    CJ2="$(mktemp)"
    CRUMB2="$(curl -s -u "$AUTH" -c "$CJ2" http://127.0.0.1:18081/crumbIssuer/api/json \
      | sed -n 's/.*"crumb":"\([^"]*\)".*/\1/p')"
    RC="$(curl -s -o /dev/null -w '%{http_code}' -X POST -u "$AUTH" -b "$CJ2" \
      -H "Jenkins-Crumb: $CRUMB2" http://127.0.0.1:18081/job/bts-ci/build)"
    rm -f "$CJ2"
    if [ "$RC" = "201" ]; then
      echo "→ 등록 빌드 트리거 (parameters·triggers 재등록). 완료까지 몇 분 걸린다"
    else
      echo "⚠️ 등록 빌드 트리거 실패 HTTP $RC — 손으로 한 번 돌려야 파라미터가 살아난다" >&2
    fi
    ;;
  *) echo "사용. $0 {up|down|logs|lock|job [브랜치]}" >&2; exit 1 ;;
esac
