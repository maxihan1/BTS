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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
