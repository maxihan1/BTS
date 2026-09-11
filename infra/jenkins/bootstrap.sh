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
JENKINS_UID="$(docker run --rm --entrypoint id bts-jenkins:local -u 2>/dev/null \
  || docker run --rm --entrypoint id jenkins/jenkins:lts-jdk21 -u 2>/dev/null)"
[ -n "$JENKINS_UID" ] || echo "⚠️ jenkins uid 를 못 구했다 — 아래 소유권 조정이 전부 건너뛰어진다" >&2

DEPLOY_KEY="$SCRIPT_DIR/.deploy-key"
if [ -f "$DEPLOY_KEY" ] && [ -n "$JENKINS_UID" ]; then
  chown "$JENKINS_UID" "$DEPLOY_KEY"
  chmod 600 "$DEPLOY_KEY"
  echo "→ deploy key 소유권 uid=$JENKINS_UID (컨테이너 jenkins) · 600"
fi

# ★★named volume 마운트 지점을 컨테이너의 jenkins 가 쓸 수 있게 만든다.
#
# Docker 는 **이미지에 없는 경로**에 named volume 을 붙일 때 그 디렉터리를 root:root 로 만든다.
# 이미지에 있는 경로면 그 내용과 소유권을 복사해 주지만, 없으면 빈 root 디렉터리가 생긴다.
# `/var/jenkins_home/.gradle` 가 정확히 그 경우다.
#
# 실패가 기동에서 안 난다는 것이 이 함정의 핵심이다. 컨테이너는 정상으로 뜨고 파이프라인도
# 돌다가, **첫 `./gradlew` 호출**에서 죽는다 — 2026-09-09 빌드 #16 은 프론트 전량 30분을
# 다 돌고 나서 백엔드 시작 0초 만에 이걸로 떨어졌다.
#   Could not create parent directory for lock file /var/jenkins_home/.gradle/wrapper/….lck
# 배포키(위)와 같은 양식이다 — 「붙었다」와 「쓸 수 있다」는 다르다.
#
# ★볼륨 이름을 여기 적지 않는다. compose 의 서비스 정의로 실행해 정본을 하나로 둔다 —
#   이름을 옮겨 적으면 그것이 두 번째 목록이 되고, compose 에서 볼륨을 바꿔도 여기가 안 따라온다.
# ★대상 경로는 `VOLUME_PATHS` 하나로 모은다. `jenkins-volume-chown.test.ts` 가 이 목록과
#   compose 의 named volume 마운트 경로 차집합이 0 인지 검사한다.
VOLUME_PATHS="/var/jenkins_home/.gradle"
if [ -n "$JENKINS_UID" ]; then
  for VP in $VOLUME_PATHS; do
    if "${COMPOSE[@]}" run --rm --no-deps --user 0 --entrypoint chown jenkins \
         -R "$JENKINS_UID:$JENKINS_UID" "$VP" >/dev/null 2>&1; then
      echo "→ 볼륨 소유권 $VP → uid=$JENKINS_UID"
    else
      echo "⚠️ $VP chown 실패 — 첫 gradlew 호출에서 lock file 오류로 죽는다" >&2
    fi
  done
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
        # ★★「검사 못 함」을 통과로 찍지 않는다 (2026-09-11).
    #
    #   종전은 `ss -tlnp 2>/dev/null | grep -q '0.0.0.0:18081'` 하나였다. 두 가지가 문제였다.
    #     ① `ss` 가 없거나 권한이 없으면 stderr 가 버려지고 출력이 비어 **grep 이 실패** →
    #        「감지 안 됨」 = 통과. 검사를 못 한 것과 안전한 것이 같은 답을 냈다.
    #     ② `[::]:18081`(IPv6 any) 을 안 본다. 그 주소도 외부 노출이다.
    #
    #   `network_mode: host` 라 이것이 **유일한 방어선**이라고 compose 와 런북이 못박아 둔
    #   자리다. 그 자리의 검사가 「모른다」를 「안전하다」로 답하면 방어선이 아니다.
    if ! command -v ss > /dev/null 2>&1; then
      echo "🚨 ss 를 못 찾았다 — 18081 노출 여부를 **확인할 수 없다**." >&2
      echo "   이것은 통과가 아니다. iproute2 를 설치하고 다시 돌려라." >&2
      exit 2
    fi
    LISTEN="$(ss -tlnH 2>&1)" || {
      echo "🚨 ss 실행 실패 — 18081 노출 여부를 **확인할 수 없다**." >&2
      echo "$LISTEN" >&2
      exit 2
    }
    # 대상 포트가 목록에 아예 없으면 젠킨스가 안 떠 있는 것이다 — 그것도 「확인함」이 아니다.
    if ! printf '%s\n' "$LISTEN" | grep -q ':18081'; then
      echo "🚨 18081 을 듣는 프로세스가 없다 — 젠킨스가 안 떠 있다." >&2
      echo "   루프백 확인을 했다고 말할 수 없다." >&2
      exit 2
    fi
    if printf '%s\n' "$LISTEN" | grep -qE '(^|[^0-9.])(0\.0\.0\.0|\[?::\]?):18081'; then
          echo "🚨 18081 이 0.0.0.0 에 붙었다 — JENKINS_OPTS 를 확인하고 즉시 내려라" >&2
          exit 2
        fi
        echo "✅ 18081 루프백 전용 확인"

        # ── 배포 대상 디렉터리에 **쓸 수 있는지** ──────────────────────────────
        #
        # ★★마운트했다고 쓸 수 있는 것이 아니다 (2026-09-11 빌드 #47 실측).
        #
        #   compose 가 `/opt/bts:/opt/bts` 를 마운트했는데 그 트리는 `501:20 drwxr-xr-x`
        #   였고 컨테이너는 uid 1000 으로 돈다. 첫 배포의 `rsync -avz --delete` 가
        #   **한 파일도 못 쓰고** 128MB 를 보낸 뒤 `exit 23` 으로 죽었다.
        #
        #   사전 점검 47개 에이전트가 「/opt/bts 마운트 없음」은 잡고 이것은 놓쳤다.
        #   compose 를 **읽으면** 마운트가 보이고 충분해 보인다 — `touch` 를 **해봐야**
        #   보인다. 그래서 아래는 소스 검사가 아니라 컨테이너 안에서의 실제 쓰기다.
        #
        # ★왜 chown 이고 chmod 가 아닌가. `rsync -a` 의 `-p` 가 소스 퍼미션을 복사한다 —
        #   `chmod -R g+w` 로 풀어 두면 **첫 배포가 그 비트를 지우고** 두 번째부터 실패한다
        #   (성공 1회 뒤 침묵하는 최악의 양식). 소유자는 mode 와 무관하게 쓸 수 있고,
        #   `-o`(owner)는 root 만 쓸 수 있어 jenkins 가 소유권을 되돌리지 못한다.
        #   그래서 chown 만이 배포마다 유지된다.
        if docker exec bts-jenkins sh -c 'touch /opt/bts/.write-probe' 2>/dev/null; then
          docker exec bts-jenkins rm -f /opt/bts/.write-probe 2>/dev/null || true
          echo "✅ /opt/bts 쓰기 가능 — 소유권 교정 불필요"
        else
          JUID="$(docker exec bts-jenkins id -u)"
          JGID="$(docker exec bts-jenkins id -g)"
          echo "→ /opt/bts 에 못 쓴다. 소유권을 ${JUID}:${JGID} 로 교정한다"
          chown -R "${JUID}:${JGID}" /opt/bts

          # ★자격증명은 되돌린다. 파이프라인(= 저장소 코드)이 읽으면 안 되는 것들이다 —
          #   젠킨스 관리자 비밀번호와 GitHub 개인키가 여기 산다. chown 이 그것까지
          #   uid 1000 에 넘기면 **아무 Jenkinsfile 이나 그것을 읽을 수 있게 된다.**
          #
          # ★목록도 파싱도 여기 적지 않는다. 정본은 `../deploy/read-protected-paths.sh` 하나다 —
          #   `bts-deploy.sh` 의 rsync 제외 목록과 **같은 스크립트**를 부른다. 목록만 나누고
          #   파싱을 복제하면 주석·공백 처리가 갈리는 순간 다시 두 벌이 된다.
          #   계약. scripts/workflow/deploy-protected-paths-single-source.test.ts
          READER="$SCRIPT_DIR/../deploy/read-protected-paths.sh"
          if [ ! -x "$READER" ]; then
            echo "🚨 $READER 를 실행할 수 없다 — 무엇을 root 로 되돌려야 하는지 모른다." >&2
            echo "   방금 /opt/bts 전체를 uid ${JUID} 로 넘겼다. 자격증명이 노출된 상태다." >&2
            exit 2
          fi
          REVERTED=0
          while IFS= read -r p; do
            [ -n "$p" ] || continue
            [ -e "/opt/bts/$p" ] || continue
            chown -R root:root "/opt/bts/$p"
            REVERTED=$((REVERTED + 1))
          done < <("$READER")
          # ★0건은 「보호할 것이 없다」가 아니다. 프로세스 치환은 실패해도 while 을 멈추지
          #   않으므로 **개수로 판정한다.** 여기서 멈추지 않으면 자격증명이 uid 1000 소유로
          #   남고, 그 뒤로는 아무 Jenkinsfile 이나 관리자 비밀번호를 읽을 수 있다.
          if [ "$REVERTED" = "0" ]; then
            echo "🚨 root 로 되돌린 경로가 0건이다 — 읽기가 깨졌다. 자격증명이 노출된 상태다." >&2
            exit 2
          fi
          echo "→ 운영 자격증명 ${REVERTED}건 root 소유로 복구"

          # ★★교정했다고 끝이 아니다. **다시 해본다.** 「chown 했다」와 「쓸 수 있다」는 다르다.
          if ! docker exec bts-jenkins sh -c 'touch /opt/bts/.write-probe' 2>/dev/null; then
            echo "🚨 소유권 교정 후에도 /opt/bts 에 못 쓴다 — 배포는 실패한다." >&2
            ls -ld /opt/bts >&2
            exit 2
          fi
          docker exec bts-jenkins rm -f /opt/bts/.write-probe 2>/dev/null || true
          echo "✅ /opt/bts 쓰기 가능 확인"
        fi
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
    #
    # ★잡 이름을 여기 적지 않는다. `job-bts-<이름>.xml` 파일 목록에서 **유도**한다 —
    #   이름을 본문에 적으면 잡을 하나 더 만들 때 그 목록과 파일 목록이 갈리고,
    #   새 잡이 조용히 적용되지 않는다(그리고 아무도 모른다).
    BRANCH="${2:-main}"
    . "$SCRIPT_DIR/.env"
    AUTH="$JENKINS_ADMIN_ID:$JENKINS_ADMIN_PASSWORD"

    APPLIED=0
    for DEF in "$SCRIPT_DIR"/job-bts-*.xml; do
      [ -e "$DEF" ] || { echo "❌ job-bts-*.xml 이 하나도 없다" >&2; exit 1; }
      JOB="$(basename "$DEF" .xml)"; JOB="${JOB#job-}"

      CJ="$(mktemp)"; XML="$(mktemp)"
      CRUMB="$(curl -s -u "$AUTH" -c "$CJ" http://127.0.0.1:18081/crumbIssuer/api/json \
        | sed -n 's/.*"crumb":"\([^"]*\)".*/\1/p')"
      sed "s|@BRANCH@|*/${BRANCH}|" "$DEF" > "$XML"

      # ★`charset=utf-8` 이 없으면 한글이 깨진다. Jenkins(Stapler)는 charset 미지정 본문을
      #   ISO-8859-1 로 읽는다 — JVM 이 UTF-8 이어도(file.encoding=UTF-8) 소용없다.
      #   2026-09-09 실측. `—`(E2 80 94)가 `303 242 &#x80; &#x94;` 로 저장돼 설명이
      #   「BTS CI â íì´íë¼ì¸…」이 됐다. 증상이 UI 에서만 보여 원인을 엉뚱한 데서 찾기 쉽다.
      #
      # 있으면 갱신, 없으면 생성. 두 경로를 나눠야 기존 빌드 이력이 보존된다.
      if curl -sf -u "$AUTH" -o /dev/null "http://127.0.0.1:18081/job/$JOB/api/json"; then
        URL="http://127.0.0.1:18081/job/$JOB/config.xml"
      else
        URL="http://127.0.0.1:18081/createItem?name=$JOB"
      fi
      CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST -u "$AUTH" -b "$CJ" \
        -H "Jenkins-Crumb: $CRUMB" -H 'Content-Type: application/xml; charset=utf-8' \
        --data-binary "@$XML" "$URL")"
      rm -f "$CJ" "$XML"
      case "$CODE" in
        200|302) echo "✅ $JOB 적용 (브랜치 */${BRANCH})" ;;
        *) echo "❌ $JOB 적용 실패 HTTP $CODE" >&2; exit 1 ;;
      esac

      # ★★적용 직후 **파라미터 없는** 빌드를 한 번 돌린다. 자동화가 아니라 복구다.
      #
      # 이 XML 들에는 `parameters`·`triggers` 가 없다(정본이 Jenkinsfile 이라 일부러 비웠다).
      # 그래서 적용하는 순간 젠킨스가 학습해 뒀던 그 둘이 지워지고, 다음 호출이
      # `buildWithParameters` HTTP 400 으로 죽는다. 증상은 「FULL 이 갑자기 없는 파라미터가
      # 됐다」이고 원인과 전혀 안 닮았다.
      #
      # ★주석으로는 못 막는다는 것이 실증됐다 — 2026-09-09 에 이 함정을 XML 주석에 적어 두고도
      #   같은 날 **또 밟았다.** 그래서 사람이 기억할 일을 스크립트가 하게 옮겼다.
      #   `/build` 다. `buildWithParameters` 가 아니다 — 지워진 파라미터를 넘길 수 없다.
      CJ2="$(mktemp)"
      CRUMB2="$(curl -s -u "$AUTH" -c "$CJ2" http://127.0.0.1:18081/crumbIssuer/api/json \
        | sed -n 's/.*"crumb":"\([^"]*\)".*/\1/p')"
      RC="$(curl -s -o /dev/null -w '%{http_code}' -X POST -u "$AUTH" -b "$CJ2" \
        -H "Jenkins-Crumb: $CRUMB2" "http://127.0.0.1:18081/job/$JOB/build")"
      rm -f "$CJ2"
      if [ "$RC" != "201" ]; then
        echo "❌ $JOB 등록 빌드 트리거 실패 HTTP $RC" >&2
        exit 3
      fi
      echo "→ $JOB 등록 빌드 트리거 — 재등록을 확인한다"

      # ★★201 은 「큐에 들어갔다」일 뿐이다. **결과를 본다.**
      #
      #   2026-09-10 실측. 머지보다 이 `job` 적용을 먼저 돌려서 등록 빌드가
      #   `Jenkinsfile not found` 로 죽었다. 그런데 이 스크립트는 201 만 보고
      #   「✅ 적용 완료」라고 찍었다. 그 뒤로 잡은 **폴링도 파라미터도 없는 껍데기**였고,
      #   증상은 빨간불이 아니라 **침묵**이었다 — 아무 빌드도 안 걸리는 것.
      #   빨간불은 죽은 등록 빌드 하나뿐이고, 그것도 「예전 실패」로 읽힌다.
      #
      #   무엇을 보나. 두 파이프라인 모두 `parameters` 를 선언하므로, 젠킨스가
      #   Jenkinsfile 을 **파싱하는 데 성공했으면** config 에 그 속성이 다시 나타난다.
      #   트리거 HTTP 코드가 아니라 이 결과가 우리가 원하는 것이다.
      REGISTERED=0
      for _ in $(seq 1 60); do
        sleep 5
        if curl -sf -u "$AUTH" "http://127.0.0.1:18081/job/$JOB/config.xml" \
          | grep -q 'ParametersDefinitionProperty'; then
          REGISTERED=1; break
        fi
      done
      if [ "$REGISTERED" = "1" ]; then
        echo "→ $JOB parameters·triggers 재등록 확인 ✅"
      else
        echo "❌ $JOB 등록 빌드가 파이프라인을 파싱하지 못했다 (5분 대기 후에도 미등록)." >&2
        echo "   이 잡은 지금 폴링도 파라미터도 없는 상태다 — **조용히 아무것도 안 돈다.**" >&2
        echo "   처방. 대상 브랜치(*/${BRANCH})에 Jenkinsfile 이 있는지부터 확인하라." >&2
        echo "         머지보다 이 명령을 먼저 돌리면 정확히 이 상태가 된다." >&2
        exit 3
      fi
      APPLIED=$((APPLIED + 1))
    done
    echo "→ 잡 ${APPLIED}개 적용 완료"
    ;;
  *) echo "사용. $0 {up|down|logs|lock|job [브랜치]}" >&2; exit 1 ;;
esac
