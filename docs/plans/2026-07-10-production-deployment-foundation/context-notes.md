# 배포 기반 구축 — 컨텍스트 노트 (결정 + 근거)

> 작업 진행하며 계속 append. 다음 세션(사람/에이전트)이 재유도 없이 이어받도록.

## 2026-07-10 — 착수 배경

- Maxi 요청: "계획된 FR 90%+ 개발됨. VM 서버에 올리고 싶다. AIG에 VM 정보 있으니 확인해서 세팅."
- Maxi 결정(질문 응답):
  - **진행 범위** = "계획과 배포 기반 먼저 구축" (조립+Docker+compose, 로컬 부팅 검증까지. 실제 서버 배포는 별도 단계).
  - **대상 서버** = "같은 VM, 격리 배포" (61.107.200.30, AIG 옆에 Docker 격리).

## 핵심 발견 (왜 단순 복사가 아닌가)

1. **BTS는 지금 배포 불가** — FR 기능은 90%+지만 8개 BC를 하나로 조립하는 배포 앱이 없다. 90% 완성은 test-assembled 컨텍스트로만 검증됨(메모리 `no-cross-bc-deployment-assembly`). production-bootable 아티팩트가 존재하지 않음.
2. **AIG ≠ BTS 스택** — AIG는 Next.js/PM2/rsync. BTS는 Kotlin/Spring + React + Docker Compose. AIG 배포 스크립트 재사용 불가. VM 접속 정보(IP/SSH/유저)만 재사용.
3. **VM은 AIG 실서비스 운영중** (포트 13579) → 격리 배포 필수. 리소스 여유 미확인.

## 설계 결정 근거

- **Flyway 다중 이력 테이블 채택 이유**: identity(V001–033)↔issue(V001–035) 충돌은 오직 이 둘. 나머지는 대역 분리(200/400/500/600/700)라 안전. 재번호(단일 이력)는 기존 DB 이력/픽스처 전면 파손(blast radius 과대)이라 비채택. `baseline-on-migrate: true` 주석이 이미 "모놀리스 조립 대비"라 명시 → 다중 이력이 원 설계 의도와 정합.
  - ⚠️ 미검증 지점: identity+issue를 **한 Flyway 실행에 합친 적이 없음**(project-workflow 테스트는 issue만 testRuntimeOnly, identity 미포함). 따라서 V001 충돌은 실전에서 처음 부딪히는 것. 다중 이력으로 회피.
- **조립 모듈 스캔 제외 필터 필요 이유**: `scanBasePackages`로 전체 스캔 시 기존 2개 `@SpringBootApplication`(=`@SpringBootConfiguration`)이 스캔에 걸려 "multiple SpringBootConfiguration" 부팅 실패. 필터로 배제.
- **redis 제외**: 백엔드 prod 코드에서 redis/lettuce 미참조 확인. dev compose에도 없음. 인프라에서 뺌.

## 파악한 사실 (참조용)

- 모듈 의존: 전부 shared-kernel. issue-tracking → project-workflow(compile). project-workflow는 testRuntimeOnly로 issue-tracking(마이그레이션 classpath 목적).
- 인프라 의존: postgres(pgmq, `quay.io/tembo/pg16-pgmq`) · MinIO · ClamAV.
- 서버 포트(현행 개별): identity 8090(dev 8080), 나머지 라이브러리라 자체 포트 없음. 조립 후 단일 포트.
- prod env 키(identity 기준): BTS_DB_URL/USERNAME/PASSWORD · BTS_AUTH_ISSUER_URI · BTS_JWT_PRIVATE_KEY_PATH · BTS_LDAP_* · BTS_WEBAUTHN_* · BTS_BOOTSTRAP_ADMIN_USERNAME. + minio 키(issue/search 모듈), clamav host/port.
- 프론트: Vite build → apps/web/dist. nginx 서빙 대상.

## 2026-07-10 — P1 실행 로그 (조립 모듈 신설 + 부팅 디버깅)

브랜치 `deploy/prod-foundation`(origin/main=fd7e60005 기반, FR-CA-02 포함).

