# FR-TT-01 D6/D7 — Worklog 프론트 UI + E2E — 스펙

> slug: fr-tt-01-d6-d7-worklog-ui-e2e
> type: ui (D6 frontend-engineer, D7 qa-engineer)
> 백엔드: #163 머지 완료 (계약 동결). 본 작업은 view layer + E2E.
> 작성: 2026-06-20 (office-hours 대신 직접 기술 스펙 — 완료된 FR 후속)

## 배경 / 범위

FR-TT-01(Worklog 추정/실제/잔여 시간)의 **D6(프론트 UI) + D7(E2E)**. 백엔드 D1~D5는 #163에서 완료되어 계약이 동결돼 있다. 본 작업은 그 계약을 소비하는 프론트 UI와 E2E만 추가한다(백엔드 변경 0).

**Maxi 확정 (스펙 게이트, 2026-06-20)**.
1. 시간 입력/표시 = **시간(h) + 분(m) 분리 number input** → 초로 변환(h*3600 + m*60). 표시는 `"2h 30m"` 포맷.
2. 범위 = **풀 범위** — 추정 편집(original/remaining PATCH) + Worklog 추가/목록/수정/삭제(본인만) + 잔여 자동계산 미리보기.

## 백엔드 계약 (동결 — 실제 코드 grep 검증, invent 금지)

### 추정 요약/편집 (Issue 애그리거트)

- `GET /api/v1/issues/{key}` 응답 `IssueResponse`에 3필드(키 항상 존재, `@JsonInclude(NON_NULL)` **미적용** → null도 직렬화):
  - `originalEstimateSeconds: number | null`
  - `timeSpentSeconds: number` (항상 ≥0, Worklog 합산 파생, **읽기전용**)
  - `remainingEstimateSeconds: number | null`
- `PATCH /api/v1/issues/{key}` 요청 `UpdateIssueRequest` — JsonNullable 3-state(미전송=무변경 / null=클리어 / 값=설정):
  - `originalEstimateSeconds?: number | null`
  - `remainingEstimateSeconds?: number | null`
  - `timeSpentSeconds`는 PATCH 불가(읽기전용)
  - OCC: `expectedVersion` 동반(기존 updateIssue 패턴). 409 → 충돌 toast + invalidate.

### Worklog CRUD (`/api/v1/issues/{key}/worklogs`)

모든 응답은 `DataResponse<T> = { data: T }`로 래핑.

| 메서드 | 경로 | 요청 | 응답 | 비고 |
|---|---|---|---|---|
| POST | `/worklogs` | `AddWorklogRequest` | 201 `WorklogResponse` | 권한 UPDATE |
| GET | `/worklogs` | — | 200 `WorklogListResponse` | 권한 VIEW |
| PATCH | `/worklogs/{worklogId}` | `UpdateWorklogRequest` | 200 `WorklogResponse` | 권한 UPDATE + **본인만**(타인 403) |
| DELETE | `/worklogs/{worklogId}` | — | 204 | 권한 UPDATE + **본인만**(타인 403) |

DTO 형식(실측).
- `WorklogResponse`: `{ id: string(UUID), issueKey: string, authorId: string(UUID), timeSpentSeconds: number, startedAt: string(ISO Instant), comment: string|null, createdAt: string(ISO), updatedAt: string(ISO) }`
- `WorklogListResponse`: `{ worklogs: WorklogResponse[], summary: WorklogSummary }`
- `WorklogSummary`: `{ originalEstimateSeconds: number|null, timeSpentSeconds: number, remainingEstimateSeconds: number|null }`
- `AddWorklogRequest`: `{ timeSpentSeconds: number(필수, ≥1), startedAt: string(ISO, 필수), comment?: string|null, newRemainingEstimateSeconds?: number|null(≥0) }`
- `UpdateWorklogRequest`: `{ timeSpentSeconds?: number(≥1), startedAt?: string(ISO), comment?: string|null }`

