# FR-PL-01 — 일정 필드 (Start/Due/Target Date)

> slug: fr-pl-01-issue-dates
> type: feature
> agent: backend-engineer (D1/D4/D5) + db-engineer (D3) + frontend-engineer (D6) + qa-engineer (D7)
> primary_bc: issue-tracking
> 생성: 2026-06-19

## Brief

FR-PL-01 (agile-planning §6.1). 이슈에 일정 필드 3종(start_date, due_date, target_date) 추가.
- 데이터: `issues.start_date, due_date, target_date` (DATE)
- 백엔드: 이슈 PATCH 엔드포인트 확장
- 프론트: date-fns + 데이트픽커 UI
- 선행: issue-tracking §2.1.1 (FR-IS-01 이슈 CRUD) — 완료됨
- BC 경계: FR은 agile-planning 분류이나 구현은 issue-tracking BC (issues 테이블 + 이슈 PATCH)

classify-task 오판정(auth/security) → feature/backend-engineer override.

## 도메인 정리

- **BC**: issue-tracking (확정). FR은 agile-planning §6.1 분류이나, 데이터는 `issues` 테이블 + PATCH 엔드포인트 확장 = issue-tracking BC. 모든 선행 `issues.X` 컬럼 추가(priority/assignee/labels/components/versions/parent/securityLevel/customFields)가 issue-tracking BC였던 전례 일치. agile-planning 모듈 신설 불필요.
- **영향 엔티티**: Issue 1개. nullable `LocalDate?` 3필드 추가 — `startDate`(시작일), `dueDate`(마감일/종료일), `targetDate`(목표일).
- **도메인 결정**:
  1. **타입 = DATE(Kotlin `LocalDate?`)**. 시각/타임존 성분 없는 캘린더 날짜 — Jira Start/Due date 정석. SDD 05.1(24~26행) `DATE NULL` 정합. → **D2 타임존 정책의 답**: 날짜 전용이라 타임존 무관, 저장/표시 모두 캘린더 날짜 그대로.
  2. **PATCH 3-state sentinel = `JsonNullable<LocalDate>`** (각 필드). `securityLevelId: JsonNullable<UUID>` 선례 동형 — undefined=무변경 / null=클리어 / 값=설정. nullable 날짜는 "지우기" vs "안 건드림" 구분 필수.
  3. **도메인 mutation 경유 강제**. `assignSecurityLevel(levelId)` 패턴 따라 도메인 메서드(예: `assignSchedule(...)` 또는 개별)로 변경. repository 직접 update 금지(patch-merge-domain-bypass 방지). version +1 책임 위치는 기존 선례(securityLevel은 도메인 메서드서 +1, label/priority는 repository서 +1) 확인해 spec서 확정.
- **교차 필드 검증(start ≤ due 등)**: 도메인 차원은 독립 nullable 3필드로 둠. Jira는 기본 강제 안 함(경고만). 강제 여부 = **spec 결정 사안** → bts-spec에서 정책 확정.
- **새 용어**: "일정 필드"(Schedule Dates) — 평범한 서술어라 glossary 신규 등재 불요(Maxi 확인 생략). 필요 시 spec서 재검토.
- **기존 결정 충돌**: 없음. SDD 05.1이 이미 명세. issue-tracking domain 노트 기존 ADR(이슈키 prefix, IssueType cross-BC) 무관.
- **관련 ADR**: 없음 (SDD 05.1 명세 기준 구현, 신규 ADR 불필요).
- **마이그레이션**: 다음 번호 **V025** (현재 최신 V024 issue_watchers). init_codegen.sql issues 블록에도 미러 필수(jOOQ codegen, memory: jooq-init-codegen-mirror). ⚠️ V번호는 머지 직전 재확인(동시 브랜치 충돌 방지 — 현재 병행 fr-nt-03은 notification 모듈이라 issue-tracking과 무충돌).
- **grill-with-docs**: 생략. SDD 완전 명세 + securityLevelId 동형 선례 + BC 무모호 → 직접 도메인 정리(memory: bts-spec-office-hours-mismatch / bts-review-plan-autoplan-overkill 원칙).

## 스펙

전체 스펙. [docs/specs/2026-06-19-fr-pl-01-issue-dates.md](../specs/2026-06-19-fr-pl-01-issue-dates.md)

