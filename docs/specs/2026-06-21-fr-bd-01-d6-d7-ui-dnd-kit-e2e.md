# FR-BD-01 D6/D7 — 칸반 보드 프론트엔드 UI + E2E (스펙)

> slug: fr-bd-01-d6-d7-ui-dnd-kit-e2e · BC: agile-planning · SDD §13.1 · 선행 백엔드 #165(보드)·#168(필터)
> 범위: 프론트 D6(@dnd-kit 보드 UI) + D7(E2E). 백엔드 변경 0(기존 API 호출만).
> 관련 ADR: docs/decisions/2026-06-20-fr-bd-01-agile-planning-bootstrap.md (기존, 무변경)

## 결정 요약 (Maxi 확정 2026-06-21)

- **드래그앤드롭 = @dnd-kit** (product §2.1 D6·SDD §13.1.1 명시). `@dnd-kit/core@6.3.1` +
  `@dnd-kit/utilities@3.2.2` 신규 의존성 도입(버전 고정). React 19 호환(peer `react>=16.8`).
  컬럼 내 재정렬(LexoRank)은 범위 외(ADR 결정 3) → @dnd-kit은 **컬럼 간 이동 전용**.
- **범위 = 보드 목록 + 생성 + 칸반 뷰 전체**. 라우트 `/projects/$projectKey/board`.
- **FR-BD-02 필터 칩 UI는 범위 외** (보드 안정화 후 별도).

## 사용자 시나리오 (Given-When-Then)

1. **보드 진입(보드 존재)** — Given 프로젝트 `BTS`에 보드가 1개 이상 있고 사용자가 BROWSE
   권한을 가질 때, When `/projects/BTS/board`로 진입하면, Then 첫 번째 보드의 컬럼이
   displayOrder 순으로 표시되고 각 카드가 현재 상태가 매핑된 컬럼에 배치되어 보인다.
2. **보드 없음 → 생성** — Given 프로젝트에 보드가 0개일 때, When 보드 페이지로 진입하면,
   Then "보드가 없습니다" 안내 + 보드 생성 폼(이름 입력)이 보이고, 이름을 입력해 생성하면
   default 워크플로우 상태가 컬럼으로 시드된 빈 보드가 표시된다.
3. **다중 보드 선택** — Given 프로젝트에 보드가 2개 이상일 때, When 보드 페이지로 진입하면,
   Then 보드 선택 드롭다운으로 보드를 전환할 수 있다(기본 = 첫 번째).
4. **카드 이동(일반 전이)** — Given 카드가 TODO 컬럼에 있을 때, When 카드를 IN_PROGRESS 컬럼으로
   드래그앤드롭하면, Then 낙관적으로 카드가 즉시 이동하고, `POST .../move`(toColumnId,
   expectedVersion=카드.version)가 성공하면 카드 version이 갱신된다.
5. **카드 이동(DONE — resolution 필요)** — Given 카드를 DONE 카테고리 컬럼으로 드롭할 때,
   When 드롭하면, Then resolution(해결 방안) 선택 모달이 열리고, resolution을 선택해 확인해야
   이동이 전송된다(미선택 시 확인 버튼 비활성). 취소하면 카드는 원위치한다.
6. **전이 불가/충돌(409)** — Given 워크플로우가 해당 전이를 허용하지 않거나 다른 사용자가 먼저
   전이했을 때, When 이동하면, Then 409로 거부되어 카드가 원위치하고 "다른 변경과 충돌이
   발생했습니다" 토스트 + 보드를 새로고침(refetch)한다.
7. **권한 없는 진입(403)** — Given BROWSE 권한이 없는 사용자가, When 보드 페이지로 진입하면,
   Then "접근 권한이 없습니다" 안내 화면을 표시한다.
8. **표시 제한 경고(truncated)** — Given 보드 카드가 백엔드 페치 상한을 초과할 때, When 조회하면,
   Then "표시되지 않은 이슈가 있습니다" 경고 배너를 표시한다.
9. **미매핑 이슈 경고(unplacedCount>0)** — Given 워크플로우 상태가 어떤 컬럼에도 매핑되지 않는
   이슈가 있을 때, Then "N개 이슈가 컬럼에 매핑되지 않아 표시되지 않았습니다" 경고를 표시한다.

## 기능 요구사항 (FR)

