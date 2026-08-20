# 배포 기반 구축 체크리스트

> 관련: plan.md · context-notes.md

## P0 조사 (완료)
- [x] AIG VM 정보 확보 (61.107.200.30 / testify / ~/.ssh/id_ed25519 / /home/testify/aig, AIG 13579 운영중)
- [x] BTS 배포 인프라 부재 확인 (Dockerfile·prod compose·배포스크립트·prod시크릿 없음)
- [x] 조립 앱 부재 확인 (@SpringBootApplication 2개, 자기 패키지만 스캔)
- [x] 모듈 의존 그래프 (모두 shared-kernel; issue→project-workflow)
- [x] Flyway V번호 충돌 분석 (identity↔issue만 충돌, 나머지 대역 분리)
- [x] prod 프로파일·env 키·redis 미사용 확인

## P1 조립 모듈 신설
- [x] `backend/modules/app` 모듈 + settings.gradle.kts 등록
- [x] `build.gradle.kts` — 8개 모듈 의존 + application 플러그인 + bootJar (+flyway/jdbc)
- [x] `com.bts.app.BtsApplication` (`scanBasePackages = com.bts, com.atlas.bts` + FQN 이름생성기)
- [x] 기존 2개 @SpringBootApplication 스캔 제외 필터 (+ SchedulingConfiguration REGEX 제외)
- [x] @EnableScheduling 단일화
- [x] **컴파일 성공** (8개 모듈 jar + jOOQ codegen 통과)
- [ ] 컨텍스트 로드 성공 → **prod 프로파일 필요**로 판명 (P2로 이관)

