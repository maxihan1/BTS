<!-- FR-SR-01 이슈 필터 (다중 필드 조합) 기술 스펙 -->

# FR-SR-01 — 이슈 필터 (다중 필드 조합) 스펙

- FR: FR-SR-01 (product `docs/plan/product/search-export-import.md §2.1`)
- 구현 BC: issue-tracking (ADR `docs/decisions/2026-06-23-fr-sr-01-issue-filter-bc.md`)
- 범위: **백엔드 D1~D5** (D6 프론트 / D7 E2E는 후속 PR로 분리)
- type: api | agent: backend-engineer (+ db-engineer for D3)

## 1. 요약

기존 `GET /api/v1/issues` 이슈 목록 API에 **다중 필드 필터**(status / assignee / label / component)를 추가한다.
같은 필드 다중값은 OR, 다른 필드 간은 AND로 결합한다. 페이지네이션·정렬(created_at DESC)·visibility(보안 수준) 필터는 기존 동작을 그대로 보존한다.

## 2. 사용자 시나리오 (Given-When-Then)

- **S1 무필터(기존 동작)**. Given 프로젝트 ATLAS에 이슈 30건 / When `GET /api/v1/issues?projectKey=ATLAS` / Then 기존과 동일하게 created_at DESC 페이지(size 20) 반환.
- **S2 단일 상태 필터**. Given 이슈 중 status=in_progress 8건 / When `?projectKey=ATLAS&status=in_progress` / Then in_progress 8건만 반환.
- **S3 다중 상태(필드 내 OR)**. When `?projectKey=ATLAS&status=open&status=in_progress` / Then status ∈ {open, in_progress} 이슈 반환.
- **S4 필드 간 AND**. When `?projectKey=ATLAS&status=open&assignee=<uuid>` / Then status=open **이면서** 담당자=uuid 인 이슈만.
- **S5 미배정**. When `?projectKey=ATLAS&assignee=unassigned` / Then 담당자 없는 이슈 반환.
- **S6 라벨 필터**. When `?projectKey=ATLAS&label=bug&label=urgent` / Then 라벨에 bug 또는 urgent 가 하나라도 포함된 이슈(배열 overlap).
- **S7 권한 격리(visibility)**. Given 사용자 B가 못 보는 보안등급 이슈가 필터 조건에 맞아도 / When B가 필터 조회 / Then 그 이슈는 결과에 **없음**(필터로 visibility 우회 불가).
- **S8 잘못된 UUID**. When `?projectKey=ATLAS&assignee=not-a-uuid` / Then 400 Bad Request(명시 핸들러, 500 변질 금지).

## 3. 기능 요구사항 (FR)

- **FR-1** `GET /api/v1/issues`에 쿼리 파라미터 추가 — `status`(반복), `assignee`(반복, `unassigned` 센티널), `label`(반복), `component`(반복).
- **FR-2** 같은 필드 다중값은 OR, 다른 필드 간은 AND (BoardCardFilter 의미론 동형).
- **FR-3** status 필터는 `issues.current_state_key` 정확 매칭(IN). 대소문자 구분(키는 소문자 컨벤션 — V004 lowercase).
- **FR-4** assignee 필터는 `issues.assignee_id` IN + `unassigned` 시 `assignee_id IS NULL` OR 결합.
- **FR-5** label 필터는 `issues.labels` 배열 overlap(`&&`, GIN 인덱스). 정확 라벨명 매칭(부분일치 아님).
- **FR-6** component 필터는 `issue_components` EXISTS 서브쿼리(JOIN 금지 — cartesian product 회피).
- **FR-7** 필터는 **count 쿼리와 content 쿼리 양쪽**에 동일 적용 — `Page.totalElements` 정확.
- **FR-8** visibility(보안 수준) 필터 + 활성(deleted_at IS NULL) + projectKey 술어와 필터를 AND 결합. 보안 우선.
- **FR-9** 빈/blank 파라미터 또는 파라미터 전무 → 무필터(`BoardCardFilter.EMPTY`), 기존 동작 보존.
- **FR-10** 필터는 issue-tracking 전용 파서(`IssueFilterQueryParser`)로 파싱. agile-planning의 `BoardFilterQueryParser`를 cross-BC import 하지 않음(BC 격리).

## 4. API 인터페이스 (REST)

```
GET /api/v1/issues
  ?projectKey=ATLAS          (기존, 필수 의미 — 빈 문자열 위임 동작 보존)
  &status=open&status=in_progress      (선택, 반복 가능, OR)
  &assignee=<uuid>&assignee=unassigned (선택, 반복 가능, OR; unassigned 센티널)
  &label=bug&label=urgent              (선택, 반복 가능, OR; 배열 overlap)
  &component=<uuid>                    (선택, 반복 가능, OR; EXISTS)
  &page=0&size=20                      (기존 Pageable, size ≤ 100)
→ 200 OK  Page<IssueResponse>   (응답 형식 무변경)
→ 400     assignee/component UUID 형식 오류
→ 403     BROWSE 권한 없음(기존)
```

응답 DTO(`IssueResponse`)와 페이지 형식은 **변경 없음**. 새 필드 추가 0.

## 5. 데이터 모델 변경

- **신규 테이블/컬럼 0.**
- **인덱스(D3, db-engineer 검토)**. 기존 — `idx_issues_project_id`, `idx_issues_project_id_deleted_at`, `ix_issues_labels_gin` 존재.
  신규 검토 — status/assignee 필터 성능을 위해 `(project_id, current_state_key)` / `(project_id, assignee_id)` 복합 인덱스 추가 검토. 1,000명·중간 데이터 규모 기준 필요성 판단(불필요하면 D3에서 "추가 안 함" 명시 + 사유). 추가 시 V0NN 마이그레이션 + `init_codegen.sql` 미러(메모리 jooq-init-codegen-mirror).

