# FR-EP-01 D6/D7 — 에픽 UI + 자식 연결 + 보드 EPIC 스윔레인 — 스펙

> slug: fr-ep-01-d6-d7-epic-ui-e2e
> 작성: 2026-06-22 · type: ui · agent: frontend-engineer
> 백엔드 D1~D5: PR #174 (계약 확정) · ADR 2026-06-22-fr-ep-01-epic-child-link.md
> 정의된 FR의 D6/D7 — office-hours 부적합, 직접 기술 스펙

## 결정 사항 (Maxi 게이트, 2026-06-22)

- **D-UI**: 별도 에픽 라우트 신설 안 함. 기존 이슈 상세(`issues.$key`) 재사용 — 에픽도 이슈 타입(hierarchy_level=1)일 뿐. parent 선례(별도 페이지 없이 IssueLinksPanel)와 일관.
- **D-스윔레인**: 보드 EPIC 스윔레인을 이 PR에 포함. FR-BD-03 #173이 "FR-EP 미구현"으로 이연한 enum을 FR-EP-01 완료로 활성화. 백엔드 `BoardCardResponse.epicKey` cross-BC view-layer patch 동반(PRIORITY 재노출 #173 옵션C 동형). → FR-BD-03 product 체크박스 / fr-index 영향 없음(스윔레인은 FR-BD-03 D6에서 이미 완료 마킹, enum 확장은 deviation 인라인으로 기록).

## 사용자 시나리오 (Given-When-Then)

### S1. 에픽에서 자식 이슈 연결
- Given 사용자가 에픽 이슈(타입 Epic) 상세를 보고 자식 UPDATE 권한이 있을 때
- When "자식 이슈 추가"에 자식 이슈 키(예: ATLAS-42)를 입력하고 추가하면
- Then `POST /api/v1/issues/{epicKey}/epic-children {childKey}` 호출 → 201 → 자식 목록에 추가되고 성공 토스트

### S2. 에픽에서 자식 연결 해제
- Given 에픽 상세의 자식 목록에 항목이 있고 자식 UPDATE 권한이 있을 때
- When 자식 항목의 "연결 해제"를 누르면
- Then `DELETE /api/v1/issues/{epicKey}/epic-children/{childKey}` → 204 → 목록에서 제거

### S3. 자식 이슈에서 소속 에픽 표시·지정·해제
- Given 일반 이슈(level 0) 상세를 볼 때
- When `IssueResponse.epic`이 있으면 소속 에픽(키+제목)을 링크로 표시하고, 없으면 "에픽 지정" 입력을 보여준다
- Then 에픽 지정 = `POST /api/v1/issues/{입력한 에픽키}/epic-children {childKey: 현재 이슈키}`, 해제 = `DELETE /api/v1/issues/{현재 epic.key}/epic-children/{현재 이슈키}` (parent ParentSection 미러)

### S4. 연결 실패 처리
- Given 자식 연결을 시도할 때
- When 백엔드가 4xx를 반환하면(409 이미 연결 / 422 타입·프로젝트·자기참조 / 404 미존재 / 403 권한 / 400 입력)
- Then errorCode별 한국어 메시지를 인라인(parent 선례) 또는 토스트로 표시. 목록/캐시 무변경

### S5. 변경 이력에 에픽 표시
- Given 자식 이슈의 에픽이 연결/해제되면 백엔드가 changelog field="epic" 기록
- When 사용자가 이슈 변경 이력을 보면
- Then "에픽" 필드 라벨 + 값(에픽 키/요약 또는 박제 라벨)으로 표시

### S6. 보드 EPIC 스윔레인 그룹
- Given 보드에서 스윔레인 기준을 "에픽"으로 선택하면
- When 보드가 렌더되면
- Then 각 컬럼 내부에서 카드가 소속 에픽별 레인으로 그룹핑(컬럼 내 세로 레인, FR-BD-03 ASSIGNEE/PRIORITY 동형). 에픽 없는 카드는 "에픽 없음" 레인

## 기능 요구사항 (FR)