핵심 결정 요약.
- 3필드(startDate/dueDate/targetDate) `LocalDate?` DATE NULL. 시각/타임존 없음.
- PATCH /{key} 확장, `JsonNullable<LocalDate>` 3-state(부재=무변경/null=클리어/값=설정) — securityLevelId 동형.
- 교차 검증 없음(Maxi 확정, Jira 정석) — Version `ChangeVersionDatesRequest` 주석 정책과 동일.
- 권한 = 기존 `IssuePermission.UPDATE`(IssueScope.Issue) 재사용. 신규 권한 없음.
- 도메인 mutation 경유 + OCC version bump. 마이그레이션 V025 + init_codegen 미러.

## Brainstorming Check

✅ 통과 (직접 sanity check). Version 날짜 패턴 + securityLevelId 3-state 두 동형 선례로 설계 공간 닫힘. 인터랙티브 brainstorming/office-hours 생략(memory: bts-spec-office-hours-mismatch — 잘 명세된 FR엔 직접 기술 스펙). 유일 미결(교차검증)은 Maxi AskUserQuestion으로 "검증 없음" 확정.

## Plan

### 설계 확정 (plan-level 결정)

- **날짜 3-state 영속 = `updateFields` 단일 UPDATE에 포함** (securityLevel식 별도 sub-update 아님). 이유: 날짜는 특수 권한·불변식·감사 경로가 없음 → summary/priority와 동급 평범 필드. version 이중 bump 회피.
- **도메인 mutation 메서드 없음**. 날짜엔 도메인 불변식이 없어 patch-merge-domain-bypass 대상 아님(securityLevel은 SET_SECURITY+스킴검증 때문에 mutation 경유였음). Issue는 평범 nullable 필드로 보유, updateFields가 직접 SET.
- **`DatePatch` sealed interface** (application 레이어) — `Unchanged`/`Clear`/`Set(value: LocalDate)`. `SecurityLevelPatch` 동형. 3필드 공용 1타입.
- **2-레이어 DTO**: REST `UpdateIssueRequest`(adapter.inbound.rest, `JsonNullable<LocalDate>` ×3 + Jakarta) → 컨트롤러 `toDatePatch()` ×3 → App `UpdateIssueRequest`(application, `DatePatch` ×3).
- **검증 없음** — 교차 필드 검증 0(Maxi 확정). `updateFields` when-set만.

### Task 1. V025 마이그레이션 + init_codegen 미러 (날짜 3컬럼)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V025__issue_schedule_dates.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`]
- depends-on: []

**RED**: 마이그레이션 검증 통합 테스트(기존 Flyway/Testcontainers boot 또는 신규 스키마 단언) — `issues.start_date/due_date/target_date` 컬럼 부재로 실패. (init_codegen 미러 없으면 jOOQ `ISSUES.START_DATE` 미생성 → 후속 백엔드 task 컴파일 실패가 RED 신호로도 작동.)

**GREEN**:
- `V025`: `ALTER TABLE issues ADD COLUMN start_date DATE NULL, ADD COLUMN due_date DATE NULL, ADD COLUMN target_date DATE NULL;` + 3 COMMENT('일정 — 날짜 단위라 DATE'). V010 versions 주석 톤 일치.
- `init_codegen.sql` **파일 하단**(V024 블록 다음)에 `-- V025: issues 일정 3컬럼 추가` 주석 + `ALTER TABLE issues ADD COLUMN ...` trailing 블록으로 미러 (B2 리뷰 — V006(184행)/V007(200행)/V014(381행) 지배적 컨벤션. **35행 CREATE TABLE 인라인 아님** — 인라인은 parent_id 예외 1건뿐).

**REFACTOR**: COMMENT 문구 통일, 컬럼 순서 SDD 05.1 따름(start→due→target).

**검증**: `./gradlew :backend:issue-tracking:generateJooq :backend:issue-tracking:compileKotlin` (codegen에 `ISSUES.START_DATE` 등 생성 확인) + 마이그레이션 통합 테스트.

### Task 2. Issue 도메인 — LocalDate? 3필드 추가

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueTest.kt`]
- depends-on: []

**RED**: `Issue` 인스턴스가 `startDate/dueDate/targetDate: LocalDate?`(기본 null)를 보유하는지 / `create()` 기본 null 테스트 → 필드 부재로 실패.

**GREEN**: `Issue` data class에 `val startDate: LocalDate? = null, val dueDate: LocalDate? = null, val targetDate: LocalDate? = null` 추가. `create()`는 무변경(기본 null — 생성 시 날짜 미지정, FR 범위). KDoc 3줄.

**REFACTOR**: KDoc @property 정리. 불변식·mutation 메서드 추가 안 함(날짜 검증 없음).

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueTest"`.

