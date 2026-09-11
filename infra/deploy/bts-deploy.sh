#!/bin/bash
# BTS Docker 배포 스크립트 (스캐폴드) — 호스트에서 산출물 빌드 → 서버로 전송 → compose build+up
#
# ⚠️ P5(실제 배포) 단계에서 사용. 아직 서버 대상 실행 검증 전. 사전 확인 필수:
#    - VM 에 Docker + Compose 설치 여부
#    - VM RAM 여유 (BTS 스택 ~4.5GB + 기존 AIG). 부족 시 별도 VM 재검토
#    - infra/deploy/config.sh (config.sh.example 복사) + infra/prod/.env + infra/secrets/bts-jwt.pem
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# ★설정을 두 곳에서 찾는다 (2026-09-11).
#
#   `config.sh` 는 `.gitignore` 라 **저장소 체크아웃에 없다.** 그런데 젠킨스는 매 빌드
#   `cleanWs` 로 워크스페이스를 지우므로, 젠킨스 배포는 **구조적으로** 이 파일을 가질 수
#   없었다 — 배포 스크립트 12번째 줄에서 죽었고 REMOTE_DIR 은 정의조차 안 됐다.
#   (2026-09-11 사전 점검에서 적발. 배포 0회라 드러난 적이 없었다.)
#
#   순서. ① 스크립트 옆(사람이 자기 체크아웃에서 돌릴 때)
#         ② `BTS_DEPLOY_CONFIG`(젠킨스가 호스트 실물 경로를 준다)
DEPLOY_CONFIG=""
if [ -f "$SCRIPT_DIR/config.sh" ]; then
  DEPLOY_CONFIG="$SCRIPT_DIR/config.sh"
elif [ -n "${BTS_DEPLOY_CONFIG:-}" ] && [ -f "$BTS_DEPLOY_CONFIG" ]; then
  DEPLOY_CONFIG="$BTS_DEPLOY_CONFIG"
fi
if [ -z "$DEPLOY_CONFIG" ]; then
  echo "❌ 배포 설정을 못 찾았다."
  echo "   ① $SCRIPT_DIR/config.sh (config.sh.example 복사 후 값 기입)"
  echo "   ② 환경변수 BTS_DEPLOY_CONFIG 가 가리키는 파일"
  exit 1
fi
echo "⚙️  배포 설정 $DEPLOY_CONFIG"
source "$DEPLOY_CONFIG"
cd "$REPO_ROOT"

echo "🚀 BTS 배포 시작 (서버 ${SERVER})"

# 1. 배포 브랜치 확인
#
# ★★이름이 아니라 **커밋**을 본다 (2026-09-11 수정).
#
#   종전은 `git rev-parse --abbrev-ref HEAD` 하나였다. 그런데 젠킨스는 **detached HEAD**
#   로 체크아웃하므로 그 명령이 `HEAD` 를 돌려준다 — main 을 체크아웃했는데도
#   「HEAD != main」으로 무조건 중단이다. 배포가 0회인 이유 중 하나가 이것이다.
#   같은 함정을 `Jenkinsfile` 은 이미 고쳐 뒀는데(세 곳을 순서대로 본다) 이 스크립트는
#   안 고쳤다 — 두 자리가 서로를 검사하지 않았다.
#
#   물어야 할 것은 「브랜치 이름이 무엇인가」가 아니라 **「이 커밋이 배포 브랜치인가」**다.
HEAD_SHA=$(git rev-parse HEAD)
BRANCH_SHA=$(git rev-parse "refs/remotes/origin/${DEPLOY_BRANCH}" 2>/dev/null \
  || git rev-parse "refs/heads/${DEPLOY_BRANCH}" 2>/dev/null || true)
if [ -z "$BRANCH_SHA" ]; then
  echo "⚠️ 배포 브랜치 ${DEPLOY_BRANCH} 를 못 찾았다(로컬·원격 모두). 중단."
  exit 1
fi
if [ "$HEAD_SHA" != "$BRANCH_SHA" ]; then
  echo "⚠️ HEAD($(echo "$HEAD_SHA" | cut -c1-9)) != ${DEPLOY_BRANCH}($(echo "$BRANCH_SHA" | cut -c1-9)). 중단."
  echo "   배포는 ${DEPLOY_BRANCH} 의 **최신 커밋**에서만 한다."
  exit 1
