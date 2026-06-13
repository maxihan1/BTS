# FR-LK-01 — 이슈 링크 (blocks/relates/duplicates/clones/parent-child)

> slug: fr-lk-01-issue-links
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-13

## Brief

FR-LK-01 — 이슈 간 링크. blocks / relates / duplicates / clones / parent-child 관계를 이슈끼리 맺을 수 있게 한다. BC=issue-tracking, SDD §5.3.1, 우선순위 필수.

- classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- **BC**. issue-tracking
- **범위(Maxi 확정)**. 백엔드 D1~D5만. 프론트 UI(D6)·E2E(D7)는 후속 PR.
- **핵심 결정(Maxi 확정 → ADR)**. 링크와 parent-child를 **별개 메커니즘**으로 분리(옵션 B).
  - `issue_links` 테이블 — `link_type ∈ {blocks, relates, duplicates, clones}` 4종.
  - `issues.parent_id UUID NULL` 컬럼 — parent-child(Subtask → 부모) 구조적 계층(SDD §5.8 정석).

### 영향 엔티티
- **IssueLink (신규)**. `issue_links(id BIGINT identity, source_id UUID, target_id UUID, link_type VARCHAR(30), created_by UUID, created_at)`. source/target은 같은 BC(issues) → 실 FK. created_by는 users.id(타 BC) → FK 미적용, ApplicationService guard.
- **Issue.parent_id (신규 컬럼)**. `issues.parent_id UUID NULL REFERENCES issues(id)`. 단일 부모.

### 유비쿼터스 언어
- **링크(Link)**. 이슈 간 참조 관계. 방향성 있음(relates만 대칭).
- **링크 타입(LinkType)**. blocks(막음)/relates(관련)/duplicates(중복)/clones(복제).
- **역방향 표시(inverse/bidirectional, D2)**. 한 방향 row 1개만 저장, 반대쪽 이슈 화면에선 역명칭("is blocked by" 등) 계산 표시. 이중 저장 안 함(Jira 정석).
- **부모-자식(parent-child)**. 구조적 계층. `issues.parent_id`로 표현, 링크와 별개.

### 도메인 불변식 (D2/D5 cycle 케이스)
- 링크. (1) 자기 링크 금지(source≠target), (2) 같은 (source,target,type) 중복 금지, (3) **blocks 그래프 acyclic**(A blocks B blocks …blocks A 거부), (4) 소프트 삭제 이슈 대상 링크 금지.
- parent-child. (1) 단일 부모(컬럼), (2) 자기 부모 금지, (3) **조상 체인 acyclic**(부모의 조상에 자신 금지), (4) hierarchy_level 위계(자식 타입 level < 부모 타입 level — Subtask < Story/Task), (5) 소프트 삭제 이슈 부모 금지.

### deviation (SDD ↔ 코드)
- SDD §5.7 `source_id/target_id BIGINT` → 실제 `issues.id`가 UUID(V001)이므로 **UUID FK**로 구현. SDD 본문 불변, ADR + 본 plan에 기록.
- `link_type` 4종은 SDD §5.7과 일치(parent-child 제외) → SDD 데이터 모델 정정 불필요.

### 새 용어 / 기존 결정
- glossary "링크"(line 33) 이미 존재. **LinkType 4종 세부 + 역방향 표시 + parent-child(parent_id)** 항목 추가 후보(Maxi 승인 영역).
- 기존 결정 충돌. 없음. fr-index↔SDD drift는 본 ADR로 해소(분리 근거 박제).
- 관련 ADR. [docs/decisions/2026-06-13-issue-link-vs-parent-child-separation.md](../decisions/2026-06-13-issue-link-vs-parent-child-separation.md) (생성됨)
- 관련 마이그레이션. issue-tracking 다음 버전 **V021**(현재 최신 V020), init_codegen 미러 필수.

## 스펙

전체 스펙. [docs/specs/2026-06-13-fr-lk-01-issue-links.md](../specs/2026-06-13-fr-lk-01-issue-links.md)

핵심 요약.
- 링크(issue_links, 4종 blocks/relates/duplicates/clones, UUID source/target, surrogate BIGINT id) — POST/GET/DELETE `/api/v1/issues/{key}/links`.
- parent-child(issues.parent_id) — PATCH `/api/v1/issues/{key}/parent` 2-state(set/clear). 구조만 강제(단일부모·acyclic·self금지), hierarchy_level 위계 이연(Maxi 확정 B).
- 불변식 7종(자기링크·중복 409·blocks 순환 409 전이탐색·self-parent 422·부모 순환 409·소프트삭제 404·링크id 404).
- 마이그레이션 V021(issue_links 테이블 + issues.parent_id 컬럼) + init_codegen 미러.
- 비목표. 이력·알림 미발행, 권한 placeholder resolver, 프론트 D6/D7 후속.

## Brainstorming Check

✅ 통과 (1회 iteration). well-specified 백엔드 FR → 직접 기술 스펙. gap 1건(hierarchy_level 위계) Maxi 확정 B(구조만)로 해소. 나머지는 선례·범위 기반 자기 결정.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