### Task 3. DatePatch sealed interface + App UpdateIssueRequest 확장

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationRequestsTest.kt`]
- depends-on: []

**RED**: `DatePatch.Unchanged/Clear/Set(date)` 3-state 존재 + App `UpdateIssueRequest`가 `startDate/dueDate/targetDate: DatePatch = DatePatch.Unchanged` 보유 테스트 → 미존재로 실패.

**GREEN**: `SecurityLevelPatch`(44행) 동형 `sealed interface DatePatch { data object Unchanged; data object Clear; data class Set(val value: LocalDate) }`. App `UpdateIssueRequest`에 3필드 추가(기본 Unchanged). KDoc.

**REFACTOR**: KDoc 3-state 시맨틱 명시(Unchanged=무변경/Clear=해제/Set=설정).

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueApplicationRequestsTest"`.

### Task 4. Repository — IssueFieldPatch 확장 + updateFields 3-state 영속 + row 매핑

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryTest.kt`]
- depends-on: [1]

**RED**: 통합 테스트(Testcontainers) — PATCH로 날짜 설정→재조회 반영 / 명시 Clear→null / Unchanged→기존 유지. `ISSUES.START_DATE` 미영속으로 실패.

**GREEN**:
- `IssueFieldPatch`(104행)에 `startDate/dueDate/targetDate: DatePatch = DatePatch.Unchanged` 추가.
- `updateFields`(236행)에 3필드 when-분기: `Set→set(ISSUES.START_DATE, value)`, `Clear→set(ISSUES.START_DATE, null as LocalDate?)`, `Unchanged→{}`. (jOOQ null-set 실행 선례 — `updateSecurityLevel` 1079행 `UUID?`, description 247행.)
- row 매퍼 — `IssueRepository.kt` **1376행 `private fun IssuesRecord.toIssue()` 단일 확장함수**에 `startDate = startDate, dueDate = dueDate, targetDate = targetDate` 3줄 추가. 모든 조회 경로(findByKey 198행·findByKeyWithType 481/565행·list 384행)가 이 한 함수 공유 (B3 리뷰 — "조립부"가 여러 곳 아님, 단일 매퍼). codegen 의존이라 depends-on:[1] 유효.

**REFACTOR**: when-분기 중복 3회 → private 헬퍼 추출 **시도**. ⚠️ jOOQ `set(Field, T)`/`set(Field, Field)` 오버로드 모호로 막히면 기존 `.apply { ... set(...) }` 인라인 패턴(245~252행) 유지 (C1 리뷰 — `updateFields`에 `@Suppress("CyclomaticComplexMethod")` 이미 부착돼 분기 추가 detekt 무해).

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueRepositoryTest"` (단독 재실행 — 동시 suite flaky 회피, memory).

### Task 5. Service updateIssue — 날짜 패치 배선 + buildChangedFields 감지

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceTest.kt`]
- depends-on: [2, 3, 4]

**RED** (C3 리뷰 — 경계 명시): 서비스 단위 테스트 —
- App req의 DatePatch가 `IssueFieldPatch`로 전달되는지.
- **날짜-only PATCH → `changedFields`에 해당 날짜 필드명 포함 → `updateFields` 호출됨(492행 `handleCoreFieldsUnchanged` 1734행 경로 **아님**) → version+1**.
- `Unchanged` → 무변경(감지 false).
- **Clear 경계**: 기존 null인데 Clear 요청 = no-op(미감지), 기존 값 있는데 Clear = 변경(감지). 두 케이스 분리 단언.
→ 미구현으로 실패.

**GREEN**:
- `updateIssue`(470행)의 `IssueFieldPatch(...)` 생성에 `startDate=request.startDate, dueDate=request.dueDate, targetDate=request.targetDate` 추가.
- `buildChangedFields`(1128행)에 날짜 변경 감지: 각 DatePatch가 기존값과 다르면 "startDate"/"dueDate"/"targetDate" 포함. → 날짜-only PATCH도 updateFields 진입(version+1, 이벤트/이력 기록).

**REFACTOR**: 날짜 감지 로직 private 헬퍼(`datePatchChanges(existing, patch)`)로 추출.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueApplicationServiceTest"`.