**완료.**
- `backend/modules/app` 조립 모듈 (settings.gradle.kts 등록). 8개 BC + spring-boot-starter-web/actuator/jdbc + flyway-core/database-postgresql. **컴파일 성공**.
- `com.bts.app.BtsApplication` — `@SpringBootApplication @EnableScheduling @ComponentScan(basePackages=[com.bts, com.atlas.bts], nameGenerator=FullyQualifiedAnnotationBeanNameGenerator, excludeFilters=[IdentityAccessApplication·IssueTrackingApplication·*.SchedulingConfiguration])`.
- `FlywayAssemblyConfig` — 모듈별 이력 테이블(`flyway_history_<bc>`) 순차 마이그레이션 (identity→issue→workflow→notification→agile→search→slack).
- 통합 `application.yml` (dev 기본값 + ${ENV} prod 주입, flyway.enabled=false, sql.init.mode=never, allow-bean-definition-overriding=true).
- identity 마이그레이션 서브디렉토리 정규화(커밋 fc8e75ac1) — FR-CA-02 V034 포함 34개 전량 이동, 루트 잔존 0.

**부팅 디버깅 순서 (BtsApplicationContextTest, dev postgres 5433).**
1. `Unresolved reference flywaydb` → app에 flyway 컴파일 의존 추가.
2. `ConflictingBeanDefinitionException schedulingConfiguration` (issue·notification·search) → REGEX 제외.
3. `ConflictingBeanDefinitionException jacksonNullableConfiguration` (issue·agile) → **FullyQualifiedAnnotationBeanNameGenerator**.
4. `BeanDefinitionOverrideException jsonNullableModule` (@Bean 메서드명, FQN 밖) → `allow-bean-definition-overriding=true`. ⚠️ 오버라이드 목록 로그 감사 필요(P2).
5. `NoUniqueBeanDefinitionException IssuePermissionResolver` — **핵심**.

**★ 핵심 발견 — 조립 앱은 prod 프로파일 산출물.**
- 권한 포트는 프로파일 배타 구현: `IdentityAccess*Resolver`(@Profile("prod"), 실제 DB기반) ↔ `DevAllow*`/`AlwaysAllow*`(@Profile("!prod"), 스텁).
- **프로파일 없이** 부팅→issue AlwaysAllow + identity DevAllow(둘 다 !prod) 동시활성 충돌. **prod 프로파일**→실제 resolver 하나만 활성, 깨끗이 해소(auth-bypass 위험 없음, 설계의도). prod 활성 빈 35개.
- prod 부팅 추가 필요: `BTS_AUTH_ISSUER_URI` + `BTS_JWT_PRIVATE_KEY_PATH`(RSA CRT PEM, PemFileKeyProvider fail-fast). 로컬 검증용 테스트 RSA키 생성 필요.