## 6. 변경 대상 코드 (재사용 우선)

| 파일 | 변경 |
|---|---|
| `shared-kernel/.../board/BoardCardFilter.kt` | `statusKeys: List<String> = emptyList()` 필드 추가 + `isEmpty()`에 반영(하위호환, 기본값으로 보드 동작 불변) |
| `issue-tracking/.../repository/IssueRepository.kt` | `listWithType`에 `filter: BoardCardFilter = EMPTY` 파라미터 추가 → count/content where에 `buildFilterCondition` AND 결합. `buildFilterCondition`에 `buildStatusCondition`(CURRENT_STATE_KEY IN) 추가 |
| `issue-tracking/.../application/IssueApplicationService.kt` | `listIssues`에 `filter` 파라미터 추가 → `repo.listWithType(..., filter)` 전달 |
| `issue-tracking/.../adapter/inbound/rest/IssueFilterQueryParser.kt` (신규) | status/assignee/label/component 쿼리 → BoardCardFilter. UUID 400은 `ResponseStatusException`(명시 핸들러 경유) |
| `issue-tracking/.../adapter/inbound/rest/IssueController.kt` | `list`에 status/assignee/label/component 파라미터 추가 → 파서 → service |

`buildAssigneeCondition`/`buildLabelCondition`/`buildComponentCondition`/`buildSecurityCondition`/`buildActiveSecureWhere`는 그대로 재사용.

## 7. 엣지 케이스

- **EC1** 필터 없음 → 전체 목록(기존). `BoardCardFilter.EMPTY` → `buildFilterCondition`이 null → where 무변경.
- **EC2** blank/공백 파라미터(`status=`) → 무시(trim 후 isBlank).
- **EC3** 잘못된 UUID(assignee/component) → 400 `ResponseStatusException`. `IssueExceptionHandler.handleResponseStatus`(IssueExceptionHandler.kt:659~)가 **이미 등록**되어 500 변질은 없음(status 400 전파됨). 단 현재 when 절이 `else -> INTERNAL_ERROR`라 **400 응답의 errorCode가 `INTERNAL_ERROR`로 오매핑**됨(클라이언트가 서버 오류로 오해) → `BAD_REQUEST -> VALIDATION_FAILED` 분기 추가로 교정(Task 4). 테스트는 status 400 + errorCode `VALIDATION_FAILED` 둘 다 단언.
- **EC4** 존재하지 않는 status/label → 결과 0(검증·400 안 함, 매칭 0).
- **EC5** `status` + 같은 status 중복값 → IN 중복 제거 자연 처리.
- **EC6** count 쿼리에 필터 누락 시 totalElements 오류 → FR-7로 양쪽 적용 보장(회귀 테스트 필수).
- **EC7** visibility 미보유 이슈가 필터 조건 충족 → 결과 제외(FR-8). 보안 필터 + 필터 둘 다 AND.
- **EC8** projectKey 빈 문자열(기존 위임) → 빈 결과/권한 동작 기존 보존(필터 무관).
- **EC9** pageSize > 100 → 기존 `require` 400 동작 보존.

## 8. 제약 조건

- **DevEx 트레이드오프 기록(C4)**. `status`는 워크플로우 상태 키(소문자, 예 `open`/`in_progress`) 정확 매칭이며, 유효 키는 워크플로우 상태 API에서 조회한다(오타 시 EC4로 조용히 빈 결과 — 클라이언트가 키를 추측하지 말 것). `assignee`/`component`는 username/이름이 아닌 **UUID**(보드 필터 BoardController와 일관). username→UUID 변환은 호출측(프론트) 책임.
- BC 격리 — agile-planning 코드(BoardFilterQueryParser/BoardController) import 금지. BoardCardFilter(shared-kernel)만 공유.
- 보드(FR-BD-02) 동작 회귀 0 — BoardCardFilter에 statusKeys 추가해도 보드 파서가 status 미파싱 → 항상 emptyList → 보드 결과 불변(회귀 테스트로 보장).
- 기존 IssueResponse/Page 응답 형식 무변경.
- TDD red→green→refactor 강제(test 커밋 선행).

## 9. 측정 가능한 완료 기준 (D1~D5)

- [ ] `GET /api/v1/issues` status/assignee/label/component 필터 동작(S2~S6 통합 테스트).
- [ ] 필드 내 OR / 필드 간 AND 의미론 검증(S3, S4).
- [ ] visibility 격리(S7) 통합 테스트 — 권한 없는 이슈 필터로 노출 0.
- [ ] count(totalElements) 필터 반영(EC6 회귀).
- [ ] 잘못된 UUID 400(S8, EC3) — 500 변질 0.
- [ ] 보드(FR-BD-02) 회귀 0(기존 보드 테스트 그대로 통과).
- [ ] ktlint/detekt clean, 전체 `:modules:issue-tracking:test` 통과.

## Brainstorming Check

직접 sanity check(인라인 적대 검토) 수행. 발견·반영 항목:
- count 쿼리 필터 누락 가능성 → FR-7 + EC6 회귀 테스트로 명시.
- catch-all 핸들러가 400을 500으로 변질 위험 → EC3에서 확인 의무화.
- BoardCardFilter 공유 VO에 statusKeys 추가 시 보드 회귀 → §8 회귀 0 보장 + 테스트.
- label 부분일치 오해 → FR-5 정확 매칭 명시.
- visibility 필터 우회 위험 → FR-8 / S7 / EC7로 보안 우선 명시.
✅ 통과 (1회, gap 모두 스펙에 반영).
