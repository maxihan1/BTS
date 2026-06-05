# FR-CM-02 이슈에 다중 컴포넌트 할당 — 스펙

> slug: fr-cm-02-issue-components | BC: issue-tracking | 날짜: 2026-06-04
> 도메인 ADR: docs/adr/2026-06-04-issue-component-assignment-model.md
> 선례 복제: FR-IS-03 담당자 전용 서브리소스(PATCH /issues/{key}/assignee).

## 개요

한 이슈에 0~N개의 컴포넌트(프로젝트 내 하위 영역 분류)를 붙인다. 이슈↔컴포넌트는 다대다.
변경은 **전체교체(set) 의미론** — 컴포넌트 ID 전체 목록을 PATCH로 보내 통째로 교체한다.

## 사용자 시나리오 (Given-When-Then)

### S1. 컴포넌트 할당 (해피패스)
- **Given** 프로젝트 ATLAS에 컴포넌트 "Backend"(C1), "Frontend"(C2)가 있고, 이슈 ATLAS-1은 컴포넌트 미할당.
- **When** 편집 권한자가 `PATCH /api/v1/issues/ATLAS-1/components`에 `{componentIds:[C1,C2], expectedVersion:0}` 전송.
- **Then** 200 + `IssueResponse.componentIds == [C1,C2]`. issue_components에 (ATLAS-1, C1)/(ATLAS-1, C2) 2행. version 증가.

### S2. 부분 교체 (set 의미론)
- **Given** ATLAS-1이 [C1,C2] 할당 상태(version=1).
- **When** `{componentIds:[C2], expectedVersion:1}` 전송.
- **Then** 200 + componentIds == [C2]. (ATLAS-1, C1) 행 삭제, (ATLAS-1, C2) 유지. 추가 없음.

### S3. 전부 해제 (빈 배열)
- **Given** ATLAS-1이 [C2] 할당 상태.
- **When** `{componentIds:[], expectedVersion:2}` 전송.
- **Then** 200 + componentIds == []. issue_components에서 ATLAS-1 관련 행 전부 삭제.

### S4. 존재하지 않거나 삭제된 컴포넌트 (422)
- **Given** C9는 존재하지 않는 ID, C3는 소프트 삭제된 컴포넌트.
- **When** `{componentIds:[C1,C9]}` 또는 `[C1,C3]` 전송.
- **Then** 422 `COMPONENT_NOT_FOUND` (위반 컴포넌트 ID 포함). issue_components 변경 없음(원자적 — 하나라도 무효면 전체 거부).

### S5. 다른 프로젝트의 컴포넌트 (422)
- **Given** 컴포넌트 C5는 프로젝트 BETA 소속, 이슈 ATLAS-1은 ATLAS 소속.
- **When** `{componentIds:[C5]}` 전송.
- **Then** 422 `COMPONENT_NOT_FOUND` (프로젝트 불일치 = 그 프로젝트엔 없는 컴포넌트). 변경 없음.

### S6. 낙관락 충돌 (409)
- **Given** ATLAS-1 현재 version=3.
- **When** `{componentIds:[C1], expectedVersion:2}` (stale) 전송.
- **Then** 409 `VERSION_CONFLICT`. 변경 없음.

### S7. 권한 없음 (403)
- **Given** 편집 권한이 없는 사용자(prod).
- **When** PATCH 전송.
- **Then** 403 `ISSUE_ACCESS_DENIED`. (비prod는 AlwaysAllow로 통과.)

### S8. 없는 이슈 (404)
- **When** `PATCH /api/v1/issues/NOPE-1/components` 전송.
- **Then** 404 `ISSUE_NOT_FOUND` (소프트 삭제된 이슈 포함).

### S9. 중복 ID 정규화
- **When** `{componentIds:[C1,C1,C2]}` 전송.
- **Then** 200 + componentIds == [C1,C2] (도메인 distinct 정규화). issue_components에 중복 행 없음.

### S10. 프론트 — 이슈 상세 다중 셀렉터
- **Given** 이슈 상세 메타 패널(IssueMetaPanel)에 컴포넌트 섹션.
- **When** 사용자가 셀렉터에서 컴포넌트 여러 개를 체크/해제하고 적용.
- **Then** PATCH 호출 → invalidate → 현재 할당 컴포넌트가 칩/목록으로 표시. 권한 없으면 셀렉터 비활성(fail-closed).

## 기능 요구사항 (FR)