**⚠️ 사고/복구 — 멀티세션 브랜치 바꿔치기 (learnings 후보).**
- 다른 세션(FR-CA-02 iCal #250)이 작업 중 `git checkout deploy/prod-foundation→main`을 실행해 HEAD가 몰래 main으로 이동. 그 상태에서 내 identity-move 커밋이 **main에 잘못 안착**. `.bts-cache 멀티세션 충돌` 메모리가 경고한 케이스.
- 복구: (1) app 스캐폴드 커밋해 트리 정리 → (2) `git rebase --onto main 7a36fa07d deploy/prod-foundation`(docs 커밋 재배치) → (3) `git branch -f main origin/main`(main 원복). 모든 커밋 브랜치 도달 가능 유지, main 무오염 확인.
- **교훈**: 장시간 작업 중 주기적으로 `git rev-parse --abbrev-ref HEAD` 확인. 커밋 직후 브랜치 검증. 멀티세션 동시 작업 시 브랜치 전환 위험.

## 2026-07-10 — P2 완료: 조립 앱 prod 프로파일 부팅 성공 ✅

**BtsApplicationContextTest (@ActiveProfiles("prod")) BUILD SUCCESSFUL** — 8개 BC 가 하나의 Spring Boot 컨텍스트로 부팅됨(STOMP·Hikari 정상 기동 후 클린 종료). 단 workflow PermissionResolver 는 테스트 스텁(AssemblyGapStubConfig)으로 대체.

**prod 부팅 디버깅(실제 에러 로그 기반).**
1. NoUniqueBean IssuePermissionResolver → prod 프로파일로 해소.
2. NoSuchBean `com.bts.workflow.port.outbound.PermissionResolver` → **운영 어댑터 부재(FR-WF-03)**, 테스트 스텁 우회(★).
3. PemFileKeyProvider NoSuchProvider "BC" → BouncyCastle 미등록 잠복버그 → BtsApplication 등록.
4. Flyway ConnectException → dev postgres 볼륨 권한 깨짐 → 볼륨 재생성.
5. Flyway V004 "relation issues does not exist" → baselineVersion 기본1이 V001 스킵 → **baselineVersion=0**.
6. Flyway "non-empty schema no history" → baselineOnMigrate=true 유지.
7. YamlSeedService JsonParse '#' → @Primary JSON 매퍼 오주입 → **WorkflowSeedConfig + @Qualifier YAML 매퍼**(KDoc `/*` 중첩주석 컴파일실패→평문, [[ktlint-kdoc-brace-parse-failure]]).
8. 부팅 성공.

**★ 유일한 미해결 배포 블로커 — FR-WF-03 (보안 크리티컬).**
- `com.bts.workflow.port.outbound.PermissionResolver` 운영 어댑터(IdentityAccessPermissionResolver) 미구현. `AlwaysAllowPermissionResolver`(@Profile("!prod")) 스텁만 존재. prod 에서 없으면 워크플로우 전이 권한검사 결선 불가, 스텁 prod 활성화는 **auth bypass** — 금지.
- 배포 전 필수: identity-access 가 이 포트 구현(security-engineer + TDD). 타 권한 포트는 전부 IdentityAccess* prod 구현 존재 — 이 하나만 갭.

**부수(후속).** BC 프로바이더는 identity 잠복버그(등록 이관 검토) · allow-overriding 오버라이드 로그 감사 미실시 · ContextTest 로컬 postgres(5433) 의존(Testcontainers 전환 후속, 백엔드 CI 없어 무해).

## 2026-07-10 — P3 완료: 컨테이너화 (커밋 29b1baf39)

- Dockerfile.backend(JRE21+bts-app.jar, prod) 이미지 빌드 검증 843MB · Dockerfile.web+nginx.conf(SPA+프록시, Docker DNS 지연해석) 빌드+nginx -t 검증 · docker-compose.prod.yml(bts-net 격리·18080·digest 고정·mem_limit·minio 버킷 init) config 검증 6서비스 · .env.prod.example · deploy/bts-deploy.sh 스캐폴드.
- app bootJar=bts-app.jar(호스트 빌드, jOOQ codegen 이 Docker 요구해 이미지 내부 빌드 불가 → 산출물 COPY).
- **P4 두 블로커**: (1) FR-WF-03 — 운영 jar 는 테스트 스텁 없어 백엔드 부팅 불가. (2) 프론트 fresh 빌드 — pnpm deps 미설치(no-TTY `pnpm install` 실패, node_modules 부분설치)로 dist stale(2026-05-27). vite 직접호출도 MODULE_NOT_FOUND. → pnpm 환경 정비 필요(별개 이슈).

## 2026-07-10 — FR-WF-03 설계 리뷰 (구현 전, Maxi "먼저 설계만 검토" 지시)

**포트 계약** (`com.bts.workflow.port.outbound.PermissionResolver`).
- `hasPermission(actorId: ActorId, permission: String, scope: Scope): Boolean` — deny-by-false. raw String 권한 + Scope(Global/Project(key)/Issue(key)).
- 호출부: `PermissionValidator`(type="permission-check"). resolver false/예외 → 전이 차단(보안우선). Scope는 ValidatorScope.ISSUE(기본, Scope.Issue) / PROJECT(Scope.Project).
- `permission-check`는 `DefaultWorkflowValidatorFactory`에 **결선돼 있음**(line 60). config["permission"] 필수, config["scope"] 선택.

**★ 결정적 발견 1 — 시드 표준 워크플로우 4종은 permission-check 미사용.**
- simple/bug-tracking/software-default/kanban-basic YAML은 `RequiredField` validator만 사용. permission-check 0건.
- ∴ workflow `PermissionResolver` 빈은 **DI 생성 요건**(factory가 생성자 주입)일 뿐, 시드 구성의 런타임 핫패스가 아니다. 실제 호출은 **사용자가 custom 워크플로우 YAML에 permission-check를 선언할 때만** 발생(고급 미사용 기능).
- 배포 블로커의 본질 = (a) prod 빈 부재로 컨텍스트 부팅 실패(NoSuchBean), (b) custom-workflow-permission-check 기능의 정확성. 시드 워크플로우 동작엔 무영향.

**★ 결정적 발견 2 — identity에 재사용할 prod 패턴 3종 존재.**
- `IdentityAccessWorkflowSchemePermissionResolver`(@Profile prod): Global=`systemPermissionResolver.isSystemAdmin`, Project=`resolveKeyToId`→멤버게이트→`permissionSchemeRepo.roleHasPermission(projectId, role, code)`. **이 어댑터가 그대로 템플릿.**
- `IdentityAccessIssuePermissionResolver`(@Profile prod): issueKey→project 해석 방식 = `projectDirectory.resolveKeyToId(scope.key.substringBefore('-'))` (prefix 파싱). Scope.Issue 처리에 재사용.
- 매핑표(`toCodeOrNull`): BROWSE→BROWSE_PROJECT · VIEW→VIEW_ISSUE · CREATE→CREATE_ISSUE · UPDATE→**EDIT_ISSUE** · SOFT_DELETE→DELETE_ISSUE · SET_SECURITY→SET_ISSUE_SECURITY.

**★ 결정적 발견 3 — "전이(transition)" 권한은 identity 카탈로그에 코드가 없다.**
- 타입 enum `IssuePermission.TRANSITION` 조차 `toCodeOrNull → null`(HARD_DELETE와 함께 매트릭스 미위임). identity 권한코드 카탈로그 = BROWSE_PROJECT/VIEW_ISSUE/CREATE_ISSUE/EDIT_ISSUE/DELETE_ISSUE/SET_ISSUE_SECURITY/MANAGE_WORKFLOW/MANAGE_COMPONENTS/MANAGE_VERSIONS(+역할 MEMBER/PROJECT_ADMIN).
- ∴ 포트 KDoc 예시 문자열 "TRANSITION_ISSUE"는 **권威 DB 코드가 없다**. 전이 권한의 의미론(=EDIT_ISSUE로 볼지, 별도 코드 신설할지)은 **미결 제품 결정**. 그냥 복사 불가.

**설계 옵션(Maxi 판단 대기).**
- **옵션 A (완전 어댑터)**: 스킴 리졸버 복제. Global/Project/Issue 3분기 + String→코드 매핑표 + fail-closed. 단 전이 의미론 미결이라 제품결정 선행 필요. security-engineer + TDD. 범위 큼.
- **옵션 B (fail-closed prod 빈, 권장)**: Global→isSystemAdmin(실판정) · Project/Issue→멤버게이트+매트릭스, **권한 String이 identity 카탈로그 코드면 실검사·미등록 코드면 deny+WARN**. auth-bypass 0(deny-by-default). 시드 워크플로우 무영향이라 현 기능 완전. custom 워크플로우가 카탈로그 밖 문자열 쓰면 항상 거부(fail-closed=안전, 미사용 고급기능의 경계일 뿐). "완제품" 부합(스텁 아님, deny-by-default는 정식 보안 자세).
  - 계약 정의: "permission-check의 permission 문자열은 identity 권한코드여야 한다". 유효코드=실검사, 무효=거부+경고. 완결된 계약. 워크플로우 전용 어휘(TRANSITION_ISSUE 등) 도입은 제품이 정하면 후속.

## 2026-07-10 — 옵션 B 구현 완료: DelegatingPermissionResolver (P4 코드 블로커 해소) ✅

Maxi "옵션 B로 진행" 확정 → security-engineer + TDD.
- **신설** `com.bts.workflow.adapter.DelegatingPermissionResolver`(@Component @Profile("prod")) — 포트와 같은 BC라 격리 위반 0. shared-kernel `IssuePermissionResolver`+`SystemPermissionResolver`에 위임(prod는 identity가 채움). Global→isSystemAdmin, Project/Issue→String→IssuePermission 매핑 후 위임, 미등록 문자열→fail-closed deny+WARN(위임 미호출).
- **매핑 7종**: BROWSE_PROJECT→BROWSE · VIEW_ISSUE→VIEW · CREATE_ISSUE→CREATE · EDIT_ISSUE→UPDATE · TRANSITION_ISSUE→TRANSITION · DELETE_ISSUE→SOFT_DELETE · SET_ISSUE_SECURITY→SET_SECURITY. else→deny.
- **★ TRANSITION 현행 정책 상속**: identity가 TRANSITION(코드 null)을 "프로젝트 멤버면 통과"로 처리(매트릭스 미위임, FR-PM-04 이관 예정) → 위임이므로 현행 정책 그대로, 향후 전이 매트릭스 자동 반영.
- **TDD**: test #313eebcc8(RED, mockk 8케이스, ActorId는 value class라 실인스턴스만·목킹 금지) → feat #64a7e7b62 → chore #c290102a6(조립 스텁 `AssemblyGapStubConfig` 삭제 + `BtsApplicationContextTest` @Import 제거).
- **★ 조립 실배선 검증**: `:modules:app:test *BtsApplicationContextTest*` GREEN — @ActiveProfiles("prod")로 8개 BC가 **스텁 없이 실제 DelegatingPermissionResolver로 부팅**. NoSuchBean 갭 실해소 확인.
- 검증: project-workflow 단위테스트·detekt(--rerun-tasks)·ArchUnit 모두 green. ktlint는 앞선 커밋 1c22551ea의 WorkflowSeedConfig import 순서 위반 1건 잔존 → **#4fc54e6cf로 정정**(databind→dataformat, 내 세션 mess).
- 배치 근거: 포트 KDoc이 예고한 "IdentityAccessPermissionResolver"는 identity 거주 가정이었으나 BC 격리(ArchUnit identity→com.bts.workflow 금지)상 project-workflow에 두고 shared 포트 위임으로 확정. WorkflowSchemePermissionResolver 선례 동형.

## 2026-07-10 — P4 진행: 프론트 빌드 복구 + 산출물 + 컨테이너화 검증 + ★2차 hijack→worktree 격리

**프론트 fresh 빌드 블로커 해소.** 원인 = apps/web/node_modules 심볼릭이 **삭제된 워크트리(.worktrees/fr-ca-01-calendar)** store를 가리켜 vite 패키지 자체가 깨짐(.bin/vite 직접호출도 MODULE_NOT_FOUND). store(메인 .pnpm)엔 vite 존재. 해법 = `CI=true pnpm install --prefer-offline`(reused 795·downloaded 0, 네트워크 0, 52초)로 심볼릭 메인 store 재연결. 메모리 [[worktree-pnpm-verify-deps-symlink]] "★후속 발견" 그대로.

**★2차 멀티세션 hijack + worktree 격리(durable fix).** 프론트 빌드 후 확인하니 작업트리가 다른 세션에 의해 `main`으로 checkout돼 있었음(git status "On branch main", HEAD=3b3849d17 dashboard regen). 내 P3/FR-WF-03 커밋은 전부 `deploy/prod-foundation`(a0886cae6)에 온전, main 무오염 확인. 임시 checkout 재시도는 3차 충돌 위험 → **전용 worktree `.worktrees/deploy-prod-foundation` 생성**해 격리(BTS 관례·메모리 권장). backend/modules/app·infra/prod가 이 브랜치에만 있어 main 트리에선 애초에 P4 불가. 이후 모든 P4 작업은 이 worktree에서.
  - worktree node_modules = 메인에서 심볼릭(`ln -sfn`), 검증은 `node_modules/.bin/vite build` 직접호출([[worktree-pnpm-verify-deps-symlink]] point1·2). pnpm install 금지(메인 .modules.yaml 오염).

**배포 산출물 2종 생성 완료.**
- 백엔드: `./gradlew :modules:app:bootJar` → **bts-app.jar 118MB** BUILD SUCCESSFUL.
- 프론트: worktree에서 vite build → apps/web/dist(55 assets, index.html Jul10 15:48).

**컨테이너화 검증(진행 중).** Docker v29.1.2·compose v2.40.3 가동. `infra/prod/.env`(로컬 테스트값)+`infra/secrets/bts-jwt.pem`(RSA2048 PKCS#8, `openssl genpkey`) 생성 — **둘 다 gitignore 확인**. `compose config` 6서비스 파싱 OK. 이미지 빌드(bts-backend eclipse-temurin:21-jre + bts-web nginx:1.27-alpine) 실행 중.
  - ⚠️ 다음 관문: `docker compose up`. backend는 clamav `service_healthy` 의존 → clamav freshclam 시그니처 다운로드(네트워크·~120s+)가 관문. postgres/minio는 가벼움. RAM ~4.5GB(clamav 2g 최대).

## 2026-07-10 — P4 완주: docker compose 전체 스택 부팅 + 프로덕션 health 수정 2건

**전체 스택 6서비스 기동 성공** (worktree, `docker compose -f infra/docker-compose.prod.yml --env-file infra/prod/.env up -d`).
- postgres·minio·minio-init·clamav·backend·web 전부 기동. postgres/minio/clamav Healthy.
- ⚠️ clamav 이미지는 amd64 → arm64 Mac에서 에뮬레이션(경고만, Healthy 도달).
- **★백엔드 조립 앱 컨테이너 부팅 완주**: `Started BtsApplicationKt in 15.869s`. Flyway 마이그레이션(POSTGRES 16.8)·MinIO 버킷 자동생성(exports/imports/avatars)·표준 4워크플로우 시드(YamlSeedService, WorkflowSeedConfig YAML매퍼 컨테이너서도 작동, validators만·postActions 0)·DelegatingPermissionResolver 배선(없으면 NoSuchBean 부팅실패이므로 healthy=배선 증명).
- **nginx→백엔드 프록시 검증**: `/api/v1/whoami`→HTTP 401(백엔드 미인증 정상 거부). SPA 서빙 `<title>BTS — Atlas</title>`+assets(127.0.0.1).

**★프로덕션 health 수정 2건 (소스 영구 반영).**
1. **backend root health 503→UP**. 원인 = LDAP(`spring.ldap.urls` 기본 `ldap://localhost:389`)·mail(`bts-mailhog`) health indicator가 선택적 연동 부재로 DOWN→root DOWN. 오케스트레이터가 정상 컨테이너 재시작하는 실제 위험. **수정**: `application.yml` `management.health.ldap.enabled=false`·`mail.enabled=false`. env override(MANAGEMENT_HEALTH_*)로 먼저 검증(health UP 확인) 후 yml에 영구 반영·override 제거.
2. **web 컨테이너 unhealthy→healthy**. 원인 = healthcheck `wget http://localhost/`의 localhost가 컨테이너서 `::1`(IPv6) 우선 해석되나 nginx는 IPv4만 리슨(default.conf→bts.conf 리네임으로 nginx ipv6 자동리슨 스크립트 무력화)→refused. 실제 서빙은 127.0.0.1 정상. **수정**: `Dockerfile.web` healthcheck `localhost`→`127.0.0.1`.

**후속(다음 세션)**: 위 2수정 반영해 jar+이미지 재빌드→양쪽 native healthy 재확인 진행중. 그 후 P5(서버 배포, 별도 승인). 로컬 스택 정리 = `docker compose ... down`(볼륨 유지) 또는 `down -v`(볼륨 삭제).

## 2026-07-10 — 로컬 실사용 검증: MFA 등록 필수 env 누락(500) 발견·수정

**증상**: alice(SYSTEM_ADMIN) 로그인 후 MFA 등록 게이트 통과를 위해 `POST /api/v1/auth/mfa/totp/setup` 호출 시 **HTTP 500**. 로그 = `IllegalStateException: MFA encryption key not configured. Set BTS_MFA_ENCRYPTION_KEY/SALT`(`MfaSecretEncryptor.requireConfigured`).
- **근본 원인**: TOTP secret은 DB에 AES-256-GCM 암호화 저장(설계) → `BTS_MFA_ENCRYPTION_KEY`/`BTS_MFA_ENCRYPTION_SALT` 필수인데 `infra/prod/.env`에 누락. 빈은 부팅 안전상 항상 등록되고 **암호화 호출 시점에야** 검증 → 부팅/health로는 안 잡히고 실제 MFA 등록을 눌러야 표면화(YAML 회귀와 동일 계열 — 조립 앱 실사용 스모크로만 적발).
- **수정(로컬)**: `.env`에 `BTS_MFA_ENCRYPTION_KEY`(hex 48)·`BTS_MFA_ENCRYPTION_SALT`(**hex 16** — `Encryptors.stronger`가 hex 검증) 추가 후 backend 재시작. `/totp/setup` 재호출 → **200**(otpauth_uri + qr_png_data_uri 정상 발급) 확인. `.env`는 gitignored.
- **★P5 배포 영향**: prod `.env`에도 **동일 두 변수 필수**(salt는 반드시 hex). 현재 prod `.env`의 필수 변수를 문서화한 **커밋된 템플릿 부재**(`infra/deploy/config.sh.example`은 SSH 접속 설정 전용) → **P5 후속: `infra/prod/.env.example` 작성**(placeholder로 전 필수 변수 나열: DB·JWT PEM·부트스트랩 admin·issuer·MFA 암호화·OIDC 암호화 등). 안 만들면 VM 배포에서 같은 500 재현.

## 2026-07-10 — 배포 전 필수: 루트(/) 리다이렉트(T13) 미구현

로컬 실사용 중 발견 — `http://localhost:18080` 루트 접속 시 `apps/web/src/routes/index.tsx`의 개발용 placeholder(`홈 (T13 가드 추가 전 placeholder)`)가 그대로 노출. 원인 = 루트 인덱스 라우트에 인증 여부별 리다이렉트 가드("T13")가 미구현(`router.ts:65` 주석 "T13 라우트 가드에서 dashboard / login 으로 리다이렉트 예정"). 앱 자체는 정상 — `/login`·`/dashboard`·`/issues` 직접 접속하면 동작.
- **배포 전 필수 처리**: index 라우트에 `beforeLoad` 추가 — 미인증→`/login`, 인증→`resolveStartPageNav`(시작페이지). 기존 `routeGuard.ts` 헬퍼(`requireAuth`/`redirectIfAuth`/`resolveStartPageNav`) 재사용 가능. TDD 주의 — `router.test.tsx:67` "/ 라우트 마운트 → 인덱스 placeholder 렌더"(`findByText(/홈/)`)가 리다이렉트 기대로 바뀌어야 함. 수정 후 dist·web 이미지 재빌드 필요. 프론트 변경이라 frontend-engineer 경유 권장.
- Maxi 결정(2026-07-10): 지금은 `/login` 직접 접속으로 테스트, T13은 배포 전 처리로 미룸.

## 다음 세션 진입점

- ✅ **크리티컬 코드 블로커(workflow PermissionResolver prod 어댑터) 해소** — DelegatingPermissionResolver.
- **남은 유일 P4 블로커 = 프론트 fresh 빌드** — pnpm 환경 정비(정상 `pnpm install`). 현 dist 2026-05-27 stale.
- 그 후 P4(docker compose up 전체) → P5(서버: VM Docker·RAM 확인 → bts-deploy.sh).
- 커밋 흐름: 3b334c15f(계획)→fc8e75ac1(identity mig)→f75b22144(스캐폴드)→1c22551ea(YAML)→aebe4a4f8(조립부팅)→29b1baf39(P3)→313eebcc8(test)→64a7e7b62(feat FR-WF-03 어댑터)→c290102a6(스텁제거)→4fc54e6cf(ktlint fix). 브랜치 deploy/prod-foundation, main 무오염(=origin/main e5c81bdd28).
