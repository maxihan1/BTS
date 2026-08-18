# FR-UX-11 F9 — 이슈 목록 셀 인라인 편집 스펙

> FR ID. **FR-UX-11** (§4.9 `docs/plan/product/personalization.md`)
> 승계 PR. F8(#337) → **F9(이번)** → FR-UX-11 D6/D7 `[x]`
> type `ui` · agent `frontend-engineer` · BC `issue-tracking`(논리 personalization)

이슈 목록(`/issues`)에서 **담당자·우선순위·상태** 셀을 그 자리에서 바꾼다. 지금은 이슈를
열어야만 바꿀 수 있다.

## 착수 전 실측 — 정본 정정 2건

계약 §5(착수 전 사전 grep)를 수행한 결과 정본 서술 2건이 실측과 어긋났다. **원문을 교체한다**
(정정 노트가 아니라 원문 교체 — F3 #333 · F8 #337 선례).

| 정본 서술 | 실측 | 조치 |
|---|---|---|
| `personalization.md:325` *"`components/ui/popover.tsx`(소비처 **0→1**)"* | `ProjectSwitcher.tsx:9` 가 이미 소비 중. 실제로는 **1→2** | §4.9 원문 정정 |
| `classify-task.ts` 출력 `type=backend` | 대상 파일 전량 `apps/web`. 백엔드 0줄 | plan Brief 에 override 근거 기록 (완료) |

## Jira 대조 (계약 §1)

**1. 대응 화면.** Jira Cloud 의 **이슈 네비게이터 리스트 뷰** — 이슈를 표로 늘어놓고 행의
필드를 그 자리에서 바꾸는 화면. BTS `/issues` 가 같은 일을 한다.

**2. 조작감 갭.**

| 조작 | Jira Cloud | BTS 현재 | 갭 |
|---|---|---|---|
| 담당자 변경 | 셀에서 바로 | 이슈를 열어야 함 | **★F9 대상** |
| 우선순위 변경 | 셀에서 바로 | 이슈를 열어야 함 | **★F9 대상** |
| 상태 변경 | 셀 드롭다운에서 전환 선택 | 이슈를 열어야 함 | **★F9 대상** |
| 편집 가능 표시 | hover 시 셀 테두리/어포던스 | 없음 | **★F9 대상** |
| 행 클릭 | 이슈 키·요약만 링크. 행 전체는 아님 | **행 전체가 클릭 → 상세 이동** | ⚠️ 아래 D-1 |

**3. BTS 제약과 교차.** 갭 해소안이 §2 즉사 계약을 건드리는 지점은 **상태 배지**다 —
`issue-columns.ts:103` `renderStatusCell` 이 `role="status"` 를 달고 있고 e2e 가 이를 셀렉터로
쓴다. 편집 가능하게 바꾸면서 이 role 을 잃으면 즉사한다 → **FR9 로 보존을 못박는다.**

**4. Jira 를 그대로 베끼지 않는 지점.** Jira 는 행 전체가 클릭 대상이 아니지만 BTS 는 맞다
(`IssueTable.tsx:214`). 행 클릭을 제거하면 **F10(#336)이 확정한 커서 = `selectedKey` 모델**
(커서 이동이 곧 상세 교체)과 기존 e2e 가 함께 깨진다. 따라서 **행 클릭을 유지한 채 편집
가능 셀만 전파를 끊는다** — 이미 체크박스(`:222`)와 키 링크(`issue-columns.ts:81`)가 쓰는
검증된 패턴이다.

## 확정 결정 (D)

### D-1. 진입 = hover 어포던스 + 셀 클릭 **(Maxi 확정 2026-08-04)**

편집 가능 셀에 마우스를 올리면 어포던스(테두리 + 커서)가 나타나고, 클릭하면 popover 가 열려
그 자리에서 편집한다. 그 외 영역 클릭은 기존대로 상세 이동.

기각안. **① 어포던스 없는 셀 클릭** — 행을 열려던 클릭이 편집을 열고, 편집 가능하다는 사실이
발견되지 않는다. **② 편집 모드 토글** — Jira 에 대응이 없는 자체 발명이라 계약 §1-4 위반이고
조작이 한 단계 는다.

### D-2. 3종 모두 popover 로 통일

담당자는 검색이, 상태는 가용 전환 목록이 필요해 셀 안에 직접 못 넣는다. 우선순위만 `<select>`
로도 되지만 **3종의 진입·이탈 조작을 다르게 만들 이유가 없어** popover 로 통일한다.
`components/ui/popover.tsx` 소비 (1→2).

### D-3. 전환·권한은 **셀을 열 때만** 조회 — N+1 회피가 백엔드 0줄의 근거

행마다 필요한 조회가 둘이다.

```
GET /api/v1/issues/{key}/transitions                     가용 전환 (행마다 다름)
GET /api/v1/users/me/issue-permissions?issueKey={key}    UPDATE / TRANSITION (행마다 다름)
```

목록 20행에서 미리 다 부르면 40 요청이다. **열린 셀 1개에 대해서만** 조회하면 최대 2 요청이고
`useIssuePermissions` 는 `staleTime: 30_000` 이라 같은 행 재진입은 캐시된다.

기각안. **목록 응답에 전환·권한을 실어 보내기** — 백엔드 변경이라 범위 밖(F8 선례 0줄).
**`POST /issues/bulk-transitions/available` 재사용** — 반환이 *"모든 대상 이슈에 공통으로
존재하는 전환 **교집합**"*(`api/issues.ts:243`)이라 행별 편집에 쓰면 실제 가능한 전환을
**빠뜨린다**. 일괄 전환 전용이다.

**필드 권한은 조회 자체가 불필요하다 (2026-08-04 실측 정정).**
초안은 *"`useFieldPermissions(projectKey)` 로 목록당 1회"* 라고 적었으나 **그것조차 필요 없다.**
백엔드가 목록 응답의 **행마다** `noneditableFields` 를 채워 보내기 때문이다
(`IssueFieldVisibilityTest.kt:455` *"listIssues — noneditableFields (목록 경로)"* 가 봉인).
따라서 셀은 `issue.noneditableFields` 를 그대로 읽고 상세 화면과 같은 판정 함수
`isFieldDisabled(fieldKey, canEdit, noneditableFields)`(`IssueMetaPanel.tsx:143`)를 재사용한다 —
**추가 요청 0**. 판정식은 이슈 단위 `UPDATE` **AND** 필드 단위 편집가부다.

적용 필드. 담당자 = `assigneeId` · 우선순위 = `priority`.
**상태(전환)는 대응 필드 키가 없다** — 전환은 `TRANSITION` 권한으로만 통제되고 상세 화면
(`IssueStateTransition`)도 필드 권한을 보지 않는다. 목록도 같게 둔다.

**★두 술어는 짝이다 — `isFieldDisabled` 만으로는 절반만 막힌다 (2026-08-04 재리뷰).**
백엔드 `buildNoneditableKeys` 가 `.filter { key -> key !in restrictedSet }` 로 두 목록을
**배타적**으로 만든다 — **열람 숨김 키는 `noneditableFields` 에 절대 들어오지 않는다.**
따라서 `isFieldDisabled('assigneeId', true, [])` 는 `false` 를 내고 셀이 완전히 열린다.
`isFieldHidden(fieldKey, restrictedFields)`(`IssueMetaPanel.tsx:127`)를 **함께** 봐야 한다.

**열람 숨김 시 목록 동작 (Maxi 판단 대기 없이 확정 — 근거 아래).**
담당자 셀은 **편집 트리거를 아예 걸지 않고** 값 대신 짧은 표기(`비공개`)를 보이며,
사유 전문은 `issueDetailStrings.descriptionRestricted` 를 `title` 로 단다.
- **거짓 표시 제거가 1순위.** 백엔드가 열람 불가 `assigneeId` 를 **null 로 마스킹**하므로
  그대로 그리면 담당자가 **있는데 「미배정」** 이라고 말한다.
- **트리거를 남기지 않는 이유.** 남기면 접근성 이름이 *"…담당자 변경"* 이라고 **할 수 없는
  일을 약속**하고, 눌러서야 못 한다는 걸 알게 된다.
- **열 정렬 유지.** 상세는 섹션을 통째로 숨기지만 표는 셀을 비우면 열이 어긋난다 —
  같은 `<span>` 을 유지하고 내용만 바꾼다. 문장 전문을 화면에 쓰지 않는 이유도 `w-36` 열
  폭이다.

**우선순위는 해당 없음 (실측).** `IssueResponse.kt:142` *"non-null CORE(summary·priority):
마스킹 대상 아님 — 항상 노출"*. `masked += "priority"` 가 코드 어디에도 없다 ⇒ `priority` 는
`restrictedFields` 에 들어올 수 없다.

### D-4. 캐시 = 낙관적 필드 patch + `onSettled` invalidate

`useChangeCardField`(보드)가 이미 쓰는 패턴을 승계한다 — `onMutate` 낙관 patch → `onError`
스냅샷 롤백 + toast → `onSuccess` 서버 값 반영 → `onSettled` invalidate.

`learnings.md:616`(메타 mutation `setQueryData` 플리커)과 **모순되지 않는다.** 그 교훈의 대상은
*"PATCH 응답(부분 뷰)으로 캐시를 **통째 교체**"* 였다. 여기서는 **변경 필드만 patch** 하므로
`descriptionHtml` 류의 파생 필드를 덮지 않는다. 이슈 상세가 `invalidate-only` 인 것도 같은
이유(그쪽은 전체 교체를 하려다 문제가 됐다).

목록 캐시 키는 `['issues', projectKey, page, normalizedFilter, sort]`(filter-aware)다.

### D-5. `issue-columns.ts` 의 순수 함수 계약을 **유지**한다

이 파일은 *"훅을 직접 호출하지 않는 순수 함수 모음"*(`:24`)이고 `.ts` 라 JSX 를 못 쓴다.
편집 셀은 상태·훅이 필요하지만 **컬럼 정의를 오염시키지 않는다** — 편집 셀을 별도 `.tsx`
컴포넌트로 만들고 컬럼의 `render` 는 `createElement(EditableXCell, {...})` 로 위임한다.
훅은 그 컴포넌트가 소유한다.

기각안. `issue-columns.tsx` 로 확장자 변경 후 훅 직접 사용 — 순수 계약 파기.

### D-6. 권한 미확정 시 **fail-closed**

hover 어포던스는 「편집 가능한 **종류**의 칸」임을 알릴 뿐이고, 실제 가부는 popover 를 연 뒤
판정한다. 권한 조회 중·실패·거부 시 컨트롤을 `disabled` 로 두고 사유를 보인다 —
`IssueAssigneeSelect` 의 기존 `canEdit` 계약(`:35` *"fail-closed: 권한 미확정 시 false 전달 권장"*)
을 그대로 승계한다.

## 사용자 시나리오 (Given-When-Then)

**S1. 담당자 변경.**
Given 이슈 목록에 `ATLAS-1`(담당자 `미배정`)이 보이고 나는 UPDATE 권한이 있다
When 담당자 셀에 마우스를 올리면 편집 가능 어포던스가 뜨고, 클릭하면 popover 가 열린다
And 검색창에 이름을 치고 후보를 고른다
Then popover 가 닫히고 셀이 즉시 새 담당자로 바뀐다 (낙관적) — 서버 확정 후에도 같은 값

**S2. 우선순위 변경.**
Given 이슈 목록에 `ATLAS-1`(우선순위 `보통`)이 보인다
When 우선순위 셀을 클릭하고 `높음` 을 고른다
Then popover 가 닫히고 셀이 `높음` 으로 바뀐다

**S3. 상태 전환.**
Given `ATLAS-1` 이 `할 일` 이고 나는 TRANSITION 권한이 있다
When 상태 셀을 클릭한다
Then 그 이슈에서 **지금 갈 수 있는 전환만** 목록에 뜬다
When `진행 중` 을 고른다 Then 셀 배지가 `진행 중` 으로 바뀐다

**S4. 행 이동은 그대로.**
Given 목록이 보인다 When 요약 셀(편집 대상 아님)이나 행 여백을 클릭한다
Then 기존대로 상세로 이동한다 — 편집이 열리지 않는다

**S5. 권한 없음.**
Given 나는 `ATLAS-1` 에 UPDATE 권한이 없다
When 담당자 셀을 클릭한다
Then popover 는 열리되 컨트롤이 비활성이고 편집 불가 사유가 보인다

**S6. 취소.**
Given 담당자 popover 가 열려 있다 When `Esc` 를 누르거나 바깥을 클릭한다
Then 아무 변경 없이 닫히고 **상세로 이동하지 않는다**

**S7. 저장 실패(OCC 409).**
Given 다른 사람이 먼저 같은 이슈를 바꿔 내 `version` 이 낡았다
When 우선순위를 바꾼다
Then 셀이 원래 값으로 되돌아가고 실패 안내 toast 가 뜬다

## 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| **FR1** | 담당자·우선순위·상태 3개 셀이 편집 가능하다. 나머지 셀(키·요약·수정일)은 **변경 없음** |
| **FR2** | 편집 가능 셀은 hover 시 어포던스(테두리 + `cursor-pointer`)를 노출한다 (D-1) |
| **FR3** | 편집 가능 셀 클릭은 popover 를 열고, **행 클릭(상세 이동)으로 전파되지 않는다** (`stopPropagation`) |
| **FR4** | popover 는 `Esc`·바깥 클릭으로 닫히고, 닫힘이 상세 이동을 유발하지 않는다 |
| **FR5** | 담당자는 사용자 검색 + 선택 + 해제(`null`)를 지원한다. `changeAssignee(key, {assigneeId, expectedVersion})` |
| **FR6** | 우선순위는 1~5 를 지원한다. `updateIssue(key, {priority, expectedVersion})` |
| **FR7** | 상태는 **그 이슈의 가용 전환만** 노출한다. `POST /issues/{key}/transition {toStatusKey, expectedVersion}` |
| **FR8** | 가용 전환 0건이면 사유를 구분해 안내한다 — **422(워크플로우 미설정)** vs **200+빈 배열(종료 상태)**. `issues.$key.tsx:55` `resolveTransitionUnavailableReason` 을 공용으로 올려 재사용 (복제 금지) |
| **FR14** | **종료 전환(`toCategory === 'DONE'`)는 해결 결과를 먼저 받는다** — 기존 `ResolutionModal` 을 재사용하고 `resolutionId` 를 실어 전환한다 (**Maxi 확정 2026-08-04**) |
| **FR15** | 전환 실패를 사유별로 안내한다 — 409 `TRANSITION_NOT_ALLOWED` / 409 `VERSION_CONFLICT`(재조회 유도) / 422(워크플로우 미설정). `issues.$key.tsx:359-378` 의 분기를 승계한다 |
| **FR9** | **상태 셀의 `role="status"` 를 보존한다** (§2 즉사 계약). 편집 트리거를 씌우되 배지의 role 은 유지 |
| **FR10** | 권한 미확정·거부 시 컨트롤 `disabled` + 사유 표시 (fail-closed, D-6) |
| **FR11** | 저장은 낙관적 반영 후 실패 시 롤백 + toast (D-4) |
| **FR12** | 전환·권한 조회는 **셀이 열린 이슈에 대해서만** 발생한다 (D-3) |
| **FR13** | 기존 e2e 셀렉터 계약을 보존한다 — `select-{key}` · `select-all-page` · `issue-summary-{key}` · 키 링크 `aria-label={key}` |

## 비기능 요구사항 (NFR)

| ID | 요구사항 | 측정 |
|---|---|---|
| **NFR1** | 목록 초기 렌더에 **추가 네트워크 요청 0** | 목록 진입 시 `/transitions`·`/issue-permissions` 호출 0건 |
| **NFR2** | 셀 1개 열기당 요청 ≤2 (전환 + 권한). 같은 행 재진입은 캐시 | `staleTime: 30_000` |
| **NFR3** | 저장 중 컨트롤 비활성 — 중복 제출 차단 | `isPending` → `disabled` |
| **NFR4** | 키보드 접근 — 셀 트리거가 포커스 가능하고 `Enter`/`Space` 로 열린다 | WCAG AA |
| **NFR5** | 라이트/다크 양쪽에서 어포던스가 보인다. 하드코딩 색 0 (ADS 토큰만) | 눈확인 + 토큰 검사 |
| **NFR6** | 백엔드 0줄 · 마이그레이션 0 · 신규 의존성 0 | `git diff --exit-code main -- backend/` EXIT 0 |

## API 인터페이스 (REST) — 전부 기존, 신규 0

```
GET  /api/v1/issues/{key}/transitions                     가용 전환 조회 (셀 열 때)
POST /api/v1/issues/{key}/transition                      { toStatusKey, expectedVersion }
PATCH(updateIssue) /api/v1/issues/{key}                    { priority, expectedVersion }
POST(changeAssignee) 담당자 변경                            { assigneeId|null, expectedVersion }
GET  /api/v1/users/me/issue-permissions?issueKey={key}     { UPDATE, SOFT_DELETE, TRANSITION }
```

## 데이터 모델 변경

**없음.** 기존 컬럼만 소비. 마이그레이션 0.

## 엣지 케이스

| ID | 상황 | 처리 |
|---|---|---|
| **E1** | 셀 클릭이 행 클릭으로 전파 | `stopPropagation` (체크박스 `IssueTable.tsx:222` 선례) |
| **E2** | popover 를 `Esc` 로 닫을 때 행 이동 유발 | 닫힘 경로에서 전파 차단 — **F8 의 pane 이중 발화(E1)와 동형 위험** |
| **E3** | 목록 캐시의 `version` 이 낡아 409 | 롤백 + toast + invalidate 로 최신 `version` 재수급 |
| **E4** | 가용 전환 0건 (워크플로우 미설정) | `no-workflow` 안내 (FR8) |
| **E5** | 가용 전환 0건 (종료 상태) | `terminal` 안내 (FR8) |
| **E6** | 담당자 검색 결과 0건 | 빈 상태 안내. 해제는 여전히 가능 |
| **E7** | 권한 조회 실패(네트워크) | fail-closed — `disabled` (D-6) |
| **E8** | 저장 중 같은 셀 재클릭 | `isPending` 비활성 (NFR3) |
| **E9** | 편집 중 목록이 refetch 되어 행 순서/내용 변동 | popover 는 열린 이슈 키에 묶인다. 행이 사라지면 닫는다 |
| **E10** | 편집 중 커서(`?selected=`) 이동 단축키(`j`/`k`) 입력 | popover 포커스 안에서는 목록 단축키가 발동하지 않아야 한다 — F10 `shouldIgnoreEvent` 가드 확인 |
| **E11** | 좁은 폭(모바일) | 셀 폭이 좁아 어포던스가 겹치지 않는지 확인. F10 은 커서 단축키를 **와이드 전용**으로 확정했다 |
| **E12** | 체크박스(일괄 선택)와 동시 사용 | bulk 선택은 별개 개념 — 편집이 선택을 바꾸지 않는다 (`IssueTable.tsx:159` 경고 준수) |
| **E13** | 같은 행의 다른 셀을 연속 클릭 | 이전 popover 는 바깥 클릭으로 닫히고 새 popover 가 열린다 |
| **E14** | **종료 전환 선택** | popover 를 닫고 `ResolutionModal` 을 연다. 확인 시 `resolutionId` 를 실어 전환, 취소 시 상태 불변 (FR14) |
| **E15** | 결의안 모달이 열린 채 목록이 refetch | 모달은 선택된 전환 항목에 묶인다. 확인 시 `expectedVersion` 이 낡았으면 409 → FR15 경로 |
| **E16** | 전환 목록 조회가 **422** | 워크플로우 미설정 안내. 200+빈 배열(종료 상태)과 **다른 문구** (FR8) |

## 제약 조건

- **프론트 전용.** 백엔드 0줄 (NFR6). BC 격리 — `apps/web` 만 수정
- **§2 즉사 계약** — `role="status"`(FR9)·기존 testid(FR13) 보존
- **F10 자산 무손상** — `shortcuts.test.ts` `toHaveLength(5)` 무수정 green 유지. 신규 키 0
- **`issue-columns.ts` 순수 계약 유지** (D-5)
- **시각 검증 트랙** — red-first 면제 대상이나 **기존 E2E 동반 실행 + 브라우저 눈확인 필수**

## 시각 검증 기준 (ui 경량 경로 필수 — brainstorming 대체)

**동반 실행할 기존 E2E.** 목록 화면을 방문하는 spec 18개를 실측했다. 최소 아래를 함께 돌린다.

```
issue-table · issue-split-view · issue-filter · issue-bulk-operations
context-shortcuts · issue-crud-happy · keyboard-shortcuts · active-project
```

**브라우저 눈확인 항목 (계약 §6 — 생략 금지).**

1. 라이트/다크 양쪽에서 hover 어포던스가 보이는가 (NFR5)
2. 3종 popover 가 각각 열리고 값이 바뀌는가
3. 편집 가능 셀을 눌렀을 때 **상세가 열리지 않는가** (FR3)
4. 편집 대상이 아닌 셀·여백을 눌렀을 때 **기존대로 상세가 열리는가** (S4)
5. `Esc` 로 닫을 때 상세로 튀지 않는가 (E2)
6. 좁은 폭에서 레이아웃이 깨지지 않는가 (E11)
7. 드래그로 셀 텍스트 복사가 되는가 — **F8 이 `<button>` 감싸기로 복사를 죽인 회귀**
   ([[button-user-select-auto-is-none]]). 계산값이 아니라 실제 드래그로 확인한다

## 측정 가능한 완료 기준

- [ ] FR1~FR15 각각에 **비-공허 가드**가 있다 (뮤테이션으로 확인 — 코드를 지우면 빨강)
- [ ] 신규 E2E 가 S1~S7 을 덮는다
- [ ] 위 기존 E2E 8종 동반 통과
- [ ] `git diff --exit-code main -- backend/` **EXIT 0**
- [ ] `tsc --noEmit` · `eslint` · 유닛 전량 green
- [ ] 브라우저 눈확인 7항목 결과를 PR 본문에 기록
- [ ] `shortcuts.test.ts` `toHaveLength(5)` **무수정** green
- [ ] 정본 정정 2건 반영 (`popover` 소비처 · §4.9 D6/D7 `[x]` 전환)
- [ ] pre-commit 훅 봉합 3건 (범위 포함 — Maxi 확정 2026-08-04)

## Brainstorming Check

**ui 경량 경로로 스킵** (Maxi 확정 2026-08-03). 대신 위 `## Jira 대조`(계약 §1 4단계) +
§2 즉사 계약 교차 + `## 시각 검증 기준`이 sanity check 를 대신한다.