- **FR-1 도메인(D1)**: `Issue.componentIds: List<UUID> = emptyList()` 추가. `assignComponents(ids: List<UUID>): Issue`(distinct 정규화 후 copy), `clearComponents(): Issue`. labels 정규화(`validateAndNormalizeLabels`) 선례 동형 — null 제외 + distinct. **개수 상한 없음**(Maxi 결정 2026-06-04, Jira 동일).
- **FR-2 데이터(D3)**: `issue_components` 조인 테이블 — `issue_id UUID NOT NULL FK→issues(id)`, `component_id UUID NOT NULL FK→components(id)`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`. 복합 PK `(issue_id, component_id)`. 인덱스 `idx_issue_components_component_id(component_id)`. Flyway **V012** + jOOQ init_codegen.sql 미러(메모리 jooq-init-codegen-mirror).
- **FR-3 API(D4)**: `PATCH /api/v1/issues/{key}/components` — 요청 `{componentIds: List<UUID>, expectedVersion: Long}`, 응답 200 `IssueResponse`(componentIds 포함). 전용 서브리소스(merge-patch 3-state 모호성 회피, 담당자 선례).
- **FR-4 검증(D4)**: 이슈 존재(404) → 권한(403) → 각 componentId가 **이슈와 같은 프로젝트 + 활성**인지 `ComponentRepository.findById(id, projectId)`로 검증(없으면 422 `COMPONENT_NOT_FOUND`) → 낙관락(409). 도메인 정규화는 service가 `assignComponents`/`clearComponents` 경유(메모리 patch-merge-도메인-우회 — repository raw 우회 금지).
- **FR-5 영속(D1)**: 전체교체는 "이슈의 기존 issue_components 전체 삭제 후 신규 목록 삽입"을 **한 트랜잭션**에서. version은 issues row의 expectedVersion 낙관락으로 충돌 감지(updateVersion bump). 담당자 `updateAssignee` 선례 동형.
- **FR-6 읽기(D5)**: `IssueResponse.componentIds: List<UUID>`를 **단건 조회 경로(`findByKeyWithType`)에만** 노출. 목록 경로는 생략(빈 배열) — N+1 회피, resolution 단건전용 노출 선례 동형(Maxi 결정 2026-06-04). 조회 시 **활성 컴포넌트만**(`JOIN components ON ... WHERE deleted_at IS NULL`) — 소프트 삭제된 컴포넌트는 자동 제외(고아행 무해, Maxi 결정). 프론트는 컴포넌트 이름을 별도 로드 목록(fetchComponents)으로 해소(담당자 assigneeId→useUsersByIds 동형).
- **FR-7 프론트(D6)**: IssueMetaPanel에 컴포넌트 다중 셀렉터. 현재 할당 컴포넌트 칩 표시 + 추가/제거. `useChangeComponents` 훅(useChangeAssignee 복제, invalidate-only, X-XSRF-TOKEN). 권한 없으면 비활성(fail-closed, FR-PM-03 게이팅 선례).
- **FR-8 E2E(D7)**: Playwright — 컴포넌트 할당/부분교체/전부해제 happy path. (422/409는 UI 트리거 곤란 → 단위/통합 커버.)

## 비기능 요구사항 (NFR)

- **NFR-1 BC 격리**: issue_components는 issues·components 모두 issue-tracking 소유라 **실 FK 적용**(cross-BC 아님). reporter/assignee의 FK 생략과 다름.
- **NFR-2 성능(목록 N+1)**: componentIds는 **단건 경로에만** 노출(목록 생략)으로 N+1 원천 회피(Maxi 결정). 단건 조회 시 issue_components↔components 조인은 단일 이슈라 비용 작음. 카테시안 곱 주의(메모리 cartesian-product-jooq-leftjoin-count — 다른 컬렉션과 동시 JOIN 시 분리 조회).
- **NFR-3 트랜잭션**: 컴포넌트 교체 = ApplicationService `@Transactional`(클래스 레벨, 기존). 삭제+삽입+version bump가 원자적. 컨트롤러는 트랜잭션 경계 미보유.
- **NFR-4 멱등/원자성**: 같은 목록 재전송은 결과 동일(set). 무효 컴포넌트 1개라도 있으면 **전체 거부**(부분 적용 금지).
- **NFR-5 이력/알림 범위 밖**: 담당자 변경 선례(`changeAssignee`)가 이슈 히스토리를 남기지 않고 `IssueEventPublisher` 이벤트도 발행하지 않으며, `issue_history` 인프라 자체가 미존재 → FR-CM-02도 동일(이력·알림 없음). 후속 감사/알림 FR에서 일괄 도입.

## API 인터페이스 (REST)

```
PATCH /api/v1/issues/{key}/components
  요청  { "componentIds": ["<uuid>", ...], "expectedVersion": <long> }
  200   { "data": { ...IssueResponse, "componentIds": ["<uuid>", ...] } }
  403   ISSUE_ACCESS_DENIED        (prod 권한 없음)
  404   ISSUE_NOT_FOUND            (없거나 소프트 삭제된 이슈)
  409   VERSION_CONFLICT           (낙관락 stale)
  422   COMPONENT_NOT_FOUND        (없음/삭제됨/타프로젝트 컴포넌트 포함)
  400   (개수 상한 초과 / 잘못된 UUID 형식)
