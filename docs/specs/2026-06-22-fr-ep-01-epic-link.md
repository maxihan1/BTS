# FR-EP-01 — 에픽 이슈 타입 + 자식 이슈 연결 (백엔드 D1~D5) — 스펙

> BC. issue-tracking · slug. fr-ep-01-epic-link · 2026-06-22
> 도메인 ADR. [2026-06-22-fr-ep-01-epic-child-link.md](../decisions/2026-06-22-fr-ep-01-epic-child-link.md)
> 선례. FR-LK-01(링크/parent, IssueLinkController) · FR-PL-01(날짜=UPDATE 권한) · FR-TT-01(worklog=권한 repo조회 선행)

## 개요

Epic(이슈 타입, hierarchy_level=1)에 자식 이슈(story/task/bug, level 0)를 연결/해제/조회한다. 연결은 별도 `issues.epic_id UUID NULL` 컬럼(V028)으로 저장한다. parent_id(Subtask→부모)와 완전히 별개 메커니즘.

## 사용자 시나리오 (Given-When-Then)

- **S1 (연결, happy)**. Given UPDATE 권한 보유 + Epic이 존재, When `POST /issues/{epicKey}/epic-children {childKey}`, Then 자식의 epic_id가 설정되고 201 + 자식 요약 반환.
- **S2 (권한 없음)**. Given 자식에 UPDATE 권한 없음, When 연결, Then 403 (epic 존재 노출 안 함 — 권한 검증을 repo 조회보다 먼저).
- **S3 (자식 타입 위반)**. Given 자식이 Epic(level 1) 또는 Subtask(level -1), When 연결, Then 422 (level 0만 자식 가능).
- **S4 (대상이 Epic 아님)**. Given 경로 {epicKey}가 Epic 타입(level 1)이 아님, When 연결, Then 422.
- **S5 (다른 프로젝트)**. Given 자식과 Epic이 다른 프로젝트, When 연결, Then 422.
- **S6 (이미 Epic 소속)**. Given 자식의 epic_id가 이미 설정됨, When 다른/같은 Epic 연결, Then 409 (먼저 해제 필요 — 암묵적 reparent 차단).
- **S7 (자기참조)**. Given childKey == epicKey, When 연결, Then 422.
- **S8 (해제)**. Given UPDATE 권한 + 자식이 이 Epic 소속, When `DELETE /issues/{epicKey}/epic-children/{childKey}`, Then epic_id=null, 204.
- **S9 (해제 대상 아님)**. Given 자식의 epic_id가 이 Epic이 아님, When 해제, Then 404 (이 Epic의 자식이 아님).
- **S10 (자식 목록)**. Given VIEW 권한 + Epic 존재, When `GET /issues/{epicKey}/epic-children`, Then 200 + actor가 볼 수 있는 자식만 (visibility 필터, 누출 0).
- **S11 (자식 단건에 Epic 노출)**. Given 자식이 Epic 소속, When `GET /issues/{childKey}`, Then IssueResponse.epic = {epicKey, summary} (parent 노출과 동형, 단건만).

## 기능 요구사항 (FR)

| ID | 요구 |
|---|---|
| FR-1 | 자식 이슈에 epic_id를 설정/해제하는 메커니즘 (별도 컬럼). |
| FR-2 | 연결 시 불변식 강제: 자식 level=0, 대상 Epic level=1, 동일 프로젝트, 자기참조 금지, 단일 Epic(이미 소속 시 409). |
| FR-3 | 연결/해제는 자식 이슈에 대한 `IssuePermission.UPDATE`(IssueScope.Issue) 요구. actor 추출 최상단(CurrentActor) → 권한 검증을 repo 조회보다 **먼저**(존재 probe 방지). 경로 Epic 미존재/미가시 시 **404**(존재 숨김, 403 아님 — 단건 VIEW 404-hide 정책 일관). |
| FR-4 | Epic 자식 목록 조회는 **board 동형**: `IssuePermission.BROWSE`(IssueScope.Project, Epic 프로젝트) 진입 검사 + `accessibleLevels`(IssueSecurityDirectory) SQL 푸시다운. **accessibleLevels 단독은 보안등급만 거르고 VIEW_ISSUE 매트릭스는 BROWSE 진입 검사가 담당**(FR-NT-03 반례 — accessibleLevels 단독 누출). per-issue N+1 금지. |
| FR-5 | 자식 단건 조회(IssueResponse)에 소속 Epic 요약(epicKey + summary) 노출 — parent 노출과 동형, 단건 경로만(목록은 null). |
| FR-6 | epic 대상/자식 미존재·소프트삭제 시 404. |