### Task 6. REST DTO + Controller toDatePatch + IssueResponse 확장

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/UpdateIssueRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerTest.kt`]
- depends-on: [3, 5]

**RED** (B1/C4 리뷰 강화): WebMvc/통합 테스트 —
- `PATCH /api/v1/issues/{key}` body `{startDate, dueDate:null, targetDate}` → 응답 IssueResponse에 **설정값 그대로 반영**(설정/클리어). **C4 — `from()` 매핑 누락 시 응답이 default null로 새는 가짜그린** 방지: 설정한 날짜가 응답에 동일하게 나오는지 단언.
- **B1 — 응답 JSON의 날짜가 `"yyyy-MM-dd"` 문자열인지 직접 단언**(배열 `[2026,6,20]` 아님).
- 잘못된 형식("2026-13-40") 400(E4), 미포함 필드 무변경.
→ 실패.

**GREEN**:
- REST `UpdateIssueRequest`: `startDate/dueDate/targetDate: JsonNullable<LocalDate> = JsonNullable.undefined()` 추가.
- `IssueController.update`(184행): `AppUpdateIssueRequest`(alias, 40행) 빌드에 `startDate = toDatePatch(request.startDate)` ×3. `toDatePatch(raw: JsonNullable<LocalDate>): DatePatch`(565행 toSecurityLevelPatch 동형: !present→Unchanged / get()==null→Clear / else→Set).
- `IssueResponse`: `startDate/dueDate/targetDate: LocalDate? = null` 추가. **B1 — 각 필드에 `@field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")` 부착**(VersionResponse.kt 32~35행 동형. IssueParentResponse 12행 — "prod ObjectMapper 기본값 비의존" 컨벤션. 전역 Jackson 날짜설정 부재 실측). `from(issue)` 본문(290~317행)에 `startDate = issue.startDate` ×3 매핑(securityLevelId 314행 인근). `maskInvisible`(122행) copy는 새 필드 자동 보존(무해).

**REFACTOR**: `toDatePatch` KDoc, 3필드 응답 KDoc.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueControllerTest"`. ⚠️ ktlint/detekt는 controller 직접 검증(에이전트 false-green 불신, memory). **C4 — `IssueResponse` 파라미터 증가 시 detekt `LongParameterList` baseline 영향 직접 확인**.

### Task 7. 프론트 api + Zod 스키마 + MSW 핸들러 (날짜 3필드 계약)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issues.ts`, `apps/web/src/api/issues.test.ts`, `apps/web/src/mocks/issue-handlers.ts`]
- depends-on: []

**RED**: api/MSW 테스트 — 응답 Zod 스키마가 `startDate/dueDate/targetDate: string().nullable()`(yyyy-MM-dd) 파싱 / `updateIssue` 호출 시 3필드 전송(설정/null 클리어) → 미존재로 실패.

**GREEN**:
- `issues.ts` 응답 스키마(IssueResponse 미러)에 3필드 `z.string().nullable()` 추가(백엔드 DTO 1:1, memory frontend-zod-backend-dto-contract-gap).
- `updateIssue` 요청 타입에 3필드 optional(`string | null | undefined`) — 3-state(undefined=미전송/null=클리어/값=설정). CSRF 등 기존 apiFetch 패턴 재사용.
- MSW `issue-handlers.ts`: PATCH 핸들러가 날짜 설정/클리어를 stateful store에 반영(memory: msw-mutation-stateful-refetch).

**REFACTOR**: 스키마 필드 그룹 주석.

**검증**: `pnpm --filter web test issues` + `pnpm --filter web typecheck`.

### Task 8. 프론트 UI — 이슈 상세 데이트픽커 3필드

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueScheduleFields.tsx`, `apps/web/src/components/issue/IssueScheduleFields.test.tsx`, `apps/web/src/routes/issues.$key.tsx`]
- depends-on: [7]

**RED** (C2 리뷰 — clear 정확성): 컴포넌트 테스트 — 3필드 데이트픽커 렌더, 날짜 선택→updateIssue 호출, **비우기 시 `updateIssue({ dueDate: null, ... })` — 키가 존재하고 값이 null인지 capture 단언(키 생략 아님)**. `updateIssue`(issues.ts 408행)가 input 직접 전송이라 키 생략=무변경/명시 null=클리어 구분 필수(createIssue securityLevelId가 동일 방어). 기존값 표시(date-fns 포맷) → 미존재로 실패.

**GREEN**:
- `IssueScheduleFields`: 시작일/마감일/목표일 3 입력. **네이티브 `<input type="date">` 우선**(신규 의존성 0, 절대규칙 #17) + date-fns 표시 포맷(기존 deps 확인). 기존 버전 날짜 UI(FR-VR) 패턴 있으면 재사용.
- 이슈 상세(`issues.$key.tsx`)에 패널 배치(IssueMetaPanel 인근). OCC version round-trip 기존 패턴 재사용.

**REFACTOR**: i18n 키 추출(ko 콜론 종결 금지, memory), 라벨/placeholder 정본화.

**검증**: `pnpm --filter web test IssueScheduleFields` + `pnpm --filter web typecheck` + `pnpm --filter web lint`.

### Task 9. E2E — 일정 설정 happy path

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-schedule.spec.ts`, `apps/web/src/mocks/issue-handlers.ts`]
- depends-on: [8]