- **FR-1 보드 API 클라이언트** — `apps/web/src/api/boards.ts` 신설. 백엔드 DTO와 **1:1 미러**하는
  Zod 스키마 + fetch 함수.
  - `fetchBoards(projectKey)` → `GET /api/v1/boards?projectKey=` → `BoardSummary[]` (`{data:[...]}` 봉투).
  - `fetchBoard(boardId)` → `GET /api/v1/boards/{id}` → `BoardDetail` (`{data:{...}}`).
  - `createBoard(projectKey, name)` → `POST /api/v1/boards` → `BoardCreated` (201, `{data:{...}}`).
  - `moveCard(boardId, issueKey, {toColumnId, expectedVersion, resolutionId?})` →
    `POST /api/v1/boards/{id}/cards/{issueKey}/move` → `MoveCardResult` (`{data:{...}}`).
  - Zod 스키마: `boardSummarySchema`, `boardDetailSchema`(columns→cards 중첩), `boardCreatedSchema`,
    `moveCardResultSchema`. UUID는 Zod v4 형식. category는 `z.enum(['TODO','IN_PROGRESS','DONE'])`.
    `assigneeId: z.string().uuid().nullable()`, `truncated: z.boolean()`, `unplacedCount: z.number().int()`.
- **FR-2 TanStack Query 훅** — `useBoards(projectKey)`, `useBoard(boardId)`, `useCreateBoard(projectKey)`,
  `useMoveCard(boardId)`. 라우터/네트워크 비의존 단위 테스트 가능하게 분리.
- **FR-3 라우트** — `/projects/$projectKey/board` 등록(router.ts). `BoardRouteAdapter`(useParams) →
  `BoardPage({projectKey})` 패턴(기존 settings 라우트 선례). 가드 = `requireAuthAndPasswordChanged`.
  선택된 보드는 **URL search param `?board=<boardId>`**로 영속(G2 — 딥링크/E2E 견고성, account-links
  `validateSearch` 선례). 미지정 시 목록의 첫 보드로 기본 진입.
- **FR-4 칸반 보드 뷰** — `KanbanBoard`(컬럼 가로 배치, displayOrder asc) + `BoardColumn`(useDroppable) +
  `BoardCard`(useDraggable). 카드는 issueKey + summary + 담당자 표시. 카드 클릭 시 이슈 상세
  (`/issues/$key`)로 이동(Link).
- **FR-5 카드 이동(드래그)** — `DndContext`(PointerSensor + KeyboardSensor, a11y). 드래그 종료 시
  대상 컬럼 != 현재 컬럼이면 이동. **같은 컬럼 드롭 = no-op**(LexoRank 범위 외).
  - 대상 컬럼 `category === 'DONE'` → **resolution 모달 먼저**(IssueMetaPanel/BulkTransitionDialog
    선례 — `useResolutions()` 사용). 선택 후 `moveCard({..., resolutionId})`.
  - 그 외 → 즉시 `moveCard({toColumnId, expectedVersion=카드.version})`.
  - **낙관적 이동 + 롤백**: 드래그 종료 시 캐시에서 카드를 대상 컬럼으로 즉시 이동(스냅샷 보관).
    성공 시 응답의 `version`/`currentStateKey`로 카드 갱신(invalidate 아님 — 부분응답 플리커 회피,
    메모리 mutation-setquerydata-partial-response-flicker는 "전체 교체"만 금지, 단일 카드 패치는 안전).
    실패 시 스냅샷 복원.
- **FR-6 보드 생성** — 보드 0개일 때 `CreateBoardForm`(이름 입력 → `useCreateBoard`). 성공 시 생성된
  보드로 전환. 422(워크플로우 미할당) → "이 프로젝트에 워크플로우 스킴이 할당되지 않아 보드를 만들 수
  없습니다" 안내.
- **FR-7 담당자 표시 (best-effort, G1)** — 카드의 `assigneeId`(UUID)를 `fetchUsers()` **1회** 조회 결과로
  Map 구성해 displayName 해석(N+1 아님, 워처 선례). 미배정(null)=빈 아바타/"미배정".
  **★1,000명 규모 한계**: `fetchUsers()`는 substring 검색·결과 상한이 있어 전체 사용자를 보장하지 않는다.
  해석 안 되는 assigneeId는 **이니셜 아바타(짧은 식별자 fallback)**로 표시(이름 누락이 보드 사용을 막지
  않음). 전 사용자 이름 신뢰 표시가 필요하면 **백엔드 view-layer 패치**(`BoardCardResponse.assigneeDisplayName`
  추가, FR-MV-01식 same-BC 프론트PR+백엔드뷰레이어)가 정석 — 이번 범위 외 후속(C 후보로 명시).
- **FR-8 경고 배너** — `truncated === true` → 표시 제한 경고. `unplacedCount > 0` → 미매핑 경고.
- **FR-9 상태 처리** — 로딩 스켈레톤 / 빈 보드(컬럼만, 카드 0) / 403 접근 불가 / 404 보드 없음 /
  네트워크 에러 분기.