fi
echo "🔖 배포 대상 커밋 $(echo "$HEAD_SHA" | cut -c1-9) (${DEPLOY_BRANCH})"

# ★GIT_* 네임스페이스를 지운다 — 아래 전량 검증이 판별식을 돌리기 전에.
#
# 왜. 지금 이 스크립트는 사람이 셸에서 부르므로 노출이 없다. 그러나 훅·`git bisect run`·
# `git rebase --exec` 로 불리는 순간 부모 git 이 GIT_DIR 를 물려주고, 판별식의 임시 저장소
# 픽스처가 그것을 상속해 **이 저장소**를 건드린다(2026-08-21 pre-push 에서 실제로 났다).
# 배포 직전에 저장소가 깨지는 것은 가장 비싼 자리라 한 줄짜리 심층 방어를 둔다.
# 브랜치 확인은 위에서 이미 끝났으므로 여기서 지워도 잃는 것이 없다.
#
# ★열거하지 않는다. `.husky/pre-push` 와 **같은 한 줄**이다 — 두 자리가 다른 형태를 쓰면
#   그 둘은 서로를 검사하지 않는 두 목록이 된다.
unset $(env | sed -n 's/^\(GIT_[A-Za-z0-9_]*\)=.*/\1/p')

# 의존성 실체 확인 — 셰임이 아니라 **모듈**을 본다.
#
# ★왜 `.bin/<도구>` 를 안 보나. pnpm 의 `.bin` 엔트리는 모듈을 부르는 **독립 셸 스크립트**라
#   모듈이 사라져도 그대로 남는다. `-x` 는 통과하고 바로 다음 줄이 `MODULE_NOT_FOUND` 로 죽어
#   원인이 배포가 아니라 **테스트 실패로 오독된다.**
#   2026-08-23 실측 — 선언 의존성 50개가 전부 부재인데 `.bin/vitest` 만 남아 `-x` 를 통과했고,
#   #395 배포가 백엔드 게이트(10m55s · 10,401 초록)를 지난 26초 뒤 여기서 끝났다.
#
# ★디렉터리 존재로는 못 가른다. 같은 실측에서 남아 있던 41개 항목이 전부 **빈 스코프
#   디렉터리**였다. 그래서 `package.json` 을 본다.
#
# ★두 벌로 적지 않는다. 부르는 자리가 둘(전량 검증 게이트 · 빌드 폴백)이라 복붙하면
#   한쪽만 고쳐지는 자리가 된다.
#
# ★정의는 아래 게이트 블록 **밖**이어야 한다. 메시지가 복구 명령으로 `pnpm` 을 언급하는데,
#   게이트 안으로 들어가면 「게이트가 pnpm 을 거치지 않는다」 판정이 엉뚱한 사유로 깨진다.
#
# 계약. scripts/workflow/push-backend-tests.test.ts §배포 전 전량 게이트
require_web_module() {
  if [ ! -e "apps/web/node_modules/$1/package.json" ]; then
    echo "❌ $1 모듈 부재 — 의존성 복구가 선행돼야 한다."
    echo "   ↳ apps/web/node_modules/.bin/$1 셰임이 남아 있어도 모듈이 없으면 여기서 죽는다."
    echo "   ↳ 복구. .worktrees/* 가 0개인지 먼저 보고 \`CI=true pnpm install --frozen-lockfile\`."
    echo "   ↳ 워크트리가 붙어 있으면 그 명령이 지우는 실체를 그쪽 심볼릭이 가리킨다. 먼저 정리할 것."
    exit 1
  fi
  # ★2차 확인 — 「모듈은 있는데 링크만 안 걸린」 상태를 잡는다. 이것이 없으면 그 트리가
  #   여기를 지나 다음 줄에서 exit 127 로 죽는다. `CLAUDE.md §함정` 이 「worktree
  #   node_modules 는 심볼릭 — `.bin` 부재부터 의심」으로 이름 붙인 반복 고장이 그것이다.
  #
  #   ★순서는 안전에 영향이 없다. 두 검사가 다 hard exit 이라 논리곱이고, 뒤집어도 둘 다
  #   막는다(2026-08-23 실측 — 두 순서를 부채 95 트리에 각각 물려 확인). 모듈을 먼저 두는
  #   이유는 **둘 다 없을 때 더 근본적인 원인을 먼저 말하기** 위해서다. 없는 위험을 주석이
  #   선언하지 않도록 이 문장을 사실로 적어 둔다.
  if [ ! -x "apps/web/node_modules/.bin/$1" ]; then
    echo "❌ $1 셰임 부재 — 모듈은 실재하는데 apps/web/node_modules/.bin/$1 이 없거나 실행 불가다."
    echo "   ↳ 스토어는 멀쩡하고 링크만 안 걸린 상태다. 복구 명령은 위와 같다."
    echo "   ↳ 워크트리를 붙였다 떼는 과정에서 나는 양식이다 — .worktrees/* 를 먼저 본다."
    exit 1
  fi
}

