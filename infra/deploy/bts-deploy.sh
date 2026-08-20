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
if [ ! -f "$SCRIPT_DIR/config.sh" ]; then
  echo "❌ $SCRIPT_DIR/config.sh 없음. config.sh.example 복사 후 값 기입."
  exit 1
fi
source "$SCRIPT_DIR/config.sh"
cd "$REPO_ROOT"

echo "🚀 BTS 배포 시작 (서버 ${SERVER})"

# 1. 배포 브랜치 확인
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
[ "$CURRENT_BRANCH" = "$DEPLOY_BRANCH" ] || { echo "⚠️ 현재 브랜치($CURRENT_BRANCH) != 배포 브랜치($DEPLOY_BRANCH). 중단."; exit 1; }

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
# ★★purge 를 승인해서(`CI=true`) 뚫으면 안 된다. 지워지는 실체를 워크트리의 심볼릭이
#   가리키고 있어 **옆 세션의 작업이 함께 깨진다.** 배포 하나를 통과시키려고 남의 작업을
#   부수는 거래다.
#
# 빌드 자체는 pnpm 을 필요로 하지 않는다 — apps/web 의 build 는 `vite build` 하나뿐이고
# 바이너리는 apps/web/node_modules/.bin 에 실재한다. 그래서 폴백이 성립한다.
if ! pnpm --filter @bts/web build; then
  echo "⚠️  pnpm 빌드 실패 — vite 직접 호출로 폴백 (워크트리 간섭 추정, node_modules 는 건드리지 않는다)"
  [ -x apps/web/node_modules/.bin/vite ] || { echo "❌ vite 바이너리도 없다 — 의존성 복구가 선행돼야 한다"; exit 1; }
  (cd apps/web && node_modules/.bin/vite build)
fi
[ -f apps/web/dist/index.html ] || { echo "❌ dist/index.html 부재 — 빌드가 산출물을 남기지 못했다"; exit 1; }

# 3. 서버로 전송 (빌드 산출물 포함, 소스/의존성 제외)
echo "📤 서버 업로드"
rsync -avz --delete \
  --include='backend/modules/app/build/' \
  --include='backend/modules/app/build/libs/' \
  --include='backend/modules/app/build/libs/bts-app.jar' \
  --exclude='backend/**/build/' \
  --exclude='**/node_modules' \
  --exclude='.git' \
  --exclude='.worktrees' \
  --exclude='infra/prod/.env' \
  --exclude='infra/secrets' \
  --exclude='*.log' \
  -e "ssh -i $SSH_KEY -o StrictHostKeyChecking=yes" \
  ./ "${SSH_USER}@${SERVER}:${REMOTE_DIR}/"

# 4. 원격 compose build + up + health
echo "🔄 원격 compose build + up"
ssh -i "$SSH_KEY" "${SSH_USER}@${SERVER}" bash -s <<REMOTE
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
sleep 10
# 호스트 포트로 확인하지 않는다. 앞단 bts-caddy 는 도메인(SNI)으로만 사이트를 매칭하므로
# localhost 요청은 사이트에 닿지 않는다 — 그 결과를 프론트 장애로 오독하게 된다.
# 각 컨테이너 내부에서 직접 묻고, 도메인 경유 https 는 배포 후 외부에서 확인한다.
docker exec bts-web wget -qO- http://127.0.0.1/ >/dev/null 2>&1 \
  && echo "✅ 프론트(nginx) 응답" \
  || echo "⚠️ 프론트 미응답 — 'docker logs bts-web' 확인"
# 백엔드 health 는 nginx 가 /actuator 를 프록시하지 않으므로(SPA fallback 가짜그린 방지)
# 백엔드 컨테이너 내부에서 직접 확인한다.
docker exec bts-backend curl -fsS http://localhost:8080/actuator/health >/dev/null \
  && echo "✅ 백엔드 health UP" \
  || echo "⚠️ 백엔드 health 대기 필요(기동 수십 초 소요) — 'docker compose ... ps'로 healthy 확인"
# 인증서는 첫 요청 때 발급된다(수십 초). 여기서 실패해도 배포 실패가 아니다.
docker logs bts-caddy 2>&1 | grep -qE "certificate obtained|certificate.*renew|serving initial configuration" \
  && echo "✅ Caddy 기동 (인증서 발급 로그 확인)" \
  || echo "ℹ️  Caddy 인증서 발급 진행 중일 수 있음 — 'docker logs bts-caddy' 확인"
REMOTE

echo "✅ 배포 명령 완료 (health 는 기동까지 수십 초 소요될 수 있음)"