## P2 통합 Flyway/설정/보안/cross-BC
- [x] 모듈별 다중 Flyway 빈 (이력 테이블 분리) — FlywayAssemblyConfig + baselineVersion=0
- [x] 통합 application.yml + application-prod.yml
- [x] 로컬 RSA 테스트키 생성 + prod 프로파일 컨텍스트 로드 **성공**
- [x] cross-BC 결선 prod 승격 (prod 프로파일이 실제 resolver 활성화로 해소)
- [x] BouncyCastle "BC" 프로바이더 등록 (identity 잠복버그 보완)
- [x] 마이그레이션 전량 적용 (7개 모듈, 다중 이력 테이블)
- [x] ✅ **workflow PermissionResolver prod 어댑터** — `DelegatingPermissionResolver`(@Profile prod, com.bts.workflow.adapter) 신설. shared-kernel `IssuePermissionResolver`+`SystemPermissionResolver`에 위임(BC 격리 준수, prod는 identity가 채움). 권한문자열→IssuePermission 매핑 7종, 미등록은 fail-closed deny+WARN. TDD(test #313eebcc8→feat #64a7e7b62), 단위 8케이스 green. security-engineer.
- [ ] allow-bean-definition-overriding 오버라이드 로그 감사 (진짜 충돌 없는지)
- [ ] SecurityFilterChain @Order 정렬 확인 (security-engineer)
- [ ] BtsApplicationContextTest Testcontainers 전환 (현재 로컬 postgres 의존)

## P3 컨테이너화 ✅
- [x] 백엔드 Dockerfile (JRE21, 호스트빌드 fat jar) — 이미지 빌드 검증 843MB
- [x] 프론트 Dockerfile + nginx.conf (SPA + 백엔드 프록시, Docker DNS 지연해석) — 빌드+nginx -t 검증
- [x] `infra/docker-compose.prod.yml` (bts-net 격리·18080·digest 고정·mem_limit·minio 버킷 init) — config 검증 6서비스
- [x] `infra/prod/.env.prod.example` (시크릿 키 목록)
- [x] `infra/deploy/bts-deploy.sh` + config.sh.example 스캐폴드 (P5용)
- [x] app bootJar 이름 고정 + mainClass, 시크릿 gitignore

## P4 로컬 전체 스택 검증 ✅ 완료
- [x] ✅ **workflow PermissionResolver prod 어댑터** — `DelegatingPermissionResolver` 신설로 해소. **`BtsApplicationContextTest`(@ActiveProfiles prod)가 스텁 없이 8개 BC 전체를 실제 어댑터로 부팅 성공** — NoSuchBean 갭 실배선 확인.
- [x] ✅ **프론트 fresh 빌드** — node_modules 심볼릭이 삭제된 워크트리 가리켜 깨짐 → `CI=true pnpm install --prefer-offline`(네트워크 0)로 복구. dist 55 assets 재생성.
- [x] ✅ **배포 아티팩트 2종** — `bts-app.jar` 118MB(`:modules:app:bootJar`) + apps/web/dist. 이미지 `bts-backend:local` 843MB·`bts-web:local` 83MB 빌드.
- [x] ✅ **`docker compose up` 전체 6서비스 기동** — postgres·minio·minio-init·clamav·backend·web. **backend `Started BtsApplicationKt in 15s`**(Flyway·MinIO버킷·워크플로우시드·DelegatingPermissionResolver 배선). worktree `.worktrees/deploy-prod-foundation`에서(2차 hijack 격리).
- [x] ✅ **health/actuator UP + 프록시 검증** — backend `/actuator/health` 200 UP(env crutch 없이 application.yml 수정만) · web healthy · nginx SPA 서빙(`<title>BTS — Atlas</title>`) · nginx→백엔드 프록시(`/api/v1/whoami`→401 정상). **health 오탐 2건 소스 수정**(commit bdccebbde). (로그인→CRUD 브라우저 스모크는 시드 사용자 없어 미실시 — 후속.)

## P5 서버 배포 ✅ 완료 (2026-08-20)

**배포처가 바뀌었다.** AIG 공존 VM(61.107.200.30)이 아니라 **BTS 전용 네이버 클라우드 VM**
(101.79.19.193 · Rocky 8.8 · 2코어 16GB)에 올렸다. AIG 와 자원을 다투지 않으므로 원래 설계의
격리 장치(18080 포트 회피 등) 중 일부는 불필요해졌다.

- [x] ✅ `bts-deploy.sh` health 버그 수정 (nginx /actuator 미프록시 → docker exec 백엔드 직접 확인).
- [x] ✅ **VM 점검** — 구 서버 네트워크 블로커는 소멸(전용 VM 신설). 실측: Rocky 8.8 x86_64 · 2코어 · RAM 15.7GB(여유 15.2) · 디스크 99GB(여유 96) · SELinux Disabled · firewalld inactive(ACG 가 유일한 방화벽).
- [x] ✅ **서버 준비** — 스왑 8GB 신설(+`/etc/fstab` 등록, 스왑 0 이었다) · Docker 26.1.3 + Compose v2.27.0 설치 후 `systemctl enable`(재부팅 자동기동 — 부채 `58` 이 이 호스트엔 구조적으로 없다). Rocky 8.8 의 `$releasever` 가 `8.8` 로 풀려 Docker repo 404 → repo 파일을 `8` 로 고정해 해소.
- [x] ✅ **시크릿** — `/opt/bts/infra/prod/.env`(600) 22키 전량 · JWT RSA 키 생성. **암호화 키 4쌍(MFA·OIDC·Webhook·Automation)을 모두 채웠다** — 미설정이면 부팅·health 는 green 이고 기능 첫 호출에서 500 이 나므로 나중에 발견하는 것이 최악이다.
- [x] ✅ **포트/도메인/TLS 결정** — `bts.maxihan.com`(A 레코드가 이미 이 IP 를 가리킴) · **Caddy 를 앞단 TLS 종단으로 신설**해 Let's Encrypt 자동 발급·갱신. ACG 가 22/443/80/3000 만 열어 18080 은 애초에 닿지 않는다. `nginx.conf` 는 로그 마스킹 봉인이 걸려 있어 한 줄도 건드리지 않았다(종단 분리). `X-Forwarded-Proto` 만 앞단 값 우선으로 수정.
- [x] ✅ **배포 + 검증** — 6서비스 전부 healthy · 백엔드 `{"status":"UP"}` `RestartCount=0` · 외부 `https://bts.maxihan.com` **HTTP 200 · 인증서 검증 0** · `/api/v1/whoami` 401(프록시 정상 도달).

### 배포 중 실측한 결함 2건 (둘 다 로컬 검증이 못 잡던 층)

1. **rsync 프로토콜 불일치** — macOS 기본 `/usr/bin/rsync` 는 openrsync(protocol 29)라 서버 GNU rsync 3.1.3(protocol 31)과 협상이 깨진다. 에러(`unexpected end of file`)가 원인을 전혀 안 가리킨다. `brew install rsync`(3.4.4) 로 해소. 정본 [[macos-openrsync-breaks-deploy-with-opaque-error]].
2. **JWT 키 Permission denied** — 키를 `root:root 600` 으로 두면 비루트 컨테이너(`USER bts` uid 999)가 못 읽어 부팅이 재시작 루프에 빠진다. **P4 로컬 검증이 통과한 이유는 Docker Desktop 의 uid 매핑**이다 — 리눅스 실서버에서만 드러난다. 소유자를 999:999 · 400 으로 이관해 해소. 정본 [[docker-desktop-uid-mapping-hides-secret-permission-bug]].

### 미완 — 로그인 계정이 없다

`users` 0행이다. `SystemAdminBootstrapRunner` 는 *이미 있는* 사용자를 SYSTEM_ADMIN 으로 승격할 뿐
계정을 만들지 않고, `POST /api/v1/users` 는 `hasRole('SYSTEM_ADMIN')` 이라 최초 1명은 그 경로로
불가능하다. `infra/local/seed-admin.sql` 은 첫 줄에 **prod 금지**로 못 박혀 있다(비밀번호 `password`).
→ 강한 임시 비밀번호의 Argon2id(`m=65536,t=3,p=4`) 해시를 `must_change_password=true` 로 삽입하고
실제 로그인 API 로 검증하는 절차가 남았다. Maxi 결정으로 후속.