# 1.5. 전량 검증 — 프로덕션 직전의 **유일한 전수 게이트**
#
# 왜 여기인가. 2026-08-21 CI 자동 실행을 껐고, 푸시 훅은 **바뀐 모듈만** 돈다(약 2~20분).
# 그 훅은 `widenOnMigration:false` 로 부르므로, 마이그레이션이 **다른 BC 의 테이블**을
# 건드리는 경우처럼 모듈 그래프로 계산할 수 없는 영향을 원리적으로 못 본다.
# 그 사각을 되찾는 자리가 여기다 — 프로덕션에 올라가기 직전.
#
# ★`pnpm` 을 쓰지 않는다. 워크트리가 붙어 있으면 모듈 재설치를 시도하다 무-TTY 로 죽는다.
#   아래 프론트 빌드 단계가 같은 이유로 이미 폴백을 갖고 있다.
#
# ★건너뛰려면 `BTS_SKIP_DEPLOY_TEST=1`. 다만 그러면 이 배포는 **전수 검증 0회**다.
#
# 계약. scripts/workflow/push-backend-tests.test.ts §배포 전 전량 게이트
if [ "${BTS_SKIP_DEPLOY_TEST:-}" = "1" ]; then
  echo "⏭  BTS_SKIP_DEPLOY_TEST=1 — 전량 테스트 생략. 이 배포는 전수 검증 0회다."
else
  echo "🧪 전량 검증 — 판별식"
  node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'

  echo "🧪 전량 검증 — 백엔드 9 BC (Testcontainers 포함, 수십 분 소요)"
  if ! docker info >/dev/null 2>&1; then
    echo "❌ Docker 데몬이 응답하지 않는다 — Testcontainers 테스트가 전부 깨진다."
    echo "   이것은 코드 문제가 아니다. Docker 를 켜고 다시 실행할 것."
    exit 1
  fi
  (cd backend && ./gradlew test --console=plain)

  echo "🧪 전량 검증 — 프론트 (vitest)"
  require_web_module vitest
  (cd apps/web && node_modules/.bin/vitest run)
fi

# 2. 호스트에서 산출물 빌드 (jOOQ 코드생성이 Docker 를 요구하므로 호스트 빌드)
echo "🔨 백엔드 fat jar 빌드"
(cd backend && ./gradlew :modules:app:bootJar)
echo "🔨 프론트 dist 빌드"
# ★pnpm 을 통과하지 못하면 vite 를 직접 부른다.
#
# 왜. 워크트리(.worktrees/*)가 붙어 있는 동안 그 node_modules 는 메인을 가리키는 **심볼릭**이고,
# 그 상태에서 pnpm 은 모듈 디렉터리를 지우고 다시 깔아야 한다고 판단한다. 비대화형 실행에는
# TTY 가 없어 확인을 받지 못하고 `ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY` 로 죽는다
# (2026-08-21 실측 — 배포가 이 지점에서 exit 1).
#
# ★★purge 를 승인해서(`CI=true`) 뚫으면 안 된다 — **워크트리가 붙어 있는 동안은.**
#   지워지는 실체를 워크트리의 심볼릭이 가리키고 있어 **옆 세션의 작업이 함께 깨진다.**
#   배포 하나를 통과시키려고 남의 작업을 부수는 거래다.
#
#   ★조건을 적는 이유. `.worktrees/*` 가 0개면 그 근거가 성립하지 않는다. 2026-08-23 실측 —
#   워크트리 0개에서 `CI=true pnpm install --frozen-lockfile` 이 17초에 복구했다
#   (796개 전부 스토어 재사용 · 다운로드 0). 조건 없이 적으면 그 상황에서 복구를 막기만 하고,
#   위 `require_web_module` 의 부재 메시지가 안내하는 복구 절차와 정면으로 어긋난다.
#
# 빌드 자체는 pnpm 을 필요로 하지 않는다 — apps/web 의 build 는 `vite build` 하나뿐이고
# 바이너리는 apps/web/node_modules/.bin 에 실재한다. 그래서 폴백이 성립한다.
if ! pnpm --filter @bts/web build; then
  echo "⚠️  pnpm 빌드 실패 — vite 직접 호출로 폴백 (워크트리 간섭 추정, node_modules 는 건드리지 않는다)"
  require_web_module vite
  (cd apps/web && node_modules/.bin/vite build)
