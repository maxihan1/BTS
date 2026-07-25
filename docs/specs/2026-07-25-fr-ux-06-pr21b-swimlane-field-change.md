# FR-UX-06 Phase 5 PR21b — 칸반 보드 스윔레인 간 드래그 필드변경 — 스펙

> slug: fr-ux-06-pr21b · type: ui · agent: frontend-engineer · BC: personalization
> 작성: 2026-07-25 · 참조: [[fr-ux-06-jira-redesign-plan]] · 직전 PR21 [[fr-ux-06-pr21-dnd-kit-sortable-done]]

## 배경

PR21이 칸반 보드의 **같은 셀(컬럼 × 스윔레인 그룹) 내 순서변경(rank)** 과 **컬럼 간 상태전이**를
`@dnd-kit`로 구현했다. 현재 `board-drop.ts`의 `resolveSameColumnDrop`은 카드를 **다른 스윔레인
그룹(줄)** 으로 드롭하면 `noop`(드롭 불가)으로 처리한다. PR21b는 이 noop을 **필드변경**으로 확장해
완전한 Jira 스윔레인 드래그를 완성한다.

Jira Cloud 스윔레인 보드에서 카드를 다른 줄로 끌면 그 줄이 기준하는 필드가 바뀐다.
- ASSIGNEE 스윔레인: 다른 담당자 줄 = 담당자 재할당
- PRIORITY 스윔레인: 다른 우선순위 줄 = 우선순위 변경
- EPIC 스윔레인: 다른 에픽 줄 = 에픽 재배치

NONE 스윔레인은 그룹이 하나뿐이라 필드변경 대상이 아니다(순서변경만 = PR21).

## 사용자 시나리오 (Given-When-Then)

- **S1 담당자 재할당**: Given ASSIGNEE 스윔레인 보드에서 "김앨리스" 줄의 카드를 잡고, When "박밥" 줄의 카드 위로 드롭하면, Then 그 카드의 담당자가 박밥으로 바뀌고 박밥 줄로 이동하며 새로고침 후에도 유지된다.
- **S2 담당자 해제**: Given ASSIGNEE 스윔레인, When 담당자 있는 카드를 "미배정" 줄로 드롭하면, Then 담당자가 해제(null)된다.
- **S3 우선순위 변경**: Given PRIORITY 스윔레인, When "우선순위 3" 줄의 카드를 "우선순위 1" 줄로 드롭하면, Then 그 카드의 우선순위가 1로 바뀐다.
- **S4 에픽 재배치(에픽→에픽)**: Given EPIC 스윔레인, When "ATLAS-10" 에픽 줄의 카드를 "ATLAS-20" 에픽 줄로 드롭하면, Then 기존 에픽 연결을 해제하고 새 에픽에 연결한다(2-step).
- **S5 에픽 지정(없음→에픽)**: When "에픽 없음" 줄의 카드를 에픽 줄로 드롭하면, Then 그 에픽에 연결한다(1-step connect).
- **S6 에픽 해제(에픽→없음)**: When 에픽 줄의 카드를 "에픽 없음" 줄로 드롭하면, Then 에픽 연결을 해제한다(1-step disconnect).
- **S7 낙관적 반영·롤백**: When 드롭 즉시 카드가 대상 줄로 이동하고, 서버 실패(409/422 등) 시 원위치 복귀 + 오류 toast가 뜬다.
- **S8 같은 그룹 내 드롭**: When 같은 줄 안에서 드롭하면, Then PR21의 순서변경(rank)이 그대로 동작한다(회귀 없음).

## 기능 요구사항 (FR)

FR-UX-06 하위 화면 작업(신규 FR 없음, **FR 129 불변**). 아래는 구현 요구.

- **FR-1** `board-drop.ts` `resolveSameColumnDrop`을 확장: active와 over가 **다른 스윔레인 그룹**이면
  `noop` 대신 `field-change` 액션(대상 그룹 필드값 포함)을 반환한다. 같은 그룹은 기존 reorder 유지.
- **FR-2** 대상 필드값은 **드롭 대상 그룹의 대표 카드**에서 읽는다(그룹 key 이름 역산 불필요).
  - ASSIGNEE: `assignee-named-*`/`assignee-unknown` → 대표 카드 `assigneeId`(UUID) · `assignee-unassigned` → `null`
  - PRIORITY: `priority-{p}` → 대표 카드 `priority`(숫자)
  - EPIC: `epic-{epicKey}` → 대표 카드 `epicKey` · `epic-no-epic` → `null`
- **FR-3** 담당자 재할당: `changeAssignee(key, { assigneeId, expectedVersion })`.
- **FR-4** 우선순위 변경: `updateIssue(key, { priority, expectedVersion })`.
- **FR-5** 에픽 재배치: 현재 `epicKey`(from)와 대상 `epicKey`(to)에 따라
  - `null → epic`: `connectEpicChild(toEpic, key)`
  - `epic → null`: `disconnectEpicChild(fromEpic, key)`
  - `epicA → epicB`: `disconnectEpicChild(fromEpic, key)` **후** `connectEpicChild(toEpic, key)` (순차)