백엔드 검증(400 트리거): POST는 `timeSpentSeconds` 미전달/≤0, `startedAt` 미전달, `newRemainingEstimateSeconds<0`. PATCH는 `timeSpentSeconds`가 있으면서 ≤0.

**잔여 자동차감 시맨틱**(백엔드 원자 SQL).
- POST 시 `newRemainingEstimateSeconds` 미지정 → 잔여가 `max(0, remaining − timeSpent)`로 자동 차감(단 remaining이 null이면 null 유지).
- POST 시 `newRemainingEstimateSeconds` 지정 → 그 값으로 직접 설정.
- PATCH(수정)/DELETE → time_spent만 재집계, **remaining 미조정**.

## 사용자 시나리오 (Given-When-Then)

- **S1 추정 설정**: Given 추정 미설정 이슈 + UPDATE 권한, When 원 추정 `1h`, 잔여 `1h` 입력 후 저장, Then summary가 `원추정 1h / 기록 0m / 잔여 1h`로 갱신.
- **S2 작업 기록 + 자동차감**: Given 잔여 `1h`인 이슈, When `30m` 작업을 기록(자동차감 ON), Then 기록 `30m` / 잔여 `30m`로 자동 갱신 + worklog 목록에 1건 추가.
- **S3 잔여 직접 지정**: Given 잔여 `1h`, When `30m` 기록하며 "잔여 직접 지정 `2h`" 선택, Then 기록 `30m` / 잔여 `2h`.
- **S4 잔여 0 하한**: Given 잔여 `30m`, When `1h` 기록(자동차감), Then 잔여 `0m`(음수 안 됨).
- **S5 본인 worklog 수정**: Given 본인이 기록한 worklog, When 시간을 `30m`→`45m`로 수정, Then 목록 갱신 + 기록 합계 재집계.
- **S6 본인 worklog 삭제**: Given 본인 worklog 2건, When 1건 삭제, Then 목록 1건 + 기록 합계 재집계(잔여 미복원).
- **S7 타인 worklog**: Given 타인이 기록한 worklog, Then 수정/삭제 버튼 미노출(또는 disabled) — 누르면 403이므로 UI에서 사전 차단.
- **S8 권한 없음(읽기전용)**: Given VIEW만 가능한 사용자, Then summary·목록은 보이되 추가/편집/추정 저장 모두 disabled(fail-closed).
- **S9 OCC 충돌**: Given 추정 저장 중 다른 탭이 먼저 수정(version 증가), When 저장, Then 409 → 충돌 toast + 최신 재조회.

## 기능 요구사항 (FR)

- **FR1** 이슈 상세에 **시간 추적 카드**(추정 카드) 추가 — 원 추정/기록/잔여를 `"2h 30m"` 형식으로 표시. timeSpent는 읽기전용.
- **FR2** 추정 편집 — 원 추정·잔여를 시간(h)+분(m) 입력으로 편집, "저장" 시 `PATCH /issues/{key}`(3-state + OCC). 비우면 null(클리어).
- **FR3** **Worklog 섹션** — 활성 worklog 목록(작성자/시간/시작시각/코멘트), 시작시각 desc 정렬(백엔드 정렬 신뢰).
- **FR4** Worklog **추가 폼** — 시간(h+m, 필수 >0), 시작시각(datetime-local, 기본=현재), 코멘트(선택), "잔여 자동조정 / 직접 지정(h+m)" 토글. POST 후 목록·summary invalidate.
- **FR5** Worklog **수정/삭제** — 본인 worklog만 버튼 노출(authorId === 현재 사용자). PATCH/DELETE 후 invalidate.
- **FR6** 잔여 **자동계산 미리보기** — 추가 폼에서 자동차감 모드일 때 "기록 후 잔여: `max(0, 현재잔여 − 입력시간)`"을 입력값 기준 실시간 표시(현재 잔여가 null이면 미표시).
- **FR7** 권한 게이팅 — `canEdit`(UPDATE) false면 추가/수정/삭제/추정저장 disabled. `canView` 전제(상세 진입 자체가 VIEW).
- **FR8** 시간 변환/표시 유틸 — `parseHm({hours,minutes}) → seconds`, `formatSeconds(seconds) → "2h 30m" | "45m" | "0m"` 순수 함수(단위 테스트 대상).
- **FR9 (★ cross-invalidate)** Worklog 추가/수정/삭제는 이슈의 `time_spent_seconds`/`remaining_estimate_seconds`를 바꾼다. 따라서 worklog mutation 성공 후 worklog 목록 query뿐 아니라 **이슈 query(`issueQueryKey(key)`)도 invalidate**해 추정 카드가 즉시 최신화돼야 한다. 역으로 추정 PATCH 후에도 worklog summary가 동일 값을 들고 있으므로 worklog query도 invalidate(또는 추정 카드는 IssueResponse 필드만 단일 출처로 사용해 중복 신뢰 제거).
- **FR10** 작성자 표시/본인 판정 — worklog의 `authorId`(UUID)는 `useUsersByIds`로 displayName 해석(WatchersSection 선례). 현재 사용자는 `whoami userId`(WatchersSection `user?.userId` 선례)로 식별해 `authorId === 현재userId`일 때만 수정/삭제 버튼 노출.