## 비기능 요구사항 (NFR)

- **NFR-1 (product §2.1)** 보드 200건 렌더 p95 < 1.5s. 카드 다수에도 드래그 부드러움. 불필요한 전체
  리렌더 회피(컬럼/카드 메모이즈, key=issueKey 안정).
- **NFR-2** 절대 규칙(DEVELOPMENT.md §1): TypeScript strict, no `any`, no `console`(eslint), Zod 런타임
  검증. 신규 의존성은 @dnd-kit 2종만(Maxi 확인 완료).
- **NFR-3 접근성** — 드래그를 키보드로도 수행 가능(@dnd-kit KeyboardSensor). 카드/컬럼 aria 라벨.
- **NFR-4** API 계약 정합 — Zod 스키마가 백엔드 DTO와 drift 없도록 spec의 응답 형태 기준 작성
  (frontend-zod-backend-dto-contract-gap 회귀 방지).

## API 인터페이스 (백엔드 — 기존, 변경 없음)

봉투 = `{ data: T }`. 에러 = RFC7807 ProblemDetail + 커스텀 `errorCode`(`AGILE_*`) + `timestamp`.

```
GET  /api/v1/boards?projectKey={key}
  200 { data: [ { boardId:UUID, projectKey:string, name:string } ] }
  403 AGILE_ACCESS_DENIED

GET  /api/v1/boards/{id}
  200 { data: {
    boardId:UUID, projectKey:string, name:string,
    columns: [ { columnId:UUID, stateKey:string, name:string,
                 category:"TODO"|"IN_PROGRESS"|"DONE", displayOrder:int,
                 cards: [ { issueKey:string, summary:string, assigneeId:UUID|null, version:long } ] } ],
    truncated:boolean, unplacedCount:int } }
  401 AGILE_UNAUTHENTICATED · 403 AGILE_ACCESS_DENIED · 404 AGILE_BOARD_NOT_FOUND
  400 AGILE_VALIDATION_FAILED (필터 파라미터 UUID 형식 오류 — 이번 UI는 필터 미사용)

POST /api/v1/boards   body { projectKey:string, name:string }
  201 { data: { boardId:UUID, projectKey, name, columns:[{columnId,stateKey,name,category,displayOrder}] } }
  400 검증 · 403 · 404(프로젝트) · 422 AGILE_UNPROCESSABLE(워크플로우 스킴 미할당, E1)

POST /api/v1/boards/{id}/cards/{issueKey}/move
  body { toColumnId:UUID, expectedVersion:long(>=0), resolutionId?:UUID }
  200 { data: { issueKey:string, currentStateKey:string, version:long, columnId:UUID } }
  400 검증 · 401 · 403 · 404(보드/컬럼/이슈)
  409 AGILE_CONFLICT      ← 전이 불가 + 버전 충돌 (★구분 불가, 둘 다 동일 코드)
  422 AGILE_UNPROCESSABLE ← 워크플로우 미설정 + resolution 필요 (★구분 불가, 둘 다 동일 코드)
```

### ★ 에러 코드 입자도 한계 (설계 반영)

백엔드 BoardExceptionHandler가 **HTTP 상태별 일반 errorCode**로 평탄화한다(전이 불가/버전 충돌이
모두 409 `AGILE_CONFLICT`, 워크플로우 미설정/resolution 필요가 모두 422 `AGILE_UNPROCESSABLE`).
→ 프론트는 **422를 사후 트리거로 쓰지 않는다.** 대신 보드 응답의 컬럼 `category === 'DONE'`을
**사전 감지**해 resolution 모달을 먼저 띄운다(IssueMetaPanel/BulkTransitionDialog 정석과 동일).
- 409 → "다른 변경과 충돌이 발생했습니다" 토스트 + 카드 원위치 + 보드 refetch(서버 진실 동기화).
- 422 → "워크플로우 또는 해결 방안 설정을 확인해 주세요" 토스트 + 원위치(보드 운영 중엔 드묾).

## 데이터 모델 변경

없음. 프론트 전용. 신규 마이그레이션/스키마 0.

## 엣지 케이스

- **EC1 같은 컬럼 드롭** — 대상=현재 컬럼이면 move 미전송(no-op). 캐시 변화 없음.
- **EC2 DONE 드롭 후 모달 취소** — 카드 원위치, move 미전송.
- **EC3 카드 version stale** — 낙관적 이동 후 409 버전 충돌 → 원위치 + refetch로 최신 version 회복.
  연속 이동은 직전 성공 응답의 version을 캐시에 반영해 사용.