- FR1. 에픽 이슈 상세에 **자식 이슈 목록 섹션**(EpicChildrenSection): GET 목록 표시, 자식 키 입력 후 POST 추가, 각 항목 DELETE 해제. 자체 useQuery+useMutation(WatchersSection/AttachmentSection 패턴).
- FR2. 일반 이슈 상세에 **소속 에픽 섹션**(IssueLinksPanel 내 EpicSection 또는 인접): `IssueResponse.epic` 표시 + 에픽 키 입력 지정/해제. parent ParentSection 그대로 미러(링크 스타일 키+요약, 인라인 에러, 권한 disabled).
- FR3. 섹션 노출 조건: 자식 목록 섹션은 이슈 타입이 Epic(hierarchy_level=1)일 때만. 소속 에픽 섹션은 level 0(자식 자격) 이슈에만(에픽/서브태스크 제외).
- FR4. 권한 게이팅: 연결/해제 버튼은 자식 UPDATE 권한 없으면 비활성(parent `disabled={!canEdit}` 패턴, use-project-permissions 재사용).
- FR5. api 레이어: `api/issues.ts`에 `IssueResponse.epic` Zod 추가(`z.object({key, summary}).nullish()`, parent 동형). epic-children 전용 api 모듈 또는 issue-links.ts 확장 — hook: useEpicChildren(GET)/useConnectEpicChild(POST)/useDisconnectEpicChild(DELETE)/useSetIssueEpic+useClearIssueEpic(자식 관점).
- FR6. cross-mutation invalidate: 연결/해제 성공 시 epic-children 목록 + 단건 이슈(epic 필드) 둘 다 invalidate(부분응답 setQueryData 금지, FR-WT-01 FR-7 / mutation-setquerydata 선례).
- FR7. changelog: i18n ko.ts `changelogFieldLabels`에 `epic: "에픽"` 추가. 값 표시는 백엔드 detector 기록 형식 확인 후(epic 키 또는 박제 label) resolveValueLabel 처리.
- FR8. 보드 EPIC 스윔레인:
  - 백엔드: `BoardCardResponse.epicKey`(nullable) view-layer 노출 + BoardIssueView/BoardIssueLookupPort 조회 SQL에 epic self-join(같은 프로젝트, deleted_at 필터). PRIORITY 재노출 #173 옵션C 동형.
  - 프론트: `swimlaneFieldSchema` enum에 'EPIC' 추가(boards.ts), boardCardSchema에 epicKey 추가, SwimlaneSelector 옵션+라벨(board-labels.ts), swimlane-group 헬퍼에 EPIC 분기, KanbanBoard 그룹 라벨(에픽 키 또는 "에픽 없음").

## 비기능 요구사항 (NFR)

- NFR1. Zod 스키마는 백엔드 DTO와 1:1(frontend-zod-backend-dto-contract-gap). `@JsonInclude(NON_NULL)` 적용 필드는 `.nullish()`, 항상 존재 nullable은 `.nullable()`. epicKey 직렬화 형식은 백엔드 실측.
- NFR2. 단위테스트는 vitest + tsc 동반(vitest≠tsc). Zod required 추가 시 인라인 mock 전수 grep+복원(zod-schema-strengthen-inline-mock-fanout).
- NFR3. E2E는 MSW stateful store 사용(serviceWorkers block 금지). SPA 내부 이동(reload 금지). 텍스트 중복 셀렉터는 컨테이너 한정(strict mode).
- NFR4. 인증 전 호출 아님(이슈 상세는 인증 후) — apiFetch 정상 사용, CSRF는 변이에 X-XSRF-TOKEN(issue-tracking 관례 확인).
- NFR5. i18n 문자열 콜론 종결 금지(ko.test 자동검증). 한국어 라벨 정본화.

## API 인터페이스 (소비 — #174 확정, 신규 0)

- `POST /api/v1/issues/{key}/epic-children` body `{childKey: string}` → 201 `{data: EpicChildSummaryResponse}`
- `DELETE /api/v1/issues/{key}/epic-children/{childKey}` → 204
- `GET /api/v1/issues/{key}/epic-children` → 200 `{data: {children: EpicChildSummaryResponse[]}}`
- `EpicChildSummaryResponse` = `{key: string, summary: string, typeKey: string|null, currentStateKey: string}`
- `IssueResponse.epic` = `{key: string, summary: string}` | null (단건 GET만)
- errorCode: ISSUE_EPIC_OR_CHILD_NOT_FOUND(404) · ISSUE_EPIC_CHILD_ALREADY_LINKED(409) · ISSUE_EPIC_CHILD_INVALID_TYPE / ISSUE_EPIC_TARGET_NOT_EPIC / ISSUE_EPIC_CHILD_CROSS_PROJECT / ISSUE_EPIC_CHILD_SELF_REFERENCE(422) · ISSUE_EPIC_VALIDATION_FAILED(400) · 403 권한