## 비기능 요구사항 (NFR)

- **NFR1** Zod 스키마는 백엔드 DTO 1:1 미러(실측 기반). 추정 3필드 = `z.number().nullable()`(키 존재+null) / timeSpent = `z.number()`. Worklog/summary 동일.
- **NFR2** WCAG AA — 모든 입력 label/aria-label, 버튼 `min-h-[44px]`, disabled 시각 표시.
- **NFR3** 신규 npm 의존성 0(네이티브 input, 기존 sonner/react-query/zod/shadcn ui만).
- **NFR4** i18n — 모든 라벨/토스트 `@/i18n/ko`에 집약, 콜론 문장종결 금지(ko.test 자동검증 대상).
- **NFR5** mutation 후 `setQueryData` 부분응답 플리커 회피 — invalidate-only(메모리 mutation-setquerydata-partial-response-flicker).

## 데이터 모델 변경 (프론트만)

- `apps/web/src/api/issues.ts` — `issueResponseSchema`에 추정 3필드 추가, `UpdateIssueInput`에 `originalEstimateSeconds?`/`remainingEstimateSeconds?` 추가(3-state, startDate 패턴 모방).
  - **Zod 형식(G7)**: 백엔드는 NON_NULL 미적용이라 키가 항상 존재(`null` 직렬화)하므로 본질은 `.nullable()`이다. 다만 기존 issue **mock/fixture fanout 회피**를 위해 startDate 선례대로 `.nullable().optional()` 허용(plan에서 확정). timeSpent = `z.number()`.
- `apps/web/src/api/worklogs.ts` (신규) — `worklogResponseSchema`, `worklogListResponseSchema`, `worklogSummarySchema`, `fetchWorklogs/addWorklog/updateWorklog/deleteWorklog`(attachments.ts 패턴). summary 추정 필드도 `z.number().nullable()`.
- `apps/web/src/lib/duration.ts` (신규) — `parseHm`/`formatSeconds` 순수 함수.

## UI 배치 / 표시 (갭 보강)

- **배치(G4)**: 추정 카드는 **메타 영역 인근**(IssueScheduleFields와 같은 2단 grid 우측 컬럼, `border rounded-xl` 카드). Worklog 섹션은 **하단 전체폭**(AttachmentSection/IssueLinksPanel 근처).
- **시작시각 표시(G1)**: `WorklogResponse.startedAt`(ISO Instant=UTC)은 목록에서 `new Date(iso).toLocaleString('ko-KR')` 로컬 타임존 포맷.
- **시작시각 입력(E4/G10)**: `<input type="datetime-local">`, 기본값=현재 로컬시각(`yyyy-MM-ddTHH:mm`), 전송 시 `new Date(local).toISOString()`로 ISO 변환.
- **토글 시맨틱(G5)**: "잔여 자동조정"(기본) → `newRemainingEstimateSeconds` **미전송**(undefined). "직접 지정" → 입력 h+m을 초로 변환해 전송(0 포함 가능, ≥0).