- **EC4 빈 컬럼으로 드롭** — 카드 0개 컬럼도 droppable(빈 영역에 드롭 가능).
- **EC5 truncated + 이동** — 표시된 카드만 이동 대상. 경고 배너 상시.
- **EC6 보드 0개 + 생성 권한 없음** — 생성 폼 제출 403 → 권한 안내(폼은 노출하되 실패 메시지).
- **EC7 프로젝트 없음/비멤버** — 보드 목록 403 → 접근 불가 안내(members 페이지 S6 선례 동형).
- **EC8 드래그 중 다른 카드/컬럼 로딩** — DnD 진행 중 refetch 억제(드래그 종료까지 캐시 고정).

## 제약 조건

- BC 격리: 프론트는 백엔드 API만 호출(직접 DB 등 없음). agile-planning 첫 프론트 영역 — 관례는
  가장 가까운 선례(issues/project settings) 차용. CSRF는 **선례 확인 후 적용**(per-BC 관례 상이 —
  frontend-api-convention-per-bc: agile-planning move/create POST가 X-XSRF-TOKEN 필요한지 백엔드
  Security 설정 grep으로 impl 단계 확인).
- Zod 스키마는 백엔드 DTO 1:1. 인라인 mock은 z.infer 타입 사용(drift 차단).
- 라우트는 code-based RouteAdapter/Page 분리(.ts router.ts의 JSX 제약).
- @dnd-kit 버전 고정. 신규 의존성 추가는 이 2종으로 한정.

## 측정 가능한 완료 기준

- [ ] `apps/web/src/api/boards.ts` Zod 스키마가 백엔드 DTO와 1:1(단위 테스트로 파싱 검증).
- [ ] `/projects/$projectKey/board` 라우트 등록 + RouteAdapter/Page 분리.
- [ ] 칸반 뷰: 컬럼 displayOrder 정렬 + 카드 category 매핑 배치 + 담당자 표시 + 경고 배너.
- [ ] 카드 드래그: 컬럼 간 이동 → move 호출(낙관적+롤백). 같은 컬럼=no-op.
- [ ] DONE 드롭 → resolution 모달 → resolutionId 포함 이동.
- [ ] 409/422/403/404 에러 분기 + 토스트/안내 + 원위치.
- [ ] 보드 0개 → 생성 폼 → 생성 → 빈 보드 표시. 422(워크플로우 미할당) 안내.
- [ ] 다중 보드 선택 드롭다운.
- [ ] vitest 단위(컴포넌트/훅) + `tsc --noEmit` + eslint 그린.
- [ ] D7 E2E: 보드 생성→조회→카드 이동(일반)→DONE 이동(resolution)→충돌(409) 시나리오 + NFR
      렌더 시간(200건) 측정.
- [ ] 기존 E2E 회귀 확인(새 화면이 전역 셀렉터 안 깸 — ui-pr-defer-e2e-regression-latent).

## Brainstorming Check

✅ 통과 (1회 iteration, 직접 adversarial 갭 스캔 — FR-BD-01 백엔드 스펙 동형 방식). 발견·반영.

- **G1 (해소·범위조정)** 담당자 이름 표시 1,000명 규모 한계 → FR-7을 best-effort(단일 fetchUsers Map +
  이니셜 fallback)로 조정. 신뢰 표시는 백엔드 `assigneeDisplayName` view-layer 후속으로 명시(C 후보).
- **G2 (해소)** 보드 선택 영속성 → URL search param `?board=<id>`(딥링크/E2E 견고성). FR-3 반영.
- **G3 (해소)** 빈 resolutions 목록(DONE 드롭 시 선택지 0) → 모달에 "설정된 해결 방안이 없습니다" +
  확인 비활성(EC 추가). 전역 표준 resolution이라 드묾.
- **확인된 안전성**:
  - 422 사후 트리거 의존 제거 → 컬럼 `category==='DONE'` 사전 감지(BulkTransitionDialog/IssueMetaPanel
    정석 미러). 에러코드 입자도 한계(409/422 일반화) 우회.
  - 카드 droppable 미설정(컬럼만 droppable) → 컬럼 간 이동만, 같은 컬럼 no-op(LexoRank 범위 외 정합).
  - 낙관적 이동은 단일 카드 패치(전체 setQueryData 교체 아님) → 부분응답 플리커 회귀 회피.
  - assignee 해석 단일 fetch(N+1 아님) → NFR-1.
  - 신규 화면이 기존 전역 E2E 셀렉터 안 깨는지 D7에서 회귀 확인(ui-pr-defer-e2e 교훈).
  - CSRF는 per-BC 관례 상이 → impl에서 백엔드 Security 설정 grep 확인(추측 금지).