fi
[ -f apps/web/dist/index.html ] || { echo "❌ dist/index.html 부재 — 빌드가 산출물을 남기지 못했다"; exit 1; }

# ★★배포본이 **자기 커밋을 말하게** 한다. `dist/version.json`
#
# 왜 필요한가. 이것이 없으면 「지금 서버에 뭐가 떠 있나」를 물어볼 창구가 없다.
#   · 배포 후 E2E 가 「새 판이 반영됐나」를 확인할 수 없다 — `curl $BASE_URL` 은
#     **옛 버전도 200 을 준다.** 배포가 실패해 옛것이 그대로여도 전량 초록이 나온다
#     (2026-09-10 설계 검토에서 적발 — 검증 대상이 배포본인지 아무도 안 물었다).
#   · 사람도 서버에 들어가 `git log` 를 봐야 안다. 배포 이력과 실물이 갈려도 모른다.
#
# ★`/actuator/info` 를 쓰지 않는다. nginx 가 `/actuator` 를 프록시하지 않는다
#   (이 파일 아래 health 확인 주석 참고 — SPA fallback 가짜그린 방지). 정적 파일은
#   HTTPS 로 바로 닿으므로 젠킨스 밖에서도, 브라우저에서도 확인된다.
#
# ★`git rev-parse` 가 실패해도 배포를 막지 않는다. 커밋을 모르는 배포가 커밋을 아는 배포보다
#   낫지는 않지만, **배포 자체를 못 하게 만들 이유는 아니다.** 그때는 `unknown` 이 들어가고
#   E2E 가 「판정 불가」로 처리한다 — 조용히 초록이 되지 않는 것이 요점이다.
DEPLOY_SHA="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
printf '{"commit":"%s","builtAt":"%s"}\n' \
  "$DEPLOY_SHA" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > apps/web/dist/version.json
echo "🏷  version.json — commit=$DEPLOY_SHA"

# ── 전송 수단 — 어디서 실행하느냐만 다르다 ─────────────────────────────────────
#
# ★`BTS_DEPLOY_LOCAL=1` 은 **배포 대상 서버 위에서** 이 스크립트를 돌릴 때 쓴다(젠킨스).
#   그때 rsync 전송과 원격 SSH 는 자기 자신에게 하는 꼴이라 의미가 없다.
#
# ★바꾸는 것은 **전송 수단뿐이고 아래 본문은 한 벌 그대로**다. DB 덤프·compose·health 를
#   Jenkinsfile 에 옮겨 적으면 그 순간 두 벌이 되고, 한쪽만 고쳐지는 자리가 된다 —
#   이 저장소가 반복해 물린 결함 양식이다. 그래서 heredoc 을 복제하지 않고
#   실행기(`run_on_target`)만 갈아 끼운다.
if [ "${BTS_DEPLOY_LOCAL:-}" = "1" ]; then
  echo "🏠 로컬 모드 — 배포 대상 위에서 실행 중이다(전송·SSH 생략)"
  RSYNC_DEST="${REMOTE_DIR}/"
  RSYNC_TRANSPORT=()
  run_on_target() { bash -s; }
else
  RSYNC_DEST="${SSH_USER}@${SERVER}:${REMOTE_DIR}/"
  RSYNC_TRANSPORT=(-e "ssh -i $SSH_KEY -o StrictHostKeyChecking=yes")
  run_on_target() { ssh -i "$SSH_KEY" "${SSH_USER}@${SERVER}" bash -s; }