**RED**: Playwright — 이슈 상세 진입 → 시작일/마감일/목표일 설정 → 저장 → 재조회 시 표시 확인. (MSW stateful store 시드, memory: e2e-msw-scenario-toggle / msw-derived-behavior-shared-store.) 미구현으로 실패.

**GREEN**: 데이트픽커 채우기 + 저장 + 단언. 텍스트 중복 시 컨테이너 한정 셀렉터(memory: playwright-getbyrole/within).

**REFACTOR**: 셀렉터 정리, 시드 헬퍼 재사용.

**검증**: `pnpm --filter web test:e2e issue-schedule`.

## Plan 메타

- task 수: 9
- agent 분포: db-engineer(T1) / backend-engineer(T2~T6) / frontend-engineer(T7~T8) / qa-engineer(T9)
- 의존성 그래프:
  - Wave 1: T1, T2, T3, T7 (deps 없음, 파일 무겹침)
  - Wave 2: T4(←1), T8(←7)
  - Wave 3: T5(←2,3,4)
  - Wave 4: T6(←3,5)
  - Wave 5: T9(←8)  *(T9는 wave 3 시점부터 가능하나 bts-impl 계산 위임)*
- ⚠️ 백엔드 T2~T6은 같은 Gradle 모듈(issue-tracking)이라 test 컴파일 단위가 직렬화 요인(memory: bts-plan-wave-gradle-module-compile). depends-on은 실제 코드 의존만 선언, wave 기계는 bts-impl이 계산.
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 추가 검증: generateJooq, ktlint, detekt(aggregate baseline), vitest, typecheck, playwright(qa)

## 리뷰 결과

### 독립 eng-review (code-reviewer, 2026-06-19)

autoplan(CEO/design/eng/DX 4종) 대신 eng 집중 독립 적대 리뷰 (memory: bts-review-plan-autoplan-overkill — 백엔드 중심 기계적 FR). 코드 전수 대조.

**핵심 설계 검증 ✅ (OK)**:
- **OCC version 체인 안 깨짐** — securityLevel은 updateFields 전 적용(488행)→`versionAfterSecurity`로 updateFields 호출(509행). 날짜를 updateFields에 태우면 동시 PATCH도 단일 UPDATE라 정합 유지, 이중 bump 없음. 날짜-only는 securityLevel Unchanged early-return(1423행)→updateFields version+1(244행). **plan 설계 근거 정확**.
- V025 실측 정확(최신 V024). DTO 이름충돌 alias 기해결(IssueController 40행). toDatePatch=toSecurityLevelPatch(565행) 동형. jOOQ null-set 실행 선례 다수. Zod nullable 미러 패턴 정확.

**BLOCKER 3건 (전부 plan 누락 = 빠진 명시, 오설계 아님 → plan 직접 반영 완료)**:
- **B1**: IssueResponse 날짜에 `@field:JsonFormat(STRING, "yyyy-MM-dd")` 누락 → VersionResponse 32~35행 동형 필수(전역 Jackson 날짜설정 부재, IssueParentResponse 12행 "prod 기본값 비의존" 컨벤션). → **Task 6 반영**.
- **B2**: init_codegen 미러는 35행 인라인 아니라 **파일 하단 trailing ALTER 블록**(V006/V007/V014 지배 컨벤션). → **Task 1 반영**.
- **B3**: row 매퍼는 1376행 `toIssue()` **단일 확장함수**(전 조회경로 공유). → **Task 4 반영**.

**CONCERN 4건 (RED 단언/REFACTOR fallback으로 흡수 → 반영 완료)**:
- **C1**: when-분기 헬퍼 추출 시 jOOQ 오버로드 모호 가능 → 인라인 `.apply{}` fallback(CyclomaticComplexMethod 이미 Suppress). → Task 4.
- **C2**: 프론트 `updateIssue`(408행) input 직접전송 → clear=명시 null 키(키생략=무변경). → Task 8 RED.
- **C3**: 날짜-only PATCH가 handleCoreFieldsUnchanged(1734행)로 새지 않게 buildChangedFields 감지+Clear 경계 단언. → Task 5 RED.
- **C4**: IssueResponse `from()` 매핑 누락=응답 null 가짜그린 + LongParameterList detekt. → Task 6 RED/검증.

**BLOCKER 잔여: 없음** (3건 모두 plan 반영 완료). 게이트1 진입 가능.