## Brainstorming Check

✅ 통과 (자체 적대적 갭 분석, office-hours 부적합한 완료 FR 후속이라 self-review). 발견·반영 갭.
- **G8 (★ 반영 → FR9)**: worklog mutation이 issue 추정 요약을 바꾸므로 issue query cross-invalidate 필수. 누락 시 추정 카드가 stale(가짜 미반영). [[fr-wt-01-watchers-frontend-done]] FR-7 동형.
- **G7 (반영)**: IssueResponse 추정 필드 Zod 추가 시 기존 mock fanout 위험 → `.nullable().optional()`(startDate 선례)로 회피, plan에서 mock grep 전수([[zod-schema-strengthen-inline-mock-fanout]]).
- **G2/G3 (반영 → FR10)**: authorId→displayName=`useUsersByIds`, 현재사용자=`whoami userId`(WatchersSection 선례, [[e2e-fixture-whoami-userid-alignment]]).
- **G1/G10 (반영)**: startedAt UTC↔로컬 변환 표시/입력 명시.
- **G5 (반영)**: 자동(undefined) vs 직접지정(값, 0 포함) 토글 시맨틱 명확화.
- **G4 (반영)**: 추정 카드=메타 인근, worklog 섹션=하단 배치.
- **G9 (D7 반영)**: E2E는 MSW **stateful store**로 자동차감(remaining=max(0,r−t)) 재현해야 진짜 검증([[msw-derived-behavior-shared-store-e2e]]/[[msw-mutation-stateful-refetch]]). reload/goto 금지, SPA 내부 이동([[worktree-stale-base-rebase-and-e2e-msw-traps]]).
- Maxi 결정 필요 갭: 없음(시간형식·범위는 스펙 게이트에서 확정).

## 엣지 케이스

- **E1** summary 추정이 모두 null(미설정) — "추정 미설정" 안내, 기록만 표시.
- **E2** 자동차감 미리보기에서 현재 잔여 null — 미리보기 숨김(차감 대상 없음).
- **E3** 입력 0h 0m → 추가 버튼 disabled(백엔드 400 전 사전 차단).
- **E4** datetime-local → ISO 변환은 `new Date(local).toISOString()`(로컬→UTC). 빈 값이면 추가 차단.
- **E5** 타인 worklog 행에 수정/삭제 버튼 미노출(authorId 비교) — 백엔드 403의 UI 선차단.
- **E6** 409(OCC) 추정 저장 — 충돌 toast + invalidate(IssueScheduleFields 동일 처리).
- **E7** worklog 0건 — "기록된 작업 없음" 빈 상태.
- **E8** 큰 시간(예: 100h+) — formatSeconds가 시간 단위로 누적 표시("100h 0m"), 일/주 단위 변환 없음(백엔드 초 모델 일치).

## 제약 조건

- 백엔드 계약 변경 금지(view layer만). BC 격리 — issue-tracking 계약 소비.
- 시간 단위는 항상 초로 전송/수신, UI 경계에서만 h/m 변환.
- 정렬은 백엔드 신뢰(startedAt desc) — 프론트 재정렬 금지.

## 측정 가능한 완료 기준

- [ ] D6: 추정 카드 + Worklog 섹션 이슈 상세에 통합, 풀 CRUD + 자동계산 미리보기 동작
- [ ] `parseHm`/`formatSeconds` 단위 테스트, Zod 스키마 백엔드 1:1
- [ ] 권한 게이팅(canEdit) + 본인 worklog만 수정/삭제
- [ ] D7: E2E — 추정 설정→기록(자동차감)→수정→삭제 happy path + 권한/타인 차단
- [ ] typecheck + lint + vitest + 기존 issue E2E 무회귀