fi

# 3. 대상으로 전송 (빌드 산출물 포함, 소스/의존성 제외)
#
# ★★`--delete` 는 **저장소에 없는 대상 파일을 지운다.** 그 대상 디렉터리에는 저장소가
#   모르는 **운영 상태**가 같이 산다 — 그것이 제외 목록에 없으면 배포가 지운다.
#
#   2026-09-11 실측. 배포 0회 상태에서 대상에 실재하던 것들.
#     backups/                      588K · **유일한 DB 덤프**
#     infra/jenkins/.env            젠킨스 관리자 비밀번호
#     infra/jenkins/.deploy-key     GitHub 배포키 (개인키)
#     infra/prod/.env               (이미 제외돼 있었다)
#     infra/secrets                 (이미 제외돼 있었다)
#
#   앞의 셋은 제외 목록에 **없었다.** 첫 배포가 그것을 전부 지울 예정이었다 —
#   백업이 사라진 직후에 백업을 뜨는 순서라(4단계), 복구 수단이 먼저 증발한다.
#
# ★목록은 `protected-paths.txt` 하나가 정본이고, 읽는 것도 `read-protected-paths.sh`
#   한 벌이다. 여기 직접 적지 않는다 — 두 벌이 되면 한쪽만 고쳐지고, 그 순간 지워질 것이
#   조용히 늘어난다. 읽는 쪽이 둘이다(여기와 `infra/jenkins/bootstrap.sh` 의 chown).
#   계약. scripts/workflow/deploy-path-viability.test.ts
#         scripts/workflow/deploy-protected-paths-single-source.test.ts

# 저장소가 모르는 **운영 상태** — 배포가 절대 지우면 안 되는 것.
PROTECTED=()
while IFS= read -r p; do
  [ -n "$p" ] && PROTECTED+=("$p")
done < <("$SCRIPT_DIR/read-protected-paths.sh")
# ★프로세스 치환은 실패해도 while 을 멈추지 않는다 — 읽어낸 **개수**로 판정한다.
#   0건을 빈 제외 목록으로 받아 `--delete` 를 돌리면 백업과 자격증명이 지워진다.
[ "${#PROTECTED[@]}" -gt 0 ] || { echo "❌ 보호 경로를 0건 읽었다 — 빈 제외 목록으로 --delete 를 돌릴 수 없다" >&2; exit 1; }

# 저장소가 관리하는 것 중 전송에서 뺄 것 — 지워져도 되는 파생물.
DERIVED=(
  'backend/**/build/'
  '**/node_modules'
  '.git'
  '.worktrees'
  '*.log'
)

RSYNC_EXCLUDES=()
for p in "${PROTECTED[@]}" "${DERIVED[@]}"; do RSYNC_EXCLUDES+=(--exclude="$p"); done

# ★★쓸 수 있는지 **먼저 해본다.** 마운트됐다고 쓸 수 있는 것이 아니다.
#
#   2026-09-11 실측(빌드 #47). compose 가 `/opt/bts:/opt/bts` 를 마운트했지만 그 트리는
#   `501:20 drwxr-xr-x` 였고 컨테이너는 uid 1000(jenkins)로 돈다. rsync 가 **한 파일도**
#   못 쓰고 128MB 를 보낸 뒤 `exit 23` 으로 죽었다 — 5분과 로그 3천 줄을 쓰고 나서.
#
#   사전 점검이 이것을 놓친 이유가 중요하다. compose 를 **읽으면** 마운트가 보이고 그것으로
#   충분해 보인다. `touch` 를 **해봐야** 보인다. 그래서 이 검사는 텍스트가 아니라 실제 쓰기다.
#
#   처방의 정본은 `infra/jenkins/bootstrap.sh up` 의 소유권 교정이다. 여기는 그것이
#   안 돌았을 때 **싸게 실패**시키는 자리다.
if [ "${BTS_DEPLOY_LOCAL:-}" = "1" ]; then
  PROBE="${REMOTE_DIR}/.deploy-write-probe"
  if ! : > "$PROBE" 2>/dev/null; then
    echo "❌ ${REMOTE_DIR} 에 쓸 수 없다 (uid $(id -u), gid $(id -g))." >&2
    echo "   마운트는 됐는데 소유권이 안 맞는 상태다. rsync 를 돌려도 한 파일도 못 쓴다." >&2
    echo "   처방. 호스트에서 \`cd /opt/bts/infra/jenkins && ./bootstrap.sh up\` — 소유권을 교정한다." >&2
    exit 1
  fi
  rm -f "$PROBE"
  echo "✅ ${REMOTE_DIR} 쓰기 가능 (uid $(id -u))"
