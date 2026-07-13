# FR-SL-06 D6 UI (프로젝트 설정 → Slack 채널 관리 화면) + D7 E2E

> slug: fr-sl-06-d6-d7-slack-channel-mapping-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-13

## Brief

FR-SL-06(채널↔프로젝트 매핑)의 마지막 남은 D6 UI + D7 E2E를 구현한다.
백엔드는 완료됨:
- PR-A(#264): 매핑 CRUD API + 설정. V704, projectKey, 하드삭제, PROJECT_ADMIN 가드,
  403→404/malformed→400 정규화.
- PR-B(#266): 채널 라우팅. notification SlackChannelBroadcaster → q_slack_channel_broadcasts
  → 채널 워커, 보안게이트 IssueSecurityClassificationPort(fail-closed).

D6 = 프로젝트 설정 화면에 "Slack 채널 관리" 섹션 신설.
  채널↔프로젝트 매핑 CRUD(생성/목록/삭제) + event_filter 선택 UI.
D7 = Playwright E2E 시나리오.

완료 시 slack-integration BC = 6/6 · BC 완료 마킹 + 문서 전수 동기화(verify-master-plan).

## 도메인 정리

- BC: slack-integration (프론트엔드 소비). 새 도메인 용어/엔티티 없음 — 백엔드 완비.
- 새 용어: 없음. glossary 갱신 불필요.
- 기존 결정 충돌: 없음.
- 관련 ADR: [docs/decisions/2026-07-13-fr-sl-06-channel-mapping.md](../decisions/2026-07-13-fr-sl-06-channel-mapping.md) (PR-A). 신규 ADR 불필요 — UI는 순수 소비.

### 백엔드 API 계약 (코드 확인 완료, 이 UI가 붙는 대상)

REST 4 endpoint (`SlackChannelMappingController`, `/api/v1/slack/channel-mappings`, JWT 전용·PAT 401):

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/channel-mappings` | `{projectKey, channelId, channelName?, eventTypes: string[]}` | 201 + `ChannelMappingResponse` |
| GET | `/channel-mappings?projectKey=` | — | 200 + `ChannelMappingResponse[]` |
| PATCH | `/channel-mappings/{id}` | `{channelId?, channelName?, eventTypes?}` (null=미변경) | 200 + `ChannelMappingResponse` |
| DELETE | `/channel-mappings/{id}` | — | 204 |

`ChannelMappingResponse` = `{id: UUID, projectKey, channelId, channelName: string|null, eventTypes: string[](정렬됨), createdAt, updatedAt}`. **team_id 비노출**(단일 워크스페이스 설치, 내부 해석).

**event_filter 허용값 10종** (`SlackChannelEventType`, notification wireValue 미러):
`issue.created` · `issue.assigned` · `issue.transitioned` · `issue.commented` · `issue.due_soon` · `issue.overdue` · `sprint.started` · `sprint.ended` · `automation.failed` · `issue.mentioned`. 빈 집합/미지값 → 400.

**권한/에러 계약**: PROJECT_ADMIN(service 내부 fail-closed). 권한 거부/미존재 → **404**(존재 비노출). malformed 입력 → **400**. PAT 인증 → 401.

### 프론트엔드 관례 (같은 앱 grep 확인)

- 라우트: `src/routes/projects.$projectKey.settings.slack-channels.tsx` (선례 `.automation.tsx` 동형 — projectKey 스코프 + admin 게이트 + 룰 CRUD).
- API: `src/api/slack.ts`에 채널 매핑 함수 추가(기존 slack API 관례 유지).
- MSW: `src/mocks/slack-channel-mapping-handlers.ts` 신규 + **전역 등록 필수**(`msw-global-handler-registration-gap` 회귀 방지).
- 컴포넌트: `src/components/settings/` 하위(기존 `SlackConnectionCard` 등과 동일 위치).

### 스코프 보정

- plan 스텁 Brief의 "CRUD(생성/목록/삭제)"는 **update(PATCH) 누락**. 백엔드가 PATCH 완비 → UI도 수정 포함(완제품 기준). 스펙에서 최종 확정.

## 스펙

전체 스펙. [docs/specs/2026-07-13-fr-sl-06-d6-d7-slack-channel-mapping-ui.md](../specs/2026-07-13-fr-sl-06-d6-d7-slack-channel-mapping-ui.md)

핵심 시나리오 요약.
- 프로젝트 설정 → "Slack 채널" 탭에서 채널↔프로젝트 매핑을 CRUD(생성/목록/수정/삭제).
- 각 매핑 = 채널 ID(+표시명) + event_filter(10종 이벤트 유형 다중선택).
- 선례 `settings.automation.tsx` 미러(RouteAdapter+props-Page, List+FormDialog, `p-8 space-y-6 max-w-2xl`).
- office-hours 스킵(메모리 `bts-spec-office-hours-mismatch` — 명확·완결 후속 FR). design-consultation 스킵(DESIGN.md 존재). design-shotgun 스킵(기존 설정 페이지 패턴 미러, 새 시각 발명 불필요).

**게이트1 확정 필요 결정**.
- **C1. 채널 ID 직접입력(추천) vs 채널 picker(백엔드 확장)** — 백엔드에 채널 목록 API 없음.
- EC4 워크스페이스 미설치 사전 안내 배너 포함 여부(409 처리는 기본, 배너는 선택).

## Brainstorming Check

✅ 통과 (1회 iteration). 409 두 종류(중복·워크스페이스미설치) 폼 처리 과소명세 발견 후 보강. C1 결정만 게이트1로.

## Plan

> 선례 미러 = `settings.automation.tsx`(RouteAdapter+props-Page, List+FormDialog). 백엔드 완비.
> **설정 네비게이션 발견** — 이 앱은 프로젝트 설정 공유 nav가 없음(automation·members 전부 router.ts 등록 + 직접 URL). 따라서 nav 항목 추가 없음(scope creep 회피), 라우트 등록만.

### Task 1. Slack 채널 매핑 API 클라이언트 (`slack.ts` 확장)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/slack.ts`, `apps/web/src/api/slack.test.ts`]
- depends-on: []

**RED**: `slack.test.ts`에 4 함수 테스트 추가.
- `listChannelMappings(projectKey)` → `GET /api/v1/slack/channel-mappings?projectKey=` 호출·`ChannelMapping[]` 파싱.
- `createChannelMapping({projectKey, channelId, channelName?, eventTypes})` → `POST` 201.
- `updateChannelMapping(id, {channelId?, channelName?, eventTypes?})` → `PATCH` 200.
- `deleteChannelMapping(id)` → `DELETE` 204(`apiFetch` 직접, `disconnectSlack` 선례).
- Zod parse: `ChannelMappingSchema`가 `{id, projectKey, channelId, channelName: nullable, eventTypes: string[], createdAt, updatedAt}` 검증.
- 실패(예상): 함수/스키마 미존재.

**GREEN**: `slack.ts`에 `ChannelMappingSchema`(z.object) + `ChannelMapping` 타입(z.infer) + 4 함수.
- backend DTO 1:1(메모리 `frontend-zod-backend-dto-contract-gap` — invent 금지). `eventTypes`는 `z.array(z.string())`.

**REFACTOR**: KDoc(각 함수 endpoint·에러코드 명시: 409 CONFLICT/WORKSPACE_NOT_INSTALLED·400·404·401), 기존 slack.ts 구조(스키마 그룹→타입→함수) 유지.

**검증**: `pnpm --filter web test src/api/slack.test.ts` + `pnpm --filter web typecheck`

---

### Task 2. 이벤트 필터 카탈로그 + 다중선택 컴포넌트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/slack-event-types.ts`, `apps/web/src/components/settings/SlackEventFilterSelect.tsx`, `apps/web/src/components/settings/SlackEventFilterSelect.test.tsx`]
- depends-on: []

**RED**: `SlackEventFilterSelect.test.tsx`.
- 10종 이벤트가 그룹(이슈/스프린트/자동화)으로 렌더되고 한글 라벨 표시.
- 체크박스 토글 시 `onChange(selected: string[])` 호출.
- `value` prop으로 선택 상태 제어(controlled).
- 실패(예상): 컴포넌트/카탈로그 미존재.

**GREEN**:
- `slack-event-types.ts` — wireValue 10종 + 한글 라벨 + 그룹 카탈로그(스펙 표 그대로). 백엔드 `SlackChannelEventType` 미러(값 일치).
- `SlackEventFilterSelect.tsx` — controlled 다중선택(체크박스 그룹). DESIGN.md 토큰 준수.

**REFACTOR**: 카탈로그를 `as const` + 그룹 파생, KDoc(백엔드 미러 사유·동기화 주의).

**검증**: `pnpm --filter web test SlackEventFilterSelect` + `typecheck`

---

### Task 3. 매핑 목록 컴포넌트 (`SlackChannelMappingList`)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/settings/SlackChannelMappingList.tsx`, `apps/web/src/components/settings/SlackChannelMappingList.test.tsx`]
- depends-on: [1]

**RED**: `SlackChannelMappingList.test.tsx`.
- `useQuery`로 목록 조회 → 채널명/ID + 선택 이벤트 라벨 렌더.
- 로딩/에러(404 → "권한 없음/찾을 수 없음")/빈 상태 3종.
- "채널 추가" 버튼 → `onAddRule` 대응 `onAdd()` 콜백. 행별 "수정"/"삭제" → `onEdit(mapping)`/삭제 트리거.
- 삭제는 확인 후 `deleteChannelMapping` mutation → 성공 시 목록 invalidate.
- 실패(예상): 컴포넌트 미존재.

**GREEN**: `AutomationRuleList` 구조 미러. `queryKey: ['slack-channel-mappings', projectKey]`. 삭제 mutation invalidate-only(메모리 `mutation-setquerydata-partial-response-flicker`).

**REFACTOR**: 빈/에러 상태 컴포넌트 분리, KDoc. E2E 견고성 위해 액션 버튼 컨테이너 한정/`data-testid`(메모리 `playwright-getbyrole-exact-strict-mode`).

**검증**: `pnpm --filter web test SlackChannelMappingList` + `typecheck`

---

### Task 4. 폼 다이얼로그 (`SlackChannelMappingFormDialog`)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/settings/SlackChannelMappingFormDialog.tsx`, `apps/web/src/components/settings/SlackChannelMappingFormDialog.test.tsx`]
- depends-on: [1, 2]

**RED**: `SlackChannelMappingFormDialog.test.tsx`.
- 채널 ID 입력 + 채널 표시명(선택) + `SlackEventFilterSelect`.
- 신규 = create, `editingMapping` prop 있으면 프리필 = update(PATCH 변경 필드).
- **이벤트 0개 저장 가드**(disable 또는 검증 메시지, EC1).
- **409 처리**: create/update가 `SLACK_CHANNEL_MAPPING_CONFLICT` → "이미 동일한 채널 매핑이 존재합니다" 폼 에러. `WORKSPACE_NOT_INSTALLED` → 워크스페이스 연결 안내. 400 → malformed 에러.
- 성공 시 `onOpenChange(false)` + 목록 invalidate.
- editingMapping 토글 시 폼 프리필 stale 방지(메모리 `react-usestate-stale-key-prop` — key 재마운트).
- 실패(예상): 컴포넌트 미존재.

**GREEN**: `AutomationRuleFormDialog` 구조 미러(Radix Dialog). `ApiError.code`로 409 분기. submitError는 폼 자체 state(메모리 `dialog-submiterror-ownership-dead-path`·`form-occ-409-parent-usestate-staleness`).

**REFACTOR**: 에러코드→메시지 매핑 상수, KDoc.

**검증**: `pnpm --filter web test SlackChannelMappingFormDialog` + `typecheck`

---

### Task 5. 라우트 페이지 + router.ts 등록

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.settings.slack-channels.tsx`, `apps/web/src/routes/__tests__/projects.$projectKey.settings.slack-channels.test.tsx`, `apps/web/src/router.ts`]
- depends-on: [3, 4]

**RED**: 라우트 페이지 테스트.
- `ProjectSlackChannelSettingsRouteAdapter`(useParams) + `ProjectSlackChannelSettingsPage`(props).
- projectKey 빈 문자열 → `ProjectNotFoundScreen`(members에서 import).
- 헤더 + List + FormDialog 조립. dialogOpen/editingMapping state 보유(automation 선례).
- 실패(예상): 페이지 미존재.

**GREEN**: `settings.automation.tsx` 구조 그대로(WebhookTokenModal만 제외 — slack엔 불필요). `router.ts`에 import + `projectSlackChannelsRoute` 등록(`/projects/$projectKey/settings/slack-channels`), 라우트 트리 주석 카운트 갱신.

**REFACTOR**: KDoc, `p-8 space-y-6 max-w-2xl` 헤더 패턴.

**검증**: `pnpm --filter web test slack-channels` + `typecheck` + `pnpm --filter web build`(router 정합)

---

### Task 6. MSW 핸들러 + 전역 등록 (stateful CRUD)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/slack-channel-mapping-handlers.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [1]

**RED**(회귀가드): E2E/통합에서 소비. stateful store(생성→목록 반영·삭제→제거·중복→409).
- **전역 등록 필수**(메모리 `msw-global-handler-registration-gap` — 미등록 시 E2E만 누출적발). `handlers.ts`에 `import` + `...slackChannelMappingHandlers` 추가.

**GREEN**: `slackHandlers`/`automationRuleHandlers` 선례 미러. 시드가능 공유 store(메모리 `msw-derived-behavior-shared-store-e2e`·`msw-mutation-stateful-refetch`). 409(중복 channelId)·400(빈 eventTypes)·404(미존재 id) 시뮬레이션.

**REFACTOR**: 시나리오 토글(localStorage flag, 메모리 `e2e-msw-scenario-toggle-localstorage-flag`) — 워크스페이스 미설치/빈 상태.

**검증**: `pnpm --filter web test`(전역 등록 후 기존 스위트 green)

---

### Task 7. D7 E2E (Playwright) — qa-engineer

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/slack-channel-mapping.spec.ts`]
- depends-on: [5, 6]