## 비기능 요구사항 (NFR)

- **NFR-1 (보안/누출)**. 자식 목록은 actor visibility로 필터 — 권한 없는 이슈 제목 누출 금지(board/recipient-resolver 교훈). 권한 검증은 repo 조회 선행(probe 방지).
- **NFR-2 (성능)**. 자식 목록 visibility 필터는 SQL 푸시다운(per-issue N+1 금지). FR-EP-02 집계(자식 100개)가 500ms 임계이므로 epic_id 인덱스 필수.
- **NFR-3 (BC 격리)**. 권한은 IssuePermissionResolver(shared-kernel) 재사용. 신규 권한 코드 0.
- **NFR-4 (데이터 무결성)**. epic_id는 issues 자기참조 실 FK. 소프트삭제라 ON DELETE 미발화(parent_id 동형).

## API 인터페이스 (REST)

모두 `/api/v1/issues/{key}` 하위 (IssueLinkController 패턴 — 별도 `IssueEpicController` 신설).

| 메서드 · 경로 | 설명 | 성공 | 권한 |
|---|---|---|---|
| `POST /issues/{epicKey}/epic-children` body `{ "childKey": "PROJ-12" }` | 자식 연결 | 201 + 자식 요약 | UPDATE(child, Issue scope) · epic 미가시 404 |
| `DELETE /issues/{epicKey}/epic-children/{childKey}` | 자식 해제 | 204 | UPDATE(child, Issue scope) |
| `GET /issues/{epicKey}/epic-children` | 자식 목록 (visibility 필터) | 200 + 목록 | BROWSE(epic 프로젝트, Project scope) + accessibleLevels 푸시다운 |
| (확장) `GET /issues/{childKey}` IssueResponse.epic | 자식의 소속 Epic 요약 | 200 | VIEW(child, 기존) |

오류 매핑 (`EpicChildExceptionHandler` — `@RestControllerAdvice(assignableTypes=[IssueEpicController])` 스코프 한정, catch-all 금지). errorCode prefix=`ISSUE_EPIC_`(에이전트 §6 ISSUE_ 우산, WatcherExceptionHandler `ISSUE_WATCHER_*` 선례). `ResponseStatusException`(미인증 401·CurrentActor) 구체 핸들러 + `MethodArgumentTypeMismatch`/`HttpMessageNotReadable`(400) 구체 핸들러를 `Exception` fallback보다 먼저(catch-all-swallows 회피, WatcherExceptionHandler 선례).

| 상황 | 상태 | errorCode |
|---|---|---|
| epic/child 미존재·소프트삭제·epic 미가시 | 404 | ISSUE_EPIC_OR_CHILD_NOT_FOUND |
| 자식 level≠0 | 422 | ISSUE_EPIC_CHILD_INVALID_TYPE |
| 대상 Epic 아님(level≠1) | 422 | ISSUE_EPIC_TARGET_NOT_EPIC |
| 다른 프로젝트 | 422 | ISSUE_EPIC_CHILD_CROSS_PROJECT |
| 자기참조(child==epic) | 422 | ISSUE_EPIC_CHILD_SELF_REFERENCE |
| 이미 Epic 소속 | 409 | ISSUE_EPIC_CHILD_ALREADY_LINKED |
| child UPDATE 권한 없음 | 403 | (IssueAccessDeniedException 기존, detail 내부정보 비노출) |
| 미인증 | 401 | (ResponseStatusException) |
| childKey 누락/형식오류 | 400 | (검증) |

검증 순서. **actor 추출(CurrentActor, 미인증 401) 최상단** → UPDATE(child) 권한 → child 404 → epic 404(미존재·미가시 동일, 존재 숨김) → self 422 → 자식 level 422 → epic level 422 → cross-project 422 → already-linked 409 → updateEpic. (목록은 actor → BROWSE(epic 프로젝트) → epic 404 → accessibleLevels 푸시다운.)

