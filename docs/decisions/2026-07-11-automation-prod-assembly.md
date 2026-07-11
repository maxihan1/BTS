# ADR — automation 모듈 prod 조립 배선 (:modules:app) + 부팅 검증

- 날짜: 2026-07-11
- 상태: 수락 (Accepted)
- 관련: FR-AT-01(#255)·FR-AT-02(#256) C4 후속 · PR #259 · [[no-cross-bc-deployment-assembly]] · [[module-first-scheduled-worker-detektmain-traps]]
- 선행 ADR: `2026-07-11-fr-at-02-automation-actions`(C4 절 정정 동반) · `2026-07-10-fr-at-01-automation-triggers`

## 맥락 (Context)

automation BC(9번째 모듈, FR-AT-01 트리거 + FR-AT-02 액션)가 prod 조립 앱 `:modules:app`(#253)에 미배선이었다. app `build.gradle.kts`가 8개 BC만 의존하고 `:modules:automation`을 빠뜨려, automation의 pgmq consumer·`@Scheduled` 워커·cross-BC 커맨드 포트가 prod에서 미가동이었다. 배포 전 반드시 해소해야 한다.

FR-AT-02의 C4 ADR은 "cross-BC 배포 조립 모듈이 아직 없다 / 신설은 부팅 불가라 후속"으로 판단했으나, 이는 그 worktree가 #253(`:modules:app` 신설) 머지 **이전 base**라서 나온 stale framing이었다. 실제로 `:modules:app`은 8개 BC를 prod 프로파일로 조립·부팅 중이었다.

## 결정 (Decision)

### 배선 방식 — 기존 조립 앱에 automation 추가 (모듈 신설 아님)

1. `:modules:app` `build.gradle.kts`에 `implementation(project(":modules:automation"))` 추가(9번째 BC 모듈).
2. `FlywayAssemblyConfig`의 모듈 목록에 `"automation" to "classpath:db/migration/automation"` 추가 — automation V300~V303을 `flyway_history_automation` 이력 밴드로 조립 DB에 적용. (V301 `CREATE EXTENSION IF NOT EXISTS pgmq CASCADE` 자체완결·멱등, automation 테이블 cross-BC FK 없음 → 목록 끝 append 순서 무관.)
3. `BtsApplicationContextTest`(@ActiveProfiles prod)에 automation 빈 존재 단언 추가 — FQN 빈 이름(`com.bts.automation.worker.AutomationExecutionWorker`)으로 조립·부팅을 회귀 가드.

### D1 — 스케줄링은 전역 `@EnableScheduling` 위임

automation 워커 3종은 순수 `@Component`이고, 조립 앱 `BtsApplication`이 전역 `@EnableScheduling`을 보유한다. 따라서 워커는 조립 스캔 시 전역 스케줄러가 구동하며, `AutomationSchedulingConfig`의 opt-in property(`bts.automation.scheduling.enabled`)는 prod에서 불요하다(notification `SchedulingConfiguration` 동형). 그 property는 automation 단독 test-assembled의 opt-in 스위치로만 남는다.

### D2 — 부팅 검증 = contextLoads + automation 빈 단언

컨텍스트 로드 성공(빈 충돌·미배선·마이그레이션 실패 없음) + automation 빈 존재 단언으로 vacuous 방지.

### D3 — 웹훅 인바운드 permitAll은 이 PR 범위 밖 (scope-out)

`AutomationWebhookController`(`/api/v1/automation/webhooks/{token}`)는 조립되나 중앙 `SecurityConfig`(anyRequest().authenticated) 화이트리스트에 없어 prod에서 401이며, FR-AT-01 WEBHOOK 트리거는 사문화 상태다. **이 PR은 조립 배선+부팅 검증에 한정하고, 웹훅 permitAll 중앙등록은 slack `/slack/events`(동일하게 미등록·후속 추적 중)와 함께 통합할 인바운드 permitAll 후속으로 미룬다.** identity-access `SecurityConfig`는 이 PR에서 불변. 이유 — cross-BC(identity-access)·보안 민감 변경이라 security-engineer 검토가 필요하고, 인바운드 permitAll 중앙등록은 BTS의 BC별 배포-시점 후속 패턴이다.

## fail-closed / 부팅 계약 검증

automation main 빈 13개의 생성자 의존을 전수 추적한 결과 조립+prod 컨텍스트에서 미충족 의존은 0이다.

- `AutomationPermissionResolver` → `IdentityAccessAutomationPermissionResolver`(`@Profile("prod")`, identity-access) ✓
- `IssueMutationPort` → `AutomationIssueMutationAdapter`(`@Profile("prod")`, issue-tracking) ✓
- `OutboundUrlValidator`·`RestClient`(shared-kernel) · `JdbcTemplate`/`ObjectMapper`(프레임워크) ✓
- `q_automation_events`는 issue-tracking `V036`이 생성(조립 첫 순서) → `AutomationEventWorker` 소비 대상 실재.
- test stub(`StubIssueMutationPort` 등)은 automation/src/test에만 존재(`implementation` 의존은 test 클래스 미포함).

## 결과 (Consequences)

- automation(트리거+액션)이 prod 조립 앱에서 처음 가동된다(Brief '미가동 3종' — pgmq consumer·@Scheduled 워커·IssueMutationPort — 해소).
- `:modules:app`에 detekt baseline 추가(`BtsApplication` `UtilityClassWithPublicConstructor` PRE_EXISTING 오탐 동결, #253부터 잠복·`--rerun-tasks` 미사용으로 가려짐). 타 모듈 패턴 일치.
- `verify-master-plan.sh`에 조립 BC 카운트 정합 가드(섹션 G) 추가 — "N개 BC" 표기 drift 차단.
- FR 총수 123 불변(D-step 후속, 신규 제품 FR 아님).

## 배포 런북 (운영 노트)

- **q_automation_events 백로그 첫 배수 (리뷰 C1).** issue-tracking은 #253 조립 배포 이후 이슈 이벤트를 `q_automation_events`에 발행해왔으나 소비자(automation)가 없었다. 이 배선으로 automation 합류 시 `AutomationEventWorker`가 누적 백로그를 일괄 배수한다. **룰이 없으면 전부 무해 delete**이고 prod 미가동이면 백로그는 사실상 0이다. 다만 백로그가 크면 활성 부팅 직후 룰조회 쿼리 부하 스파이크가 가능하니, 그런 경우 automation 활성 부팅 전 `SELECT pgmq.purge_queue('q_automation_events');` 를 고려한다(무해 삭제 대상이라 안전).

## 후속 (Follow-ups, BLOCKER 아님)

- 인바운드 permitAll 중앙등록(slack `/slack/events` + automation `/api/v1/automation/webhooks/{token}`) — security-engineer 트랙.
- FR-AT-02 D6 액션빌더 UI · D7 E2E.
- FR-AT-03/04/05.
- 조립 앱 스케줄러 pool-size 후속(단일 스레드 공유, automation 폴러 3종 추가 — 아웃바운드 HTTP 포함).

## 대안 (기각)

- **automation 전용 조립 모듈 신설** — `:modules:app`이 이미 존재하므로 불필요. 중복 조립·유지비.
- **`AutomationSchedulingConfig` property를 prod yml에 명시(D1 대안)** — 전역 `@EnableScheduling`이 이미 워커를 구동하므로 기능상 redundant. 기각.
- **이 PR에서 웹훅 permitAll 등록(D3 대안)** — cross-BC·보안 민감. slack과 통합 후속이 더 자연스러운 배치. 기각.
