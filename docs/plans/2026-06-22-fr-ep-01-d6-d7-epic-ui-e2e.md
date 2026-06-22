# FR-EP-01 D6/D7 — 에픽 페이지 + 자식 이슈 연결 UI + 보드 EPIC 스윔레인

> slug: fr-ep-01-d6-d7-epic-ui-e2e
> type: ui
> agent: frontend-engineer
> primary_bc: agile-planning (보드) + issue-tracking (에픽/자식 연결 — 백엔드 계약)
> 생성: 2026-06-22

## Brief

FR-EP-01(에픽 이슈 타입 + 자식 이슈 연결)의 D6/D7 프론트 구현. 백엔드 D1~D5는 PR #174로 완료됨.

작업 범위.
1. 에픽 페이지 + 자식 이슈 목록 UI
2. 에픽 연결/해제 UI
3. 보드 EPIC 스윔레인 enum 추가 (FR-BD-03 #173에서 이연됨)
4. changelog 라벨 "에픽" (field="epic")

확정된 백엔드 계약 (#174).
- `POST /api/v1/issues/{epicKey}/epic-children` — 자식 연결
- `DELETE /api/v1/issues/{epicKey}/epic-children` — 연결 해제
- `GET /api/v1/issues/{epicKey}/epic-children` — 자식 목록
- `IssueResponse.epic` — {key, summary} 단건 (parent 동형 self-join, 단건 GET만 채움)
- changelog `field="epic"` — 한글 라벨 "에픽" 매핑 필요
- 보드 스윔레인 EPIC: BoardCardResponse.epic view-layer patch 필요 가능성 (PRIORITY 스윔레인 #173 옵션C 선례)

완료 시 FR-EP-01 전체 [x] 마킹 (69/123 → 70/123).

## 도메인 정리

- **BC**: agile-planning(보드 EPIC 스윔레인) + issue-tracking(에픽/자식 연결 — 백엔드 계약 소유). 프론트는 단일 SPA라 BC 격리는 백엔드만 적용. view-layer 소비 작업.
- **영향 엔티티(프론트 관점)**: IssueResponse.epic, BoardCardResponse(EPIC 스윔레인용 epic 필드 view-layer patch 가능성). 신규 도메인 엔티티 0 — 백엔드 #174에서 확정.
- **새 용어**: "에픽"(Epic) — glossary 이미 등재(이슈 타입 hierarchy_level=1, agile-planning 묶음 단위). "스윔레인"은 glossary 미등재이나 FR-BD-03(#173)에서 코드 정착 → 이번엔 EPIC 옵션만 추가(glossary 추가는 후속/Maxi 승인 영역).
- **기존 결정 충돌**: 없음. ADR [2026-06-22-fr-ep-01-epic-child-link.md](../decisions/2026-06-22-fr-ep-01-epic-child-link.md)가 도메인 계약 확정, 프론트는 소비만.
- **프론트가 지켜야 할 백엔드 계약(ADR + #174 확정)**:
  - `IssueResponse.epic` = {key, summary} 단건. **단건 GET(findByKeyWithType)만 채워지고 목록 응답은 null**(parent 동형 self-join). → 에픽 표시는 단건 상세 화면에서만.
  - `GET /api/v1/issues/{epicKey}/epic-children` — 자식 목록(BROWSE + accessibleLevels, 백엔드가 누출 차단).
  - `POST /api/v1/issues/{epicKey}/epic-children` — 자식 연결(자식 UPDATE 권한).
  - `DELETE /api/v1/issues/{epicKey}/epic-children` — 연결 해제(자식 UPDATE 권한).
  - 불변식: 자식=hierarchy_level 0(story/task/bug), 대상=Epic(level 1), 동일 프로젝트, 단일 Epic(이미 소속 409), 자기참조 금지. 위반 시 백엔드 4xx → 프론트는 에러 토스트.
  - changelog `field="epic"` → 한글 라벨 "에픽" (changelog-labels 매핑 추가).
  - 보드 EPIC 스윔레인: `BoardCardResponse.epic` view-layer patch 필요 가능성(PRIORITY 스윔레인 #173 옵션C, same-BC view-layer 선례 PR #13). spec 단계에서 확정.
- **관련 ADR**: [2026-06-22-fr-ep-01-epic-child-link.md](../decisions/2026-06-22-fr-ep-01-epic-child-link.md) (#174 생성), [2026-06-13-issue-link-vs-parent-child-separation](../decisions/2026-06-13-issue-link-vs-parent-child-separation.md)

## 스펙

전체 스펙. [docs/specs/2026-06-22-fr-ep-01-d6-d7-epic-ui-e2e.md](../specs/2026-06-22-fr-ep-01-d6-d7-epic-ui-e2e.md)

### Maxi 게이트 결정 (2026-06-22)
- 에픽 UI = 기존 이슈 상세 재사용(별도 라우트 없음). 에픽 상세 = 자식 목록 섹션, 자식 상세 = 소속 에픽 표시+지정/해제(parent ParentSection 미러).
- 보드 EPIC 스윔레인 = 이 PR 포함. 백엔드 BoardCardResponse.epicKey view-layer patch(PR #13 옵션C 동형) + 프론트 enum 'EPIC'.

### 핵심 시나리오
- 에픽 상세에서 자식 추가/해제(POST/DELETE epic-children, key=에픽).
- 자식 상세에서 소속 에픽(IssueResponse.epic) 표시 + 지정/해제(parent 동형).
- 4xx errorCode(409 이미연결/422 4종/404/403/400) 한국어 처리, 캐시 무변경.
- changelog field="epic" → "에픽" 라벨.
- 보드 스윔레인 "에픽" 선택 시 컬럼 내 에픽별 레인 그룹(드래그 회귀 0).

### 백엔드 계약(소비, #174 확정)
- `POST /epic-children {childKey}` 201 · `DELETE /epic-children/{childKey}` 204 · `GET /epic-children` 200
- EpicChildSummaryResponse = {key, summary, typeKey?, currentStateKey} · IssueResponse.epic = {key, summary}|null(단건만)

## Brainstorming Check

✅ 통과 (직접 sanity check, 1회). gap 3건 plan으로 인계.
- G1. 에픽 상세 자식 추가 버튼 권한 게이팅 기준 → plan에서 결정.
- G2. changelog "epic" 값 표시 형식 → plan task로 백엔드 detector 확인.
- G3. 보드 스윔레인 백엔드 view-layer patch = backend-engineer task(cross-BC) → plan에서 agent 지정.

## Plan

### gap 결정 (게이트1 검토 대상)
- **G1**: 에픽 상세 "자식 추가/해제" 버튼 = 에픽 이슈의 `canEdit`(현재 보는 에픽 편집 권한)으로 게이팅 + 백엔드 403 안전망(자식 UPDATE는 백엔드 검증). 자식 상세 "에픽 지정/해제" = 현재 이슈 `canEdit`(parent 동형, 정확).
- **G2**: changelog epic 값을 사람이 읽게 — 백엔드가 epic UUID → **epic key** 로 resolve(T2). detector 기록(`epicId.toString()`)이 raw UUID라 그대로면 부적합. backend-engineer가 #174 구조 보고 detector 기록 정정 vs 조회 서비스 resolve 선택. 프론트는 값 raw 표시(키가 의미 전달).
- **G3**: 보드 스윔레인 epicKey = self-join 필요한 cross-BC view-layer → backend-engineer task(T1) 별도.

### 파일 경로 참조 (Explore 확인)
- BoardCardResponse: `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt:147-165`
- BoardIssueView/Port: `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/board/BoardIssueLookupPort.kt:127-134`
- listVisibleForBoard SQL: `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt:691-719` (epic self-join 선례: 520-589, EPIC_ALIAS 상수 931-937)
- BoardIssueLookupAdapter: `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupAdapter.kt:96-104`
- IssueChangeDetector epic: `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/IssueChangeDetector.kt:70`
- IssueResponse.epic Zod: `apps/web/src/api/issues.ts:138-141`(parent 동형), SwimlaneSelector `components/board/SwimlaneSelector.tsx`, swimlane enum `api/boards.ts:81`, boardCardSchema `api/boards.ts:34-45`, board-labels `lib/board-labels.ts`, changelog-labels `lib/changelog-labels.ts:139-223` + i18n `i18n/ko.ts`, ParentSection 미러 `components/issue/IssueLinksPanel.tsx:208-325`, 섹션 패턴 `components/issue/WatchersSection.tsx`

---

### Task 1. (backend) 보드 카드 epicKey view-layer 노출 (cross-BC)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/board/BoardIssueLookupPort.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupAdapter.kt`, 관련 test (BoardIssueLookupAdapter/Repository listVisibleForBoard 통합테스트, agile-planning Board 통합테스트 — epicKey 단언 추가)]
- depends-on: []

**RED**: IssueRepository listVisibleForBoard 통합테스트 — epic 연결된 카드의 `epicKey`가 노출되고 cross-project epic은 노출 안 됨(동일프로젝트 필터). BoardIssueView.epicKey 미존재로 컴파일 실패 → RED.

**GREEN**: BoardIssueView에 `epicKey: String?` 추가 → listVisibleForBoard SQL에 `epicAlias` LEFT JOIN(`EPIC_ID.eq(epicAlias.ID).and(epicAlias.DELETED_AT.isNull).and(epicAlias.PROJECT_ID.eq(ISSUES.PROJECT_ID))`) + EPIC_KEY_ALIAS select(기존 상수 재사용) → adapter 매핑 epicKey 전달 → BoardCardResponse에 `epicKey: String?` + from() 매핑. PRIORITY 재노출 #173 옵션C 패턴 미러 + self-join.

**REFACTOR**: KDoc(epicKey 동일프로젝트 필터 사유, P1-A 누출 회귀방지 인용). nullable 안전.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test :modules:agile-planning:test :modules:shared-kernel:test --rerun-tasks` + ktlint/detekt(--rerun-tasks, false-green 주의). 회귀: 기존 listVisibleForBoard/Board 통합테스트(BoardControllerIntegrationTest epicKey doesNotExist→value 갱신 가능, #173 priority 선례).

### Task 2. (backend) changelog epic 값 epic key resolve (G2)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/IssueChangeDetector.kt` 또는 changelog 조회 서비스(backend-engineer가 #174 구조 보고 선택), 관련 test]
- depends-on: []

**RED**: 자식 이슈 changelog의 epic 엔트리 from/to(또는 label)가 epic **key**(예 ATLAS-5)로 표현됨을 단언하는 테스트. 현재 epicId UUID 기록이라 실패 → RED.

**GREEN**: epic UUID → epic key resolve. 방식 택1(backend-engineer 판단): (a) detector가 epic key 기록(epic 조회 의존 추가) (b) changelog 조회 서비스에서 batch resolve해 fromLabel/toLabel 박제(type/component refs 패턴 일관, 권장). epic key 안정성(재사용 금지)으로 박제 안전.

**REFACTOR**: KDoc. soft-deleted epic도 key 보존(이슈 키 영구).

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --rerun-tasks` + ktlint/detekt. ※게이트1에서 Maxi가 "changelog는 범위 밖"이라 판단 시 이 task 제거 가능(프론트 T6는 라벨만 유지).

### Task 3. (frontend) IssueResponse.epic Zod + epic-children api/hooks + MSW

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issues.ts`, `apps/web/src/api/epic-children.ts`(신규) 또는 issue-links.ts 확장, `apps/web/src/mocks/issue-handlers.ts`(epic-children handlers + epic stateful), 신규 `.test.ts`, **Zod required 추가로 깨질 인라인 IssueResponse mock 전수**(grep `currentStateKey|reporterId` 등 식별필드 — useUpdateIssueSummary.test/use-issue-transitions.test/issue-handlers fixture 등)]
- depends-on: []

**RED**: epic-children api 함수(fetchEpicChildren/connectEpicChild/disconnectEpicChild) + IssueResponse.epic 파싱 테스트. 미구현 실패.

**GREEN**: issues.ts issueResponseSchema에 `epic: z.object({key:z.string(), summary:z.string()}).nullish()`(parent 동형). epic-children.ts: `GET/POST/DELETE` 호출 + EpicChildSummaryResponse Zod(`{key, summary, typeKey: z.string().nullable(), currentStateKey}`) + EpicChildListResponse. hooks: useEpicChildren / useConnectEpicChild / useDisconnectEpicChild / useSetIssueEpic / useClearIssueEpic — onSuccess가 epic-children 목록 + 단건 이슈 둘 다 invalidate(cross-mutation, setQueryData 금지). MSW: epic-children stateful handlers + IssueResponse.epic 반영. CSRF X-XSRF-TOKEN(issue 관례 확인).

**REFACTOR**: Zod 추론 타입 export. 인라인 mock 전수 복원 후 `pnpm typecheck`(vitest≠tsc).

**검증**: `cd apps/web && pnpm test -- epic && pnpm typecheck`.

### Task 4. (frontend) 에픽 상세 자식 이슈 목록 섹션

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/EpicChildrenSection.tsx`(신규), `apps/web/src/routes/issues.$key.tsx`(타입 Epic일 때 섹션 배선), 신규 `.test.tsx`]
- depends-on: [3]

**RED**: EpicChildrenSection 렌더 — 자식 목록 표시, 자식 키 입력+추가(POST), 항목별 해제(DELETE), 빈 상태 placeholder, 권한 없으면 입력/버튼 비활성. 미구현 실패.

**GREEN**: WatchersSection/AttachmentSection 패턴(자체 useQuery+useMutation). 자식 항목 = 키+요약+상태/타입 칩(EpicChildSummaryResponse.currentStateKey/typeKey). issues.$key.tsx에서 `issue.hierarchyLevel===1`(또는 typeKey가 epic) 조건부 렌더. 권한 게이팅 = 에픽 canEdit(G1) + 4xx errorCode 한국어(409/422/404/400) 인라인.

**REFACTOR**: i18n 분리(콜론 종결 금지). 헬퍼 추출.

**검증**: `cd apps/web && pnpm test -- EpicChildren && pnpm typecheck`.

### Task 5. (frontend) 자식 상세 소속 에픽 섹션 (parent ParentSection 미러)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueLinksPanel.tsx`(EpicSection 추가) 또는 신규 컴포넌트, `apps/web/src/routes/issues.$key.tsx`(배선), 관련 `.test.tsx`]
- depends-on: [3]

**RED**: 소속 에픽 표시(IssueResponse.epic 있으면 링크 키+요약+해제 버튼, 없으면 에픽 키 입력+지정 버튼), level 0 이슈에만 노출(에픽/서브태스크 제외), 권한 disabled. 미구현 실패.

**GREEN**: IssueLinksPanel ParentSection(208-325) 미러. 지정 = useSetIssueEpic(POST `/issues/{입력epicKey}/epic-children` body 현재이슈키), 해제 = useClearIssueEpic(DELETE `/issues/{epic.key}/epic-children/{현재이슈키}`). 인라인 에러(parent 선례). `disabled={!canEdit}`(현재 이슈 권한).

**REFACTOR**: i18n. parent/epic 공통 추출 검토(과합치 금지).

**검증**: `cd apps/web && pnpm test -- IssueLinks && pnpm typecheck`.

### Task 6. (frontend) changelog "에픽" 라벨 + 값 표시

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/ko.ts`(changelogFieldLabels.epic), `apps/web/src/lib/changelog-labels.ts`(resolveValueLabel epic 처리), 관련 `.test.ts`]
- depends-on: []

**RED**: changelog field="epic" → 라벨 "에픽", 값 = epic key 표시(T2가 key resolve). 미구현 실패.

**GREEN**: ko.ts changelogFieldLabels에 `epic: '에픽'`. resolveValueLabel: epic 값을 raw(키) 표시(T2 백엔드가 key 박제). 박제 label 있으면 우선(기존 로직). 콜론 종결 금지.

**REFACTOR**: 정리.

**검증**: `cd apps/web && pnpm test -- changelog && pnpm typecheck`. ko.test 콜론 검증.

### Task 7. (frontend) 보드 EPIC 스윔레인

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/boards.ts`(swimlaneFieldSchema 'EPIC' + boardCardSchema epicKey), `apps/web/src/lib/board-labels.ts`(swimlane 옵션 라벨), `apps/web/src/lib/swimlane-group.ts`(또는 헬퍼 — EPIC 분기), `apps/web/src/components/board/SwimlaneSelector.tsx`(옵션), `apps/web/src/components/board/KanbanBoard.tsx`(그룹 라벨), `apps/web/src/mocks/board-handlers.ts`(epicKey), 관련 `.test.ts`. **boardCardSchema.epicKey required 추가로 깨질 인라인 board mock 전수 grep**(#173 priority 6파일 선례)]
- depends-on: []

**RED**: swimlane 'EPIC' 선택 시 카드가 epicKey별 레인 그룹(에픽 없음 레인 포함), 컬럼 내 세로 레인(FR-BD-03 동형, 드래그 droppable column id 보존). 미구현 실패.

**GREEN**: boards.ts enum에 'EPIC', boardCardSchema에 `epicKey: z.string().nullable()`. board-labels swimlane.options.EPIC="에픽". swimlane-group 헬퍼 EPIC 분기(epicKey null→"에픽 없음"). SwimlaneSelector 옵션. KanbanBoard 그룹 라벨(epic key). MSW board epicKey 미러. 인라인 mock 전수 복원.

**REFACTOR**: i18n. 헬퍼 순수성.

**검증**: `cd apps/web && pnpm test -- board && pnpm typecheck`. 기존 board 단위테스트 회귀 0.

### Task 8. (qa) E2E

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/epic-children.spec.ts`(신규), `apps/web/e2e/board-epic-swimlane.spec.ts`(신규) 또는 기존 board spec 확장, `apps/web/e2e/fixtures/issue-fixtures.ts`(epic/자식 fixture)]
- depends-on: [4, 5, 7]

**RED/GREEN**: ① 에픽 상세 자식 연결/해제 ② 자식 상세 소속 에픽 표시+지정/해제 ③ 보드 EPIC 스윔레인 그룹. loginAsAlice(session-fixtures) + MSW stateful store + SPA 내부 이동(reload 금지). 텍스트 중복 셀렉터 컨테이너 한정(strict mode). 드래그 회귀 0 확인.

**검증**: `cd apps/web && pnpm exec playwright test epic board-epic` + 기존 board/issue E2E 회귀.

## Plan 메타

- task 수: 8 (backend 2 + frontend 5 + qa 1)
- depends-on 그래프: T1[] T2[] T3[] / T4[3] T5[3] T6[] T7[] / T8[4,5,7]
- 예상 wave: backend(T1·T2 병렬 가능, 단 모듈 test 컴파일 직렬화 요인) + frontend는 **직렬 dispatch**(lint-staged stash race, history 반복 교훈) — T3→(T4,T5,T6,T7)→T8. 실질 직렬.
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 추가 검증: typecheck(tsc, vitest≠tsc), ktlint/detekt(--rerun-tasks, false-green 주의), playwright(qa)
- 주의 함정: Zod required 추가 시 인라인 mock 전수 grep(T3 issues, T7 boards) · frontend-zod-backend-dto 1:1 실측 · MSW stateful E2E reload 금지 · 보드 view-layer cross-BC(PR#13 옵션C) · BoardControllerIntegrationTest epicKey 회귀(#173 priority 선례)

## 리뷰 결과

### 집중 plan 리뷰 (2026-06-22, eng 비중)
type=ui지만 기존 컴포넌트 재사용(ParentSection/WatchersSection/SwimlaneSelector) + 백엔드 cross-BC view-layer 비중 → plan-design-review 무거운 mockup 절차 대신 집중 리뷰(history #173/#171 선례, 메모리 "백엔드 plan 리뷰=eng 집중 독립 리뷰").

**✅ 통과**
- T1 self-join 동일프로젝트 필터(P1-A 누출 회귀방지) RED 명시 · BoardControllerIntegrationTest 회귀(#173 priority 선례) 검증 명시
- T3/T7 Zod required 추가 인라인 mock 전수 grep 명시 · frontend-zod-backend-dto 1:1 실측
- T7 드래그 회귀 0(droppable column id 보존, FR-BD-03 동형) · T8 E2E 회귀 확인
- G1 권한: 에픽 canEdit(UX 힌트) + 백엔드 403(정확성 안전망) — 게이팅은 힌트, 백엔드가 자식 UPDATE 최종 검증

**⚠️ CONCERN (impl/게이트1 처리)**
- **C1 (T1)**: BoardIssueLookupAdapter epicKey 전달 경로 모호. listVisibleForBoard SQL이 epicKey alias fetch해도 Issue 도메인엔 epicKey 없어 toBoardIssueView에서 손실(Explore 지적). → **adapter가 SQL fetch 결과 epicKey를 BoardIssueView로 직접 전달**(Issue 도메인 우회, fetch 람다에서 record.get(EPIC_KEY_ALIAS) 추출 후 매핑). T1 GREEN에 명시 필요.
- **C2 (T2)**: T2가 #174 changelog 동작 변경 시 IssueEpicControllerIntegrationTest changelog 단언과 충돌 가능. detector 기록 불변 + **조회 시 resolve(방식 b) 권장**(회귀 위험 낮음). T2 검증에 #174 통합테스트 회귀 추가.
- **C3 (design, 경미)**: 보드 EPIC 스윔레인 그룹 라벨 = epic key만(요약 없음). 식별 가능하나 후속 개선 여지(epicSummary 노출). 이번 범위 수용.

**BLOCKER: 없음**

**design 경량 검토**: 기존 컴포넌트 재사용으로 비주얼 일관성 확보(신규 mockup 불요). 빈 상태 placeholder(EC2)·인라인 에러 role="alert"(parent 선례)·버튼 비활성 aria — T4/T5 GREEN에 접근성 명시 권장.

### PR-level 코드 리뷰 (게이트2, 2026-06-22)
code-reviewer(절대규칙) + 적대적 /review 병행.

- **/review (적대적)**: BLOCKER 0. T1 P1-A 누출차단·T2 changelog 박제·T3 invalidate-only·Zod 1:1·드래그 column id 보존 전부 검증. INFO 1 = T5 에러맵 구조 중복(값 drift 0).
- **code-reviewer (절대규칙)**: **BLOCKER 1 발견(B1)** + CONCERN 3. 적대 /review가 놓친 것을 code-reviewer가 잡음(두 리뷰 병행 상보).
  - **B1 (해소)**: 보드 EPIC 스윔레인이 프로덕션에서 깨짐 — 프론트 'EPIC' PATCH를 백엔드 SwimlaneField enum(NONE/ASSIGNEE/PRIORITY만)이 400 거부, MSW가 EPIC 허용해 vacuous green. **Maxi Option A 확정** → SwimlaneField.EPIC enum + **V502 마이그레이션(boards.swimlane_field CHECK 제약 EPIC 포함, code-reviewer가 놓친 DB CHECK를 controller가 잡음)** + init_codegen 미러 + 백엔드 테스트(EPIC 200/round-trip 실DB). test 338d8d61 → feat 82ed95bd. 백엔드 BUILD SUCCESSFUL 재검증.
  - **C2 (해소)**: epic-children MSW store afterEach 리셋 누락 → test/setup.ts resetIssueStateWithEpic() 교체(leak 차단).
  - **C3 (해소)**: 약한 assertion 2건 → invalidate spy(epicChildrenKey+issueQueryKey) + body childKey 단언 강화.
  - **C1 (수용)**: EPIC_CHILD_ERROR_MESSAGES 구조 복제(값 drift 0, 같은 상수+i18n 소스) — 후속 util 추출 여지, 이번 수용.
- 두 리뷰 통합: B1/C2/C3 수정 후 백엔드 BUILD SUCCESSFUL + 프론트 3692 passed/typecheck 그린. **BLOCKER 0**.
- 병렬 dispatch 커밋경계 race(C2/C3가 82ed95bd 흡수)는 squash 머지 무해, main 트리 오염 0 확인.