fi

echo "📤 산출물 반영 (보호 ${#PROTECTED[@]}건 · 파생 제외 ${#DERIVED[@]}건)"
rsync -avz --delete \
  --include='backend/modules/app/build/' \
  --include='backend/modules/app/build/libs/' \
  --include='backend/modules/app/build/libs/bts-app.jar' \
  "${RSYNC_EXCLUDES[@]}" \
  "${RSYNC_TRANSPORT[@]}" \
  ./ "$RSYNC_DEST"

# 4. 대상에서 compose build + up + health
echo "🔄 compose build + up"
run_on_target <<REMOTE
set -euo pipefail
cd ${REMOTE_DIR}

# ── 배포 전 DB 덤프 ──────────────────────────────────────────────────────────
# 왜 조건 없이 매번 뜨나. 「마이그레이션이 포함됐는가」를 판정하려면 배포마다 사람이 판단해야
# 하고, 판단이 끼면 「이번엔 없겠지」가 섞인다. Flyway 마이그레이션은 forward-only 라
# 되돌릴 SQL 이 없으므로, 판단이 한 번 틀리면 복구 수단 자체가 없다. 덤프는 몇 초이고
# 실패 시 잃는 것과 비교가 안 된다.
# ★덤프 실패는 배포 중단이다 — 백업 없이 스키마를 바꾸는 것이 이 단계가 막으려는 상태다.
if [ -n "\$(docker ps -q -f name=bts-postgres)" ]; then
  mkdir -p backups
  STAMP=\$(date +%Y%m%d-%H%M%S)
  docker exec bts-postgres pg_dump -U "\${BTS_DB_USERNAME:-bts}" -d "\${BTS_DB_NAME:-bts}" -Fc \
    > "backups/bts-\${STAMP}.dump"

  # ★ 아카이브가 열리는지 + 내용이 비지 않았는지까지 본다.
  #   크기만 보면 「빈 DB 를 성공적으로 덤프한」 상태가 그대로 통과한다 — 복구가 필요한
  #   순간에 못 쓰는 백업이 백업의 가장 흔한 실패 방식이다.
  DATA_COUNT=\$(docker exec -i bts-postgres pg_restore -l < "backups/bts-\${STAMP}.dump" \
    | grep -c "TABLE DATA" || true)
  [ "\$DATA_COUNT" -gt 0 ] || { echo "❌ 덤프에 TABLE DATA 가 0건 — 배포 중단"; exit 1; }

  # ★★ pgmq 큐는 이 덤프에 담기지 않는다.
  #   큐 테이블은 확장(pgmq) 소속이라 pg_dump 가 DDL·데이터를 통째로 건너뛴다
  #   (--extension=pgmq 도, -t 'pgmq.q_*' 도 효과 없음 — 2026-08-21 실측).
  #   그런데 Flyway 이력은 public 이라 데이터까지 담긴다. 그 덤프를 **새 DB 에 복원하면**
  #   Flyway 가 큐 생성 마이그레이션을 「적용됨」으로 보고 재실행하지 않아,
  #   큐가 없는데 이력만 완료인 상태가 된다 → pgmq.send 가 전부 실패하고
  #   이슈 생성·전환·웹훅·자동화·Slack 이 동시에 죽는다.
  #   그래서 큐 목록을 덤프 옆에 남긴다. 복원 절차. docs/runbooks/disaster-recovery.md
  docker exec bts-postgres psql -U "\${BTS_DB_USERNAME:-bts}" -d "\${BTS_DB_NAME:-bts}" -tAc \
    "select queue_name from pgmq.list_queues() order by 1" \
    > "backups/bts-\${STAMP}.pgmq-queues.txt"

  echo "💾 DB 덤프 backups/bts-\${STAMP}.dump (\$(du -h "backups/bts-\${STAMP}.dump" | cut -f1) · TABLE DATA \${DATA_COUNT}건)"
  echo "💾 pgmq 큐 목록 backups/bts-\${STAMP}.pgmq-queues.txt (\$(wc -l < "backups/bts-\${STAMP}.pgmq-queues.txt" | tr -d ' ')개 — 덤프에 안 담기므로 별도 보관)"

  # 최근 10개만 남긴다 — 무한 증가로 디스크를 채우면 그것이 다음 장애가 된다.
  ls -1t backups/bts-*.dump 2>/dev/null | tail -n +11 | xargs -r rm -f
  ls -1t backups/bts-*.pgmq-queues.txt 2>/dev/null | tail -n +11 | xargs -r rm -f
