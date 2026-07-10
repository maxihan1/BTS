# 로컬에서 BTS 전체 스택 실행·테스트하기

> 개발한 기능을 로컬 Mac에서 실제로 로그인해 눌러 보는 방법. 조립 앱(8개 BC 전부 부팅)을 Docker로 띄운다.
> 작성 2026-07-10 (P4 완주 시점). 접속점·자격증명·MFA 등록·스택 제어·문제 해결을 한 곳에 정리.

## 한눈에

| 항목 | 값 |
|---|---|
| 접속 주소 | **`http://localhost:18080/login`** (루트 `/`는 아직 placeholder — 아래 주의 참고) |
| 로그인 | 아이디 `alice` / 비밀번호 `password` |
| 로그인 후 | 한 번 **MFA(2단계 인증) 등록** 필요 (alice = 최고 관리자라서) |
| 실행 방식 | Docker Compose 6서비스 (`infra/docker-compose.prod.yml`, prod 프로파일) |

> 참고. 조립 앱(`backend/modules/app`, `com.bts.app.BtsApplication`)은 **prod 프로파일에서만** 부팅된다. dev 모드는 `!prod` 스텁 빈이 서로 충돌(같은 포트에 AlwaysAllow·DevAllow 둘 다)해서 뜨지 않는다. 그래서 로컬 테스트도 prod 프로파일의 Docker 스택으로 한다.

## 사전 준비

- Docker Desktop 실행 중 (`docker ps` 가 동작).
- 최초 1회 필요 파일 (둘 다 gitignored — 커밋 금지).
  - `infra/prod/.env` — 비밀값·설정 (DB 비밀번호, JWT PEM 경로, 부트스트랩 admin, **MFA 암호화 키** 등).
  - `infra/secrets/bts-jwt.pem` — JWT 서명용 RSA 2048 개인키. 없으면 `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out infra/secrets/bts-jwt.pem` 로 생성.

## 스택 켜고 끄기

작업 디렉토리 = 이 worktree.

```bash
cd /Users/maxi.moff/Projects/BTS/.worktrees/deploy-prod-foundation

# 켜기 (백그라운드)
docker compose -f infra/docker-compose.prod.yml --env-file infra/prod/.env up -d

# 상태 확인 (6서비스 전부 healthy 목표)
docker compose -f infra/docker-compose.prod.yml --env-file infra/prod/.env ps

# 백엔드 로그 보기 (에러 추적)
docker logs -f bts-backend

# 끄기 (DB·MinIO 데이터 보존)
docker compose -f infra/docker-compose.prod.yml --env-file infra/prod/.env down

# 끄기 + 데이터 완전 삭제 (초기화하고 싶을 때만)
docker compose -f infra/docker-compose.prod.yml --env-file infra/prod/.env down -v
```

서비스 6종 — `bts-postgres`(DB) · `bts-minio`(파일 저장) · `bts-clamav`(첨부 바이러스 검사) · `bts-backend`(조립 앱) · `bts-web`(nginx = SPA + API 프록시, 유일한 공개 진입점 18080).

> `bts-clamav` 는 amd64 이미지라 Apple Silicon Mac에서는 에뮬레이션으로 뜬다 (경고만, healthy 도달까지 시간 좀 걸림).

> ⚠️ **루트 `/`는 개발용 placeholder("홈 (T13 가드 추가 전 placeholder)")를 보여준다.** 루트 접속 시 인증 여부에 따라 `/login`·시작페이지로 자동 이동시키는 가드("T13")가 아직 미구현이라서다. **테스트는 `http://localhost:18080/login` 으로 바로 접속**하면 된다. 이 루트 리다이렉트는 **배포 전 필수 처리 항목**(context-notes 참고) — prod에서 개발용 문자열이 노출되면 안 됨.

## 로그인 → MFA 등록 (최초 1회)

1. 브라우저에서 `http://localhost:18080/login` 접속.
2. 아이디 `alice`, 비밀번호 `password` 로 로그인.
3. alice는 최고 관리자(SYSTEM_ADMIN)라 **MFA 등록 화면**이 뜬다 (보안 설계 — 관리자는 2단계 인증 필수).
4. 휴대폰 **인증 앱**(Google Authenticator · 1Password · Authy 등)으로 화면의 **QR 코드**를 스캔.
5. 앱이 만든 **6자리 숫자**를 입력하면 등록 완료. 다음 로그인부터는 이 6자리만 추가로 넣으면 된다.

MFA 없이 테스트하고 싶으면 (관리자 전용 기능은 못 씀) 일반 사용자 계정을 시드하는 방법도 있다 — 필요하면 요청.

## 문제 해결

### 스택이 안 뜨거나 백엔드가 죽는다
```bash
docker logs bts-backend | tail -50   # 실제 에러부터 읽는다 (추측 금지)
```
- `env fail-fast` 로 즉시 종료 → `infra/prod/.env` 필수 변수 누락. 아래 "필수 env" 참고.
- Flyway 마이그레이션 실패 → 스키마 충돌. `down -v` 로 DB 초기화 후 재기동 검토.

### 로그인은 되는데 특정 기능에서 500
- **부팅·health는 초록인데 그 기능만 500** 이면 "사용 시점 검증 env" 누락을 의심. 대표 사례 = MFA 등록의 `BTS_MFA_ENCRYPTION_KEY`/`SALT`. 암호화 키·외부 연동 키는 실제로 그 기능을 눌러야 검증된다.

### API 응답이 이상하다 (프론트가 데이터를 못 읽음)
- 응답 첫 글자가 `{` (JSON)인지 확인. `-` 로 시작하면 YAML 회귀 (이미 수정됨, 커밋 99ad2d8). 재발 시 조립 앱에 `ObjectMapper` 빈이 새로 끼어들었는지 확인.

### web 컨테이너가 unhealthy
- healthcheck는 `127.0.0.1` 로 봐야 정상 (localhost → IPv6 `::1` 로 해석되면 nginx가 거부). `Dockerfile.web` 에 반영됨.

## 필수 env (`infra/prod/.env`) — 로컬·prod 공통

아래는 조립 앱이 실제로 동작하려면 채워져야 하는 핵심 변수. **커밋된 `.env.example` 템플릿은 아직 없다** (P5 후속 작업). VM 배포 시 이 목록 누락에 주의.

| 변수 | 역할 | 형식 주의 |
|---|---|---|
| `BTS_DB_NAME` / DB 비밀번호 | Postgres 접속 | |
| `BTS_JWT_PRIVATE_KEY_PATH` | JWT 서명 RSA 키 경로 | 컨테이너 내 `/secrets/bts-jwt.pem` |
| `BTS_AUTH_ISSUER_URI` | 토큰 발급자 URI | 로컬 `http://localhost:18080` |
| `BTS_BOOTSTRAP_ADMIN_USERNAME` | 최초 관리자 지정 | 예 `alice` |
| `BTS_MFA_ENCRYPTION_KEY` | MFA TOTP 비밀키 암호화 키 | 임의 문자열 |
| `BTS_MFA_ENCRYPTION_SALT` | 위 salt | **반드시 hex** — `openssl rand -hex 8` |
| `BTS_OIDC_ENCRYPTION_KEY` / `SALT` | OIDC client_secret 암호화 (SSO 쓸 때) | salt는 hex |

> 키/salt 생성 예. `openssl rand -hex 24` (키) · `openssl rand -hex 8` (salt). `.env` 는 절대 커밋하지 않는다.

## 관련 문서
- `checklist.md` — 배포 준비 단계별 체크리스트.
- `context-notes.md` — 진행 중 내린 결정·발견한 버그 상세 기록.
