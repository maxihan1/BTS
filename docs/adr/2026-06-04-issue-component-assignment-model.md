# 이슈↔컴포넌트 다중 할당 — 정규화 조인 테이블 + 전체교체 서브리소스 + 이슈 편집권 (FR-CM-02)

> 상태: 채택(Accepted)
> 날짜: 2026-06-04
> BC: issue-tracking
> 관련 FR: FR-CM-02(이슈에 다중 컴포넌트 할당). 선행 FR-CM-01(컴포넌트 CRUD) / FR-IS-03(담당자 전용 서브리소스) / FR-PM-03(컴포넌트 권한 prod resolver).

## 맥락

FR-CM-02는 한 이슈에 여러 컴포넌트(프로젝트 내 하위 영역 분류)를 붙인다. 이슈↔컴포넌트는 다대다 관계다.
구현에 앞서 (1) 저장 모델, (2) API 변경 의미론, (3) 권한 판정 주체를 정해야 한다.

기존 코드 조사(ground-truth):
- `Issue` Aggregate(`Issue.kt`)는 `assigneeId`/`labels`/`resolutionId` 등 연관 필드를 보유. 다중 컴포넌트는
  `componentIds: List<UUID>` 컬렉션 필드로 자연 확장.
- FR-IS-03 담당자가 `PATCH /api/v1/issues/{key}/assignee` 전용 서브리소스 + `expectedVersion` 낙관락 +
  `UserLookupPort` 존재검증(422) 패턴을 확립. FR-CM-02가 복제할 선례.
- 컴포넌트는 정규화 엔티티다(ADR 2026-06-04 라벨 모델: "라벨=free-form TEXT[], 컴포넌트/버전=정규화 엔티티").
  따라서 라벨의 `TEXT[]` 배열 방식이 아니라 조인 테이블이 맞다.
- prod 권한 판정기(`IdentityAccessIssuePermissionResolver`)는 `IssueScope.Global`을 무조건 거부한다
  (전역 역할 인프라 부재 — FR-PM-04 보류, MEMORY issue-scope-global-prod-hard-deny). Global 사용 금지.

## 결정

### D1 — `issue_components` 정규화 조인 테이블 (issue-tracking BC, 둘 다 실 FK)
이슈와 컴포넌트는 모두 issue-tracking 소유이므로 **같은 BC → 실 FK 적용**(reporter/assignee의 cross-BC FK 생략과 다름).

```sql
CREATE TABLE issue_components (
    issue_id     UUID NOT NULL REFERENCES issues(id),
    component_id UUID NOT NULL REFERENCES components(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, component_id)
);
CREATE INDEX idx_issue_components_component_id ON issue_components(component_id);
```

- 복합 PK `(issue_id, component_id)`가 멱등성(같은 쌍 중복 방지)을 보장 → 별도 UNIQUE 불필요.
- 역방향 조회(컴포넌트로 이슈 찾기, 컴포넌트 삭제 영향 분석)를 위해 `component_id` 인덱스 추가.
- 소프트 삭제 불필요. 할당은 연결의 존재 자체가 상태이므로 연결 해제는 행 DELETE(조인 테이블은
  DATA.md §3 소프트 삭제 대상 아님 — 도메인 엔티티가 아닌 관계). `issues`/`components` 본체는 소프트 삭제 유지.
- jOOQ init_codegen.sql에도 동일 DDL 미러(MEMORY jooq-init-codegen-mirror — 안 하면 jOOQ 상수 미생성 → 컴파일 불가).

### D2 — 전체교체(set) 의미론, 전용 서브리소스 `PATCH /issues/{key}/components`
컴포넌트 ID **전체 목록**을 받아 통째로 교체한다(Jira 편집폼 + FR-IS-03 담당자 서브리소스 동형).

- 요청: `{ componentIds: List<UUID>, expectedVersion: Long }`. 빈 배열 = 전부 해제.
- 응답: 갱신된 `IssueResponse`(componentIds 포함).
- `expectedVersion` 낙관락(담당자 선례 동형) — 동시 편집 충돌 시 409.
- 개별 추가/제거(POST/DELETE) 방식 기각: 엔드포인트 2개 + 프론트 추가 상태관리 + 동시편집 순서의존성 복잡.
  (Maxi 결정 2026-06-04.)

### D3 — 권한은 이슈 편집권 `IssuePermission.UPDATE` + `IssueScope.Project`
이슈에 컴포넌트를 붙이는 건 **이슈 필드 수정**이므로 이슈 편집권으로 판정한다(IssueController 쓰기경로 동형).

- `ComponentPermissionResolver`(FR-CM-01/PM-03)는 **컴포넌트 자체의 CRUD 관리권**이라 의미가 다름 → 사용 안 함.
- `IssueScope.Global` 사용 금지(prod 무조건 거부). `IssueScope.Project(projectKey)`로 판정.
- 비prod는 기존 `AlwaysAllowIssuePermissionResolver`가 통과, prod 실판정은 FR-PM-02 resolver가 이미 담당.
  (Maxi 결정 2026-06-04.)

### D4 — 할당 검증: 같은 프로젝트 + 활성 컴포넌트만
- 각 컴포넌트가 **이슈와 같은 프로젝트 소속 + 활성(deleted_at IS NULL)**인지 ApplicationService에서 검증.
  `ComponentRepository.findById(id, projectId)`가 소프트삭제 컴포넌트를 null 반환하므로 이를 활용.
- 위반 시 422 `COMPONENT_NOT_FOUND`(ASSIGNEE_NOT_FOUND 선례 동형). 조인 FK는 1차방어일 뿐, 프로젝트 일치는
  FK로 강제 못 하므로 서비스 검증 필수(MEMORY patch-merge-domain-bypass: 도메인/서비스 검증 우회 금지).
- 요청 배열 내 중복 ID는 도메인에서 정규화(distinct) — labels 정규화 선례 동형.

### D5 — 읽기 경로: `IssueResponse.componentIds` 노출
이슈 단건/목록 조회 응답에 `componentIds`(또는 컴포넌트 요약)를 포함해 프론트 셀렉터가 현재 상태를 렌더.
초기 구현은 ID 목록만(셀렉터가 컴포넌트 목록 api로 이름 해소). 이름까지 임베드할지는 spec에서 확정.

## 결과

- 신규 용어 없음(컴포넌트는 glossary 기존 항목). 관계 "이슈↔컴포넌트 다대다"만 명확화.
- FR-CM-02 범위: 도메인 componentIds + issue_components 마이그레이션 + 전용 서브리소스 API + 검증 가드 +
  백엔드 테스트 + 다중 셀렉터 UI + E2E.
- 컴포넌트별 기본 담당자 자동할당은 FR-CM-03 별도 범위.

## 관련

- ADR 2026-06-02 component-model-and-permission-deferral (컴포넌트 정규화 엔티티 + 권한 이연)
- ADR 2026-06-04 issue-label-freeform-tag-model (라벨=배열 vs 컴포넌트=정규화 구분)
- MEMORY: issue-scope-global-prod-hard-deny, jooq-init-codegen-mirror, patch-merge-domain-bypass
