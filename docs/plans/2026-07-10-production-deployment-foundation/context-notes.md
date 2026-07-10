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

## 다음 세션 진입점

- **BLOCKER**: FR-WF-03 workflow PermissionResolver 운영 어댑터 — Maxi 결정 대기(빌드 vs defer). 빌드 시 security-engineer.
- 그 후 P3(Docker/compose: fat jar·nginx·격리 compose·.env) → P4(로컬 docker compose up) → P5(서버, 별도 승인).
- 커밋: 1c22551ea(YAML), aebe4a4f8(조립 부팅). 브랜치 deploy/prod-foundation.