## 데이터 모델 변경

- 프론트: 신규 스키마 0(소비). Zod 필드 추가만.
- 백엔드(보드 스윔레인용 view-layer): `BoardCardResponse.epicKey` 추가 + BoardIssueView 조회 SQL self-join. **신규 테이블/마이그레이션 0**(epic_id는 V028에 이미 존재). PR #13 옵션C same-BC view-layer 패턴.

## 엣지 케이스

- EC1. 에픽이 아닌 이슈 상세 → 자식 목록 섹션 비노출. 서브태스크(level -1) → 소속 에픽 섹션 비노출(직접 부여 금지, Jira 모델).
- EC2. 자식 목록 빈 상태 → "연결된 자식 이슈 없음" placeholder + 추가 입력.
- EC3. 자기참조(에픽에 자기 자신 추가) → 422 → 인라인 에러.
- EC4. 이미 다른 에픽에 소속된 자식 재연결 → 409 → "이미 에픽에 연결됨".
- EC5. 권한 없음 → 버튼 비활성 + (시도 시) 403 처리.
- EC6. typeKey null(DB 불일치) → 타입 칩 생략, 키+요약만.
- EC7. 보드 EPIC 스윔레인 + 보드 필터(FR-BD-02) 동시 → 필터된 카드만 그룹핑(FR-BD-03 WIP 신호 필터 상호작용 선례 주의).
- EC8. 에픽 키 입력 오타(존재하지 않는 키) → 404 → 인라인 에러.

## 제약 조건

- 백엔드 계약 불변(#174 머지됨). 프론트는 계약 소비만, 백엔드는 보드 view-layer epicKey만 추가.
- BC: 에픽 연결 UI는 issue-tracking 계약, 보드 스윔레인은 agile-planning + cross-BC view-layer. 단일 SPA라 프론트 BC 격리 없음.
- 검색 API 부재 → 에픽/자식 키 직접 입력(FR-LK-01 이슈 링크 선례).

## 측정 가능한 완료 기준

1. 에픽 상세에서 자식 추가/해제가 백엔드 왕복으로 동작(201/204/목록 갱신).
2. 자식 상세에서 소속 에픽 표시 + 지정/해제 동작(parent 동형).
3. 4xx 에러가 errorCode별 한국어로 표시되고 캐시 무변경.
4. 변경 이력에 "에픽" 필드 라벨 표시.
5. 보드 스윔레인 "에픽" 선택 시 카드가 에픽별 레인 그룹핑(드래그 이동 회귀 0).
6. 권한 없는 사용자에게 연결/해제 버튼 비활성.
7. 단위테스트(vitest)+typecheck(tsc)+lint 그린, 관련 E2E 그린, 기존 보드/이슈 E2E 회귀 0.
8. FR-EP-01 product 체크박스 D6/D7 [x], 대시보드 70/123.

## Brainstorming Check (← Phase B)

직접 sanity check(정의된 FR — office-hours/brainstorming 풀 호출 부적합). gap 3건 발견, 모두 plan 단계 해소(Maxi BLOCKER 아님).

- G1. 에픽 상세 "자식 추가" 버튼 권한 게이팅 기준 모호(자식 입력 전) → plan에서 결정(에픽 프로젝트 권한 근사 vs 백엔드 403 위임). 자식 상세 에픽 지정/해제는 canEdit으로 정확 게이팅(parent 동형).
- G2. changelog "epic" 값 표시 형식(에픽 키/박제 라벨) 미확정 → plan task로 백엔드 IssueChangeDetector epic extractor 기록 형식 확인.
- G3. 이 PR은 순수 프론트 아님 — 보드 스윔레인 cross-BC view-layer patch(BoardCardResponse.epicKey) 포함 → plan에서 backend-engineer task 별도 지정.

✅ 통과 (1회, gap 3건 plan으로 인계)