**RED→GREEN**: 스펙 E1/E2/E3.
- E1 happy path — 설정 URL 진입 → 채널 추가(ID+이벤트 2개) → 목록 표시 → 이벤트 수정 → 삭제.
- E2 이벤트 미선택 가드.
- E3 빈 상태.
- MSW 구동(serviceWorkers block 금지, 메모리 `e2e-msw-serviceworker-block`). **기존 E2E 회귀 0 확인**(메모리 `ui-pr-defer-e2e-regression-latent` — 같은 앱 전체 E2E 실행).

**검증**: `pnpm --filter web test:e2e slack-channel-mapping` + 전체 E2E 회귀 확인

---

## Plan 메타

- task 수: 7 (각 TDD 사이클)
- wave 예상: 4 (W1: T1·T2 / W2: T3·T4·T6 / W3: T5 / W4: T7)
- 예상 시간: 병렬 wave 기준 약 15~20분
- TDD 강제: yes (test→feat→refactor 커밋 순서 검증)
- 추가 검증: typecheck(tsconfig.app.json, 메모리 `ci-typecheck-tsconfig-app-vs-local`), lint, vitest, playwright, `pnpm verify`
- 문서 동기화(머지 시): fr-index·SDD 09·product/slack-integration.md D6/D7 [x]·BC 6/6·README·CLAUDE FR 상태·verify-master-plan

## 리뷰 결과 (← /bts-review-plan 채움)