```

## 데이터 모델 변경

```sql
-- V012__issue_components.sql (issue-tracking) + init_codegen.sql 미러
CREATE TABLE issue_components (
    issue_id     UUID        NOT NULL REFERENCES issues(id),
    component_id UUID        NOT NULL REFERENCES components(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, component_id)
);
CREATE INDEX idx_issue_components_component_id ON issue_components(component_id);
```

## 엣지 케이스

- 빈 배열 → 전부 해제(정상, S3).
- 중복 ID → distinct 정규화(S9).
- 무효/삭제/타프로젝트 컴포넌트 혼재 → 전체 422, 변경 없음(S4/S5, 원자성).
- 동시 편집 → expectedVersion 낙관락 409(S6).
- 소프트 삭제된 이슈 → 404(S8).
- 이미 같은 목록 재전송 → 200 멱등, 행 변화 없음(복합 PK).
- 컴포넌트가 할당된 상태에서 그 컴포넌트가 나중에 소프트 삭제되면? → issue_components 고아행은 남되 **읽기 시 활성 컴포넌트만 필터**(FR-6)하여 이슈 상세에서 자동 제외(Maxi 결정). FR-CM-01 삭제 경로는 issue_components 정리 안 함(고아행 무해). 후속 정리(cascade)는 별도 범위.
- 전체교체 트랜잭션 순서 → ① issues row를 `WHERE version=expectedVersion`으로 version bump(0행이면 409) ② 기존 issue_components 전체 DELETE ③ 신규 목록 INSERT. 한 `@Transactional` 안에서 원자적(담당자 updateAssignee 단일 UPDATE의 다행 버전). 동시 PATCH는 ①의 낙관락에서 직렬화.

## 제약 조건

- 절대 규칙(DEVELOPMENT.md §1) 준수. TDD red→green→refactor 강제.
- 도메인 정규화 단일 진입(service가 도메인 메서드 경유). DTO 검증은 1차 방어.
- jOOQ init_codegen 미러 누락 금지(컴파일 불가 — V005/V009 선례).
- IssueScope.Global 사용 금지(prod 무조건 거부). `IssueScope.Issue(key.value)` 사용.

## 측정 가능한 완료 기준

- [ ] V012 마이그레이션 + init_codegen 미러 → jOOQ 상수 생성 + 컴파일 통과.
- [ ] 도메인 단위 테스트: assignComponents distinct/상한, clearComponents.
- [ ] Testcontainers 통합: S1~S9(happy/부분/해제/422×2/409/404/중복) — 실 Postgres.
- [ ] 권한: prod 프로파일 통합으로 403, 비prod AlwaysAllow 200(메모리 issue-scope-global 함정 회피 검증).
- [ ] 프론트 단위: useChangeComponents mutation(422/409 onError), 셀렉터 렌더/권한 게이팅.
- [ ] E2E: 할당/부분교체/전부해제 happy path + 기존 이슈 상세 E2E 회귀 0.
- [ ] ktlintMainSourceSetCheck + detekt(issue-tracking baseline) 그린(메모리 subagent-ktlint-false-green — controller 직접 검증).

## Brainstorming Check

✅ 통과 (1회 iteration). gap 6점 점검 — (1)트랜잭션 순서 명시(엣지케이스 추가), (2)목록 N+1=단건전용 노출 확정, (3)고아행=읽기시 활성필터 확정, (4)개수상한=없음 확정, (5)이력 범위밖 확정(선례), (6)알림 이벤트 범위밖 확정(선례). 3개 Maxi 결정 반영, 2개 선례 자동결정, 1개 스펙 보강.