else
  echo "ℹ️  bts-postgres 미기동 — 최초 배포로 보고 덤프를 건너뛴다"
fi

docker compose -f infra/docker-compose.prod.yml --env-file infra/prod/.env build
docker compose -f infra/docker-compose.prod.yml --env-file infra/prod/.env up -d
# ★★기동을 **기다리고**, 안 뜨면 **죽는다** (2026-09-11 수정).
#
#   종전은 `sleep 10` 뒤 `cmd && echo ✅ || echo ⚠️` 였다. 두 가지가 문제였다.
#     ① 10초는 스프링 부팅보다 짧다 — 정상 배포도 ⚠️ 가 뜬다
#     ② `|| echo` 는 **종료 코드를 0 으로 만든다** — 스택이 죽어도 배포가 초록이다
#
#   ②가 치명적이다. 마이그레이션이 실패하든 컨테이너가 크래시 루프를 돌든
#   `✅ 배포 명령 완료` 가 찍힌다. **롤백을 시작할 신호 자체가 안 뜬다** —
#   이 저장소가 이름 붙인 「실패가 아니라 침묵」 그대로다.
#
#   ★Caddy 인증서만 비-치명으로 남긴다. 첫 요청 때 발급되므로 여기서 없는 것이 정상이다.
# ★\$ 를 전부 이스케이프한다. 이 블록은 `run_on_target <<REMOTE` 안이고 delimiter 에
#   따옴표가 없어, 안 하면 **로컬 셸이 먼저 풀어** 대상에 빈 값이 간다.
#   같은 이유로 기존 코드도 `STAMP=\$(date ...)` 처럼 적혀 있다.
wait_for() {
  local what="\$1" probe="\$2"
  local deadline=\$(( SECONDS + 180 ))
  while [ "\$SECONDS" -lt "\$deadline" ]; do
    if eval "\$probe" > /dev/null 2>&1; then
      echo "✅ \${what} 확인 (\${SECONDS}초)"
      return 0
    fi
    sleep 5
  done
  echo "❌ \${what} 가 180초 안에 안 떴다 — 배포 실패로 판정한다." >&2
  return 1
}

# 호스트 포트로 확인하지 않는다. 앞단 bts-caddy 는 도메인(SNI)으로만 사이트를 매칭하므로
# localhost 요청은 사이트에 닿지 않는다 — 그 결과를 프론트 장애로 오독하게 된다.
wait_for "프론트(nginx) 응답" 'docker exec bts-web wget -qO- http://127.0.0.1/'
# 백엔드 health 는 nginx 가 /actuator 를 프록시하지 않으므로(SPA fallback 가짜그린 방지)
# 백엔드 컨테이너 내부에서 직접 확인한다.
wait_for "백엔드 health UP" 'docker exec bts-backend curl -fsS http://localhost:8080/actuator/health'
# 인증서는 첫 요청 때 발급된다(수십 초). 여기서 실패해도 배포 실패가 아니다.
docker logs bts-caddy 2>&1 | grep -qE "certificate obtained|certificate.*renew|serving initial configuration" \
  && echo "✅ Caddy 기동 (인증서 발급 로그 확인)" \
  || echo "ℹ️  Caddy 인증서 발급 진행 중일 수 있음 — 'docker logs bts-caddy' 확인"
REMOTE

echo "✅ 배포 명령 완료 (health 는 기동까지 수십 초 소요될 수 있음)"