## 데이터 모델 변경

```sql
-- V028__issue_epic_link.sql
ALTER TABLE issues ADD COLUMN epic_id UUID NULL REFERENCES issues(id);
COMMENT ON COLUMN issues.epic_id IS '소속 Epic (issues.id 자기참조). NULL=소속 없음. parent_id(Subtask 계층)와 별개 — Epic↔자식 (FR-EP-01).';
CREATE INDEX idx_issues_epic_id ON issues(epic_id);
```
- `init_codegen.sql` 미러 필수 (jooq-init-codegen-mirror).
- V028 (V027 다음). V005 hierarchy_level 활용(epic=1/level0 자식).

## 엣지 케이스

- 자식이 이미 다른 Epic 소속 → 409 (해제 후 재연결). 같은 Epic 재연결도 409(이미 소속).
- 해제 시 자식의 epic_id가 이 Epic이 아니면 404(이 Epic의 자식 아님).
- Epic 소프트삭제 시 자식 목록/단건 epic 노출: deleted_at 필터(소프트삭제 Epic은 미노출).
- 자식 소프트삭제 → 목록에서 제외.
- 커스텀 이슈 타입(level=0)도 자식 가능(표준 5종 한정 아님 — hierarchy_level 기준).
- **단건 IssueResponse.epic 노출은 parent self-join과 동형**: deleted_at만 필터하고 보안등급(security level) 필터는 적용 안 함(parent 선례 IssueRepository.kt:522 일관). 자식 단건 진입 자체가 자식 VIEW(404-hide)로 보호되므로 무권한자 진입은 차단. 보안등급 제한 Epic의 summary 노출이 parent와 동일하게 의도적 수용임을 ADR에 deviation 기록(적대 리뷰 사전 차단).

## 제약 조건

- BC 격리: 권한/visibility는 IssuePermissionResolver / IssueSecurity 재사용. 신규 권한·신규 보안 경로 0.
- parent_id 메커니즘 무변경 — epic_id는 독립 컬럼·독립 서비스.
- 프론트(D6 Epic 페이지)·E2E(D7)는 후속 PR. 이번 PR은 백엔드 D1~D5.

## 측정 가능한 완료 기준

- [ ] V028 + init_codegen 미러, MigrationFileLayoutTest 통과.
- [ ] 연결/해제/목록/단건-epic-노출 4경로 통합테스트(Testcontainers) green — S1~S11 커버.
- [ ] 불변식 5종(자식 level / 대상 Epic / cross-project / self / already-linked) 단위+통합 테스트.
- [ ] 권한 3종(UPDATE 연결·해제, VIEW 목록) + 자식 visibility 필터 누출 0 테스트.
- [ ] ktlint + detekt(--rerun-tasks) + verify-master-plan(FR 123 불변) green.

## Brainstorming Check (Phase B)

직접 갭 점검(정의된 FR — 대화형 brainstorming 대신 집중 점검). 발견 2건.

- **G1 (changelog 이력) — 게이트 1 Maxi 결정 필요**. epic 연결/해제를 자식 이슈 changelog(IssueHistoryRecorder)에 기록할지. 현황: `link` 패키지(parent_id 전용 서비스)는 changelog 미기록 → parent 선례 따르면 epic도 미기록. 그러나 FR-PL-01 교훈(신규 Issue 필드 이력 필수)+적대 /review가 누락 적발 전례. **권장=기록**(Jira parity·감사가치·적대리뷰 사전차단). epic_id는 PATCH updateFields가 아닌 전용 엔드포인트라 SCALAR_FIELD_EXTRACTORS 자동 적용 대상은 아님(IssueHistoryRecorder 직접 호출 필요). parent_id 동일 갭은 별도 후속(이번 PR 범위 밖, 스코프 규율).
- **G2 (이미 Epic 소속) — 해결(스펙 반영)**. 자식 epic_id가 이미 설정된 상태에서 연결 시 409(EPIC_CHILD_ALREADY_LINKED). 암묵적 reparent 차단, 명시적 해제 후 재연결. Jira의 replace 대신 explicit 채택(안전).

나머지(visibility 필터·권한 선행·불변식 5종·V028 미러)는 스펙 본문에 반영됨.