- **FR-6** 세 필드변경 모두 **낙관적 업데이트**: board 캐시 즉시 갱신 → 성공 시 유지 → 실패 시 롤백 + toast.
  필터 인식 boardKeys(PR21 useReorderCard 선례)와 동일 키 사용.
- **FR-7** 스크린리더 announcements(DR2 선례)에 필드변경 문구 추가(한국어).

## 비기능 요구사항 (NFR)

- **NFR-1** issue-tracking·agile-planning 백엔드 변경 0(모든 API·필드 실재). 마이그레이션 0.
- **NFR-2** PR21 순서변경/상태전이 회귀 0(같은 그룹 드롭·컬럼 간 이동 불변).
- **NFR-3** OCC(낙관적 잠금) — 담당자/우선순위는 board 카드 `version`을 expectedVersion으로 전송. 409 시 롤백.
- **NFR-4** 접근성 — 드래그 announcements 유지, 드롭 가능 커서 표시.

## API 인터페이스 (모두 실재 — 신규 0)

| 동작 | 함수 | 메서드/경로 | OCC |
|---|---|---|---|
| 담당자 | `changeAssignee(key, {assigneeId, expectedVersion})` | PATCH /issues/{key}/assignee | ✅ version |
| 우선순위 | `updateIssue(key, {priority, expectedVersion})` | PATCH /issues/{key} | ✅ version |
| 에픽 연결 | `connectEpicChild(epicKey, childKey)` | POST /issues/{epicKey}/epic-children | ❌ (링크테이블) |
| 에픽 해제 | `disconnectEpicChild(epicKey, childKey)` | DELETE /issues/{epicKey}/epic-children/{childKey} | ❌ |

## 데이터 모델 변경

없음. `BoardCard`가 이미 `assigneeId`(UUID nullable) · `priority`(int) · `epicKey`(string nullable) · `version` 노출.

## 엣지 케이스

- **E1 에픽 2-step 부분 실패**: disconnect(A) 성공 후 connect(B) 실패 → 이슈가 "에픽 없음"으로 남음.
  → best-effort 복구(재connect A) + 실패 toast + board refetch(정합성 서버 기준). **[결정 D1]**
- **E2 담당자 unknown 그룹**: 이름 미확인(unknown)이지만 `assigneeId`는 존재 → 대표 카드 assigneeId 사용. 정상 재할당.
- **E3 same-value 드롭**: 대상 그룹 필드값 == 현재 카드 필드값 → noop(변경 없음). 예: 우선순위 3 카드를 우선순위 3 줄로.
- **E4 컬럼 배경 드롭(overIssueKey undefined)**: 그룹 판정 불가 → 현재 그룹 유지(reorder 맨뒤), 필드변경 아님(PR21 동작 유지).
- **E5 빈 그룹**: 빈 스윔레인 그룹은 렌더 생략(swimlane-group.ts) → 드롭 대상이 될 수 없음. 대표 카드 항상 존재.
- **E6 필터 활성 중 필드변경**: 낙관적 갱신 후 카드가 필터에서 벗어날 수 있음 → 서버 refetch가 정리(NFR-3 롤백/invalidate).
- **E7 컬럼 간 이동 + 다른 그룹 동시**: 다른 컬럼 드롭은 항상 move/needs-resolution(PR21) 우선. 필드변경은 **같은 컬럼·다른 그룹**만.

## 제약 조건

- 새 API·백엔드 변경 금지(모두 실재 함수 재사용).
- NONE 스윔레인은 필드변경 없음(그룹 1개).
- 에픽 이동은 반드시 2-step(백엔드 `linkEpic`이 `epic_id IS NULL` 조건부라 교체 불가·409).

## 측정 가능한 완료 기준

- E2E: S1~S8 시나리오 재현(ASSIGNEE·PRIORITY·EPIC 각 1 이상 + 롤백 1).
- 단위: `resolveSameColumnDrop` 필드변경 분기·대표 카드 값 읽기·2-step 에픽·same-value noop.
- typecheck 0 · eslint 0 error · vitest green · build 0 · verify-master-plan 129/129.
- PR21 회귀 0(순서변경·상태전이 E2E 유지).

## 결정 포인트 (게이트 1 Maxi 확인)

- **D1 에픽 2-step 부분 실패 처리**: (a) best-effort 재connect 롤백 + toast + refetch[권장] / (b) toast만 + refetch(복구 시도 안 함) / (c) 2-step 대신 백엔드에 원자적 reparent API 신설 요청(스코프 확대).
- **D2 same-value 드롭(E3)**: noop 처리[권장·자명] 확인.
