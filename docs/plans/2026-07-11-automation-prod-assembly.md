# automation 모듈을 :modules:app prod 조립에 추가하고 조립 앱 부팅 검증

> slug: automation-prod-assembly
> type: feature
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-11

## Brief

사용자 원문: "automation 모듈을 :modules:app prod 조립에 추가하고 조립 앱 부팅 검증"

FR-AT-02(#256, 2026-07-11 머지)의 **C4 후속**. automation BC(FR-AT-01 트리거 + FR-AT-02 액션)가 prod 조립 앱 `:modules:app`(#253)에 미배선 상태. app `build.gradle.kts`가 8개 BC 모듈만 의존하고 `:modules:automation`을 빠뜨려, automation의 pgmq consumer·@Scheduled 워커·cross-BC `IssueMutationPort` 결선이 prod에서 미가동.

**핵심 발견 (context-restore 체크포인트 + history #256)**: FR-AT-02 worktree가 #253 이전 base라서 C4 ADR이 "전역 조립 모듈은 타 BC 미구현 어댑터로 부팅불가"로 판단·이연했으나, `:modules:app`은 이미 8모듈·prod 프로파일로 존재·부팅. 따라서 이 작업은 "모듈 신설"이 아니라 **"기존 app에 automation 의존 추가 + 부팅 검증"**.

**리스크 (검증 대상)**: automation이 prod 컨텍스트에서 처음 결선될 때 미구현 어댑터/미설정 env를 요구하는가. 관련 함정 — [[no-cross-bc-deployment-assembly]] · [[profile-scoped-bean-boot-failure]] · [[minio-eager-bean-fullboot-regression]] · [[new-crossbc-dep-openapi-mockbean-regression]] · [[module-first-scheduled-worker-detektmain-traps]] · [[use-time-validated-env-passes-boot-fails-on-use]].

classify: type=feature, agent=backend-engineer, primary_bc=automation

## 도메인 정리

- **BC**: automation (배선 대상). 단, 실제 편집 파일은 배포 조립 모듈 `:modules:app`(cross-cutting, BC 아님) + 검증. automation 소스 코드는 불변.
- **새 용어**: 없음 (유비쿼터스 언어 변경 없음, 순수 배선/검증)
- **기존 결정 충돌**: FR-AT-02 C4 ADR(`docs/decisions/2026-07-11-fr-at-02-automation-actions.md`)의 "전역 조립 모듈은 타 BC 미구현 어댑터로 부팅불가라 후속 이연" framing이 **stale**(그 worktree가 #253 이전 base). 실제로 `:modules:app`(#253)이 8모듈·prod 프로파일로 이미 존재·부팅. → 본 작업이 C4를 해소하고 ADR framing을 정정.

### 실측 결과 (부팅 계약)

automation이 prod 컨텍스트에서 소비하는 cross-BC 포트 3개 — **전부 조립 앱에 있는 모듈이 prod 구현 제공**(부팅 실패 위험 없음):

| 포트(shared-kernel) | prod 구현 | 소속 모듈(조립 포함) |
|---|---|---|
| `AutomationPermissionResolver` | `IdentityAccessAutomationPermissionResolver` `@Profile("prod")` | identity-access ✓ |
| `IssueMutationPort` | `AutomationIssueMutationAdapter` `@Profile("prod")` | issue-tracking ✓ |
| `OutboundUrlValidator`/`UrlCheck` | shared-kernel 빈 | shared-kernel ✓ |

- automation 자체 `@Profile` 빈 **없음**, startup DB 접근(`@PostConstruct`/`InitializingBean`/`ApplicationReadyEvent`) **없음** → `[[profile-scoped-bean-boot-failure]]`·`[[minio-eager-bean-fullboot-regression]]` 함정 비해당.
- automation 워커 3종(`AutomationEventWorker`·`AutomationExecutionWorker`·`AutomationScheduleWorker`)은 순수 `@Component`. `BtsApplication`의 **전역 `@EnableScheduling`**이 이들을 구동(모듈 opt-in property `bts.automation.scheduling.enabled` 불요) — **notification `SchedulingConfiguration` 동형**(전역 위임). `AutomationSchedulingConfig`(@ConditionalOnProperty 기본 OFF)는 조립 컨텍스트에서 inert.
- automation 마이그레이션: `db/migration/automation/V300~V303`(별도 이력 밴드). `FlywayAssemblyConfig` 모듈 목록에 **누락됨** → 반드시 추가.

### 필요 변경 (3파일 + 검증 + ADR)

1. `backend/modules/app/build.gradle.kts`: `implementation(project(":modules:automation"))` 추가 (+ "8개 BC"→"9개" 주석 2곳: L1, L41)
2. `backend/modules/app/src/main/kotlin/com/bts/app/FlywayAssemblyConfig.kt`: 모듈 목록에 `"automation" to "classpath:db/migration/automation"` 추가 (+ "8개 BC" KDoc 정정, pgmq 큐 생성 순서 확인)
3. `backend/modules/app/src/test/kotlin/com/bts/app/BtsApplicationContextTest.kt`: "8개 BC"→"9개" 주석 + **automation 빈 존재 단언 추가**(예: `AutomationExecutionWorker` 주입 확인 — vacuous contextLoads 보강, `[[archunit-vacuous-rule-silent-pass]]`)
4. `settings.gradle.kts`에 `:modules:automation` 등록 여부 확인(이미 있을 것 — 독립 빌드 중)
5. ADR: FR-AT-02 C4 절 framing 정정 + 본 작업 신규 ADR(`2026-07-11-automation-prod-assembly`)

### 결정 대상 (게이트 1 제시)

- **D1. 스케줄링 활성 방식** — (A, 권장) 전역 `@EnableScheduling` 위임(notification 동형), property 미설정, `AutomationSchedulingConfig` inert + KDoc 정정 / (B) `application-prod.yml`에 `bts.automation.scheduling.enabled=true` 명시(전역이 이미 구동하므로 기능상 redundant, 의도 문서화 목적).
- **D2. 부팅 검증 강도** — (A, 권장) `contextLoads` + automation 빈 존재 단언 / (B) contextLoads만.
- **D3. 웹훅 인바운드 permitAll (리뷰 C2)** — `AutomationWebhookController`(`/api/v1/automation/webhooks/{token}`)가 조립되나 중앙 `SecurityConfig` 화이트리스트 미포함 → prod 401(FR-AT-01 WEBHOOK 트리거 사문화). slack `/slack/events`도 동일 미등록·후속 추적 중. (A) 이 PR에서 identity-access `SecurityConfig`에 automation 웹훅 permitAll+CSRF-ignore 등록(cross-BC·보안 민감·security-engineer 검토 필요, WEBHOOK 트리거 즉시 prod 가동) / (B, 권장) slack 선례대로 명시적 scope-out + 후속 추적(본 PR=조립 배선+부팅 검증 스코프 유지, slack+automation 인바운드 permitAll 통합 후속이 자연스러운 배치). 어느 쪽이든 Brief의 "미가동 3종"(consumer·워커·IssueMutationPort)은 본 PR로 해소됨.

- **관련 ADR**: [FR-AT-02](../decisions/2026-07-11-fr-at-02-automation-actions.md)(C4 정정 대상) + 신규 `2026-07-11-automation-prod-assembly` 후보

## 스펙

전체 스펙: [docs/specs/2026-07-11-automation-prod-assembly.md](../specs/2026-07-11-automation-prod-assembly.md)

핵심 요구사항 요약.
- R1 build.gradle `:modules:automation` 의존 · R2 FlywayAssemblyConfig automation 마이그레이션 · R3 부팅 테스트 automation 빈 단언
- R4 "8개 BC"→"9개" 표기 전수 정정(4파일) · R5 스케줄링=전역 위임(inert config) · R6 ADR 정정+신규
- 완료 기준: dev postgres 기동 후 `:modules:app:test` green + Flyway automation 4건 로그 + verify-master-plan 123/123

## Brainstorming Check

✅ 통과 (self sanity, 1회). Maxi 결정 필요 gap 없음.
- 부팅 계약 안전(cross-BC 포트 3개 prod 구현 존재).
- 실측 항목 plan 이관: E1 pgmq 확장 순서 · E3 cross-BC FK · E5 dev postgres 인프라 · settings.gradle 등록 · prod 필수설정 유무.
- 설계 갈림길 D1(스케줄링 방식)·D2(검증 강도)는 게이트 1 제시.

## Plan

### 사전 실측 완료 (Task 0 — 조사만, 코드 변경 없음)

- ✅ `backend/settings.gradle.kts`: automation(L13)·app(L17) 등록됨. **L16 주석이 stale**("automation의 app 조립 포함은 후속 — 현재 8개 BC") → R4 정정 대상 추가.
- ✅ pgmq(E1): V301 `CREATE EXTENSION IF NOT EXISTS pgmq CASCADE` + `pgmq.create` 자체완결·멱등 → FlywayAssemblyConfig 순서 무관.
- ✅ cross-BC FK(E3): automation 테이블 FK는 `automation_actions→automation_rules` 내부뿐 → 순서 무관.
- ✅ q_automation_events: issue-tracking `V036`이 생성(조립 첫 순서 실행) → `AutomationEventWorker` 소비 대상 실재.
- ✅ @Bean 충돌(E4): automation `@Bean` 메서드 0개(유일 @Configuration=AutomationSchedulingConfig, @Bean 없음) → 이름 충돌 위험 없음.
- ✅ app yml: automation 필수 설정 0(워커 poll-interval 인라인 기본값).
- ✅ 부팅 계약: cross-BC 포트 3개(AutomationPermissionResolver·IssueMutationPort·OutboundUrlValidator) prod 구현 전부 조립 실재.

> **검증 전제**: `BtsApplicationContextTest`는 Testcontainers 아닌 **외부 dev postgres(5433, pgmq 포함)** 필요. impl은 `docker compose -f infra/docker-compose.dev.yml up -d postgres` 선행(`[[no-backend-ci-and-assembly-merge-verification-traps]]`).

### Task 1. automation 조립 배선 + prod 부팅 빈 단언 (TDD)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/app/src/test/kotlin/com/bts/app/BtsApplicationContextTest.kt`, `backend/modules/app/build.gradle.kts`, `backend/modules/app/src/main/kotlin/com/bts/app/FlywayAssemblyConfig.kt`, `backend/modules/app/src/main/kotlin/com/bts/app/BtsApplication.kt`, `backend/modules/app/src/main/resources/application.yml`]
- depends-on: []

**RED**:
- 파일: `BtsApplicationContextTest.kt`
- `@Autowired ApplicationContext` 주입 후 automation 빈 존재 단언 추가. FQN 빈 이름(FullyQualifiedAnnotationBeanNameGenerator 규약)으로 문자열 조회 → 컴파일 커플링 회피, 런타임 RED.
  ```kotlin
  @Test fun `automation 워커 빈이 조립 컨텍스트에 결선된다`() {
      // FullyQualifiedAnnotationBeanNameGenerator → 빈 이름 = FQN 클래스명
      assertThat(context.containsBean("com.bts.automation.worker.AutomationExecutionWorker")).isTrue()
  }
  ```
- 실패 (예상): automation 미스캔(build 의존·Flyway 미배선) → 빈 부재로 단언 실패. (dev postgres 기동 상태에서 실행)
- 커밋: `test:` (TDD 순서 강제 — feat 앞)

**GREEN**:
- `build.gradle.kts`: 8개 BC 의존 블록에 `implementation(project(":modules:automation"))` 추가(9번째).
- `FlywayAssemblyConfig.kt`: `modules` 리스트에 `"automation" to "classpath:db/migration/automation"` 추가(마지막 append — 순서 무관 확인됨).
- 커밋: `feat:`
- 결과: `@ComponentScan("com.bts")`가 automation 스캔 → 워커/서비스 빈 결선 + Flyway automation 4건 적용 → 단언 통과.

**REFACTOR** (app 모듈 내 표기 정정, 동작 무관 — 리뷰 C1/N3 반영):
- `build.gradle.kts` L1·L41 주석 "8개 BC"→"9개 BC".
- `FlywayAssemblyConfig.kt` KDoc L13 "8개 BC"→"9개 BC".
- `BtsApplication.kt` KDoc **L1만** "8개 BC"→"9개 BC" (L21엔 카운트 표기 없음).
- `BtsApplicationContextTest.kt` L1 주석 "8개 BC"→"9개 BC".
- `application.yml` L1 주석 "8개 BC"→"9개 BC".
- 커밋: `refactor:` 또는 `docs:`

**검증**: `docker compose -f infra/docker-compose.dev.yml up -d postgres` 후 `./gradlew :modules:app:test`. 로그에 `Flyway[automation] 마이그레이션 적용 4건` 확인. **RED 커밋 시 실패 사유가 DB 연결 실패가 아닌 빈 부재(단언 실패)임을 확인**(N2 — dev postgres 기동 상태에서 red 촬영). app 모듈 ktlint/detekt.

### Task 2. 표기 전수 동기화 + ADR (docs, 비-TDD)

**메타**.
- agent: `backend-engineer`
- files: [`backend/settings.gradle.kts`, `infra/docker-compose.prod.yml`, `backend/modules/automation/src/main/kotlin/com/bts/automation/AutomationSchedulingConfig.kt`, `scripts/verify-master-plan.sh`, `docs/decisions/2026-07-11-fr-at-02-automation-actions.md`, `docs/decisions/2026-07-11-automation-prod-assembly.md`]
- depends-on: [1]

**작업** (RED/GREEN 없음 — 문서·주석·가드, 동작 무관):
- `settings.gradle.kts` L16 주석 정정: "automation app 조립 포함 완료 — app 은 9개 BC 조립".
- `infra/docker-compose.prod.yml` L93 주석 "8개 BC"→"9개 BC" (C1).
- `AutomationSchedulingConfig.kt` KDoc(R5) 정정: 조립 컨텍스트에선 전역 `@EnableScheduling`이 워커를 구동하므로 이 property 없이 폴링 활성(notification 동형). 코드/어노테이션 불변, KDoc만.
- `scripts/verify-master-plan.sh` 확장(C1): 조립 "N개 BC" 표기 카운트 가드 룰 추가 — build.gradle 의존 개수와 주석 카운트 정합 검사(일부러 위반 넣어 fail 확인 후 원복).
- FR-AT-02 ADR C4 절 정정: "전역 조립 모듈 신설은 후속" → "기존 `:modules:app`(#253)에 automation 추가로 해소(본 작업)". stale framing 명시.
- 신규 ADR `docs/decisions/2026-07-11-automation-prod-assembly.md`: 결정(조립 배선 방식·전역 스케줄링 위임·부팅 검증 강도·순서 무관 근거·D3 웹훅 permitAll 결정). Obsidian 미러는 머지 후 sync-obsidian(자동).

**검증**: `bash scripts/verify-master-plan.sh` 123/123(확장 룰 포함). automation 모듈 KDoc 변경분 ktlint(`[[ktlint-kdoc-brace-parse-failure]]` — 중괄호/백틱 평문화). `./gradlew :modules:automation:compileKotlin`(KDoc 변경 컴파일 무해 확인).

## Plan 메타

- task 수: 2 (Task 1 TDD 사이클 + Task 2 docs 동기화)
- wave: 1 wave 불가 — 단일 `:modules:app`/automation 모듈 컴파일 직렬 + depends-on. 사실상 순차 실행([[bts-plan-wave-gradle-module-compile]]).
- 예상 시간: 약 15분(dev postgres 기동·조립 컨텍스트 로드 포함)
- TDD 강제: Task 1 yes(test:→feat: 순서), Task 2 docs 면제
- 추가 검증: verify-master-plan, ktlint/detekt(app+automation), 조립 부팅 로그 Flyway automation 4건

## 리뷰 결과

### plan-eng-review (2026-07-11, 적대적 code-reviewer)

**BLOCKER 0건.** 부팅 계약(claim 1~4) 코드 반증 실패 = plan 주장 옳음.
- ✅ claim1 부팅 계약: automation main 빈 13개 생성자 의존 전수 추적 → 조립+prod 미충족 의존 0. 두 prod 어댑터(IdentityAccessAutomationPermissionResolver·AutomationIssueMutationAdapter)는 이미 조립돼 현 8-BC 부팅 테스트서 검증 중(소비자 없어도 non-lazy 인스턴스화). test stub은 automation/src/test에만(implementation 의존은 test 클래스 미포함) + `allow-bean-definition-overriding:true` 이중안전.
- ✅ claim2 마이그레이션 순서 무관: V301 pgmq 자체완결·멱등, V300 pgcrypto IF NOT EXISTS, cross-BC 객체 참조 0. q_automation_events=issue V036:25.
- ✅ claim3 전역 스케줄링: @Scheduled는 ContextRefreshedEvent 이후 시작, FlywayAssemblyConfig는 InitializingBean(refresh 이전) → 폴링 시점 큐/테이블 실재. AutomationSchedulingConfig inert.
- ✅ claim4 TDD RED: AutomationExecutionWorker 최상위 @Component, value 미지정, @Transactional 없음 → FQN 빈이름 규약 일치, 문자열 단언 진짜 RED.

**CONCERN 2건 (부팅 아님 — 완결성/drift):**
- **C1 (표기 전수 정정 누락)**: R4 목록이 라이브 "8개 BC" 2곳 누락 — `backend/modules/app/src/main/resources/application.yml:1`, `infra/docker-compose.prod.yml:93`(실제 prod 배포 서술자). verify-master-plan은 이 형식을 못 잡아 조용히 drift(CLAUDE.md §전수 동기화 위반). → **반영: R4에 2파일 추가 + verify-master-plan에 조립 BC 카운트 룰 확장(Task 2)**.
- **C2 (웹훅 인바운드 permitAll 침묵)**: `AutomationWebhookController`(`/api/v1/automation/webhooks/{token}`, permitAll 설계)가 조립되나 중앙 `SecurityConfig`(anyRequest().authenticated, 화이트리스트 미포함) → prod 401. FR-AT-01 WEBHOOK 트리거 사문화. **단 slack `/slack/events`도 동일하게 미등록·후속 추적 중** — 인바운드 permitAll 중앙등록은 BTS의 알려진 BC별 배포-시점 후속 패턴. → **게이트 1 결정 D3**.

**NIT 3건:**
- N1 단일스레드 스케줄러 공유(automation 폴러 3종 추가, AutomationExecutionWorker는 아웃바운드 HTTP) — 부팅 무관·기존 패턴 확장·범위 밖. 조립 차원 스케줄러 pool 후속으로 기록.
- N2 "가짜 red" 여지: dev postgres 미기동 시 DB 연결 실패로 단언 이전 죽음. → **Task 1 검증에 "RED 실패 사유=빈 부재 확인" 추가**.
- N3 plan 라인참조 부정확: BtsApplication "8개 BC"는 L1뿐(L21 아님). → R4 라인참조 정정(아래).

### 리뷰 반영 (plan 수정)

- **R4 정정 목록 확정(7곳)**: build.gradle.kts L1·L41 · FlywayAssemblyConfig KDoc L13 · BtsApplication KDoc **L1만** · BtsApplicationContextTest L1 · settings.gradle L16 · **application.yml L1(추가)** · **infra/docker-compose.prod.yml L93(추가)**.
- **의존 인벤토리 보강(N/A 무해)**: automation은 표의 3개 포트 외에 `RestClient`(shared-kernel OutboundHttpClientConfig @Bean, notification/search 공유)·프레임워크 빈(JdbcTemplate·ObjectMapper) 소비 — 전부 조립 실재, 부팅 무해.
- **Task 2에 verify-master-plan 확장 추가**: 조립 "N개 BC" 표기 카운트 가드 룰(일부러 위반 넣어 fail 확인).
