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
pnpm --filter @bts/web build

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
