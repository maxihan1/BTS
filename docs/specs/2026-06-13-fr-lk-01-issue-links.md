# FR-LK-01 — 이슈 링크 (백엔드 D1~D5) — 스펙

> slug: fr-lk-01-issue-links · BC: issue-tracking · type: backend
> 범위: 백엔드 D1~D5 (프론트 D6/D7 후속 PR)
> 관련 ADR: docs/decisions/2026-06-13-issue-link-vs-parent-child-separation.md
> 관련 SDD: §5.7 (IssueLink), §5.8 (parent-child 계층)

## 개요

이슈 간 관계를 두 메커니즘으로 표현한다.
1. **링크(issue_links)** — `blocks / relates / duplicates / clones` 4종. 방향성 있음(relates만 대칭).
2. **parent-child(issues.parent_id)** — Subtask → 부모의 구조적 계층. 링크와 별개.

## 사용자 시나리오 (Given-When-Then)

### S1. 링크 생성 (blocks)
- **Given** BTS-1, BTS-2 두 활성 이슈가 있고
- **When** 사용자가 `POST /api/v1/issues/BTS-1/links {targetKey:"BTS-2", linkType:"blocks"}` 요청하면
- **Then** 201 + 링크 DTO 반환. BTS-1 화면엔 "blocks BTS-2", BTS-2 화면엔 "is blocked by BTS-1"로 표시(역방향 계산).

### S2. 링크 목록 조회
- **Given** BTS-1이 BTS-2를 blocks, BTS-3이 BTS-1을 blocks
- **When** `GET /api/v1/issues/BTS-1/links`
- **Then** 200 + outward["blocks BTS-2"] + inward["is blocked by BTS-3"]. 소프트 삭제된 상대 이슈 링크는 제외.

### S3. 링크 해제
- **When** `DELETE /api/v1/issues/BTS-1/links/{linkId}`
- **Then** 204. 행 DELETE(관계 테이블이라 소프트 삭제 없음).

### S4. blocks 순환 거부
- **Given** BTS-1 blocks BTS-2, BTS-2 blocks BTS-3
- **When** BTS-3 blocks BTS-1 생성 시도
- **Then** 409 — 순환(cycle) 형성 거부. 그래프 전이(transitive) 탐색.

### S5. 부모 지정
- **Given** Subtask BTS-5, 부모 후보 BTS-1
- **When** `PATCH /api/v1/issues/BTS-5/parent {parentKey:"BTS-1"}`
- **Then** 200. BTS-5.parent_id = BTS-1.id.

### S6. 부모 해제
- **When** `PATCH /api/v1/issues/BTS-5/parent {parentKey:null}`
- **Then** 200. parent_id = NULL.

### S7. 부모 순환 거부
- **Given** BTS-1 ← BTS-5(자식)
- **When** BTS-1의 부모를 BTS-5로 지정 시도
- **Then** 409 — 조상 체인 순환 거부.

## 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| FR-LK-01.1 | `issue_links` 테이블 — `link_type ∈ {blocks, relates, duplicates, clones}`, source/target UUID FK→issues |
| FR-LK-01.2 | `issues.parent_id UUID NULL REFERENCES issues(id)` 컬럼 |
| FR-LK-01.3 | 링크 생성/조회/해제 API (POST/GET/DELETE) |
| FR-LK-01.4 | parent 지정/해제 API (PATCH 2-state) |
| FR-LK-01.5 | 도메인 불변식 강제 (자기링크·중복·blocks 순환·단일부모·부모 순환·자기부모·소프트삭제 대상) |
| FR-LK-01.6 | 역방향(inverse) 라벨 계산 — 저장은 단방향 1행, 조회 시 양방향 표현 |

## API 인터페이스 (REST)

모든 엔드포인트 `/api/v1/issues/{key}` 하위. `DataResponse<T>` 래퍼. 트랜잭션은 ApplicationService `@Transactional`.

### 링크
- `POST /api/v1/issues/{key}/links` — body `{targetKey: String, linkType: String}` → 201 + `IssueLinkResponse`
- `GET  /api/v1/issues/{key}/links` → 200 + `{outward: [...], inward: [...]}`
- `DELETE /api/v1/issues/{key}/links/{linkId}` → 204

### parent-child
- `PATCH /api/v1/issues/{key}/parent` — body `{parentKey: String?}` (null=해제) → 200 + `IssueParentResponse`

> **deviation**. product §5.3.1 D4는 `POST/DELETE /links`만 명시했으나, parent-child를 parent_id로 분리한 결정(ADR)에 따라 `PATCH /{key}/parent` 엔드포인트가 추가된다. product 문서 D4 본문에 인라인 보강.

### DTO
- `IssueLinkResponse`. `{id: Long, linkType: String, direction: "outward"|"inward", label: String, otherIssue: {key, summary, statusKey}}`
- `IssueParentResponse`. `{key: String, parent: {key, summary}?}`

### 오류 코드 (LinkErrorCodes / IssueExceptionHandler)
| 상황 | HTTP | code |
|---|---|---|
| target/parent 이슈 미존재 또는 소프트삭제 | 404 | `ISSUE_NOT_FOUND` |
| 자기 자신 링크/부모 | 422 | `LINK_SELF_REFERENCE` / `PARENT_SELF_REFERENCE` |
| 중복 링크 (같은 source,target,type) | 409 | `DUPLICATE_LINK` |
| blocks 순환 | 409 | `LINK_CYCLE` |
| 부모 순환 (조상 체인) | 409 | `PARENT_CYCLE` |
| 잘못된 linkType | 400 | (Bean Validation) |
| 링크 id 미존재 | 404 | `LINK_NOT_FOUND` |

## 데이터 모델 변경

### 신규 테이블 `issue_links` (V021)
```sql
CREATE TABLE issue_links (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id  UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    target_id  UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    link_type  VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_issue_links_no_self CHECK (source_id <> target_id),
    CONSTRAINT chk_issue_links_type CHECK (link_type IN ('blocks','relates','duplicates','clones')),
    CONSTRAINT uq_issue_links UNIQUE (source_id, target_id, link_type)
);
CREATE INDEX idx_issue_links_target_id ON issue_links(target_id);  -- 역방향(inward) 조회
-- source_id 단독 조회도 잦으므로 별도 인덱스
CREATE INDEX idx_issue_links_source_id ON issue_links(source_id);
```
> SDD §5.7은 source/target을 BIGINT로 표기했으나 실제 issues.id가 UUID라 UUID로 구현(ADR deviation). id는 SDD대로 BIGINT surrogate.
> **eng-review 교정**. `created_by` 컬럼 제거 — SDD §5.7(4필드)·형제 V017(created_at만)에 없고, FR-PM-03 actorId 실추출 이연으로 placeholder 저장은 가짜 데이터. created_at만 유지(정렬용, V017 정합). 실 actor 기록은 FR-PM-03 통합 시 후속 추가.

### 신규 컬럼 `issues.parent_id` (V021)
```sql
ALTER TABLE issues ADD COLUMN parent_id UUID NULL REFERENCES issues(id);
CREATE INDEX idx_issues_parent_id ON issues(parent_id);
```
- **init_codegen.sql 미러 필수** (메모리 jooq-init-codegen-mirror) — 두 변경 모두.

## 도메인 불변식 (D5 테스트 케이스)
- 링크. (1) source≠target, (2) (source,target,type) 중복 금지(409), (3) blocks 그래프 acyclic(전환 탐색, 409), (4) 소프트삭제 이슈 대상 금지(404).
- parent. (1) 단일 부모(컬럼), (2) self-parent 금지(422), (3) 조상 체인 acyclic(409), (4) 소프트삭제 이슈 부모 금지(404).
- **hierarchy_level 위계 — 강제 안 함(Maxi 확정, Phase B).** 구조적 무결성(단일 부모·acyclic·self 금지)만 강제. 타입 계층 위계 규칙(부모 level > 자식)은 Subtask/issue-type 후속 FR로 이연(FR-IS-02 'parent_id 강제 이연' 부합).

## 엣지 케이스
- cross-project 링크 허용(같은 BC). target이 다른 프로젝트 이슈여도 OK.
- relates는 대칭 — A relates B 저장 시 B 화면에서도 "relates to A". 역방향 row 추가 저장 안 함, 조회 시 양방향 union.
- 같은 두 이슈에 서로 다른 type 링크 공존 가능(blocks + relates). uq 제약이 type 포함.
- blocks 역방향(A blocks B 있는데 B blocks A 추가) = 길이 2 순환 → 409.
- 부모 변경(이미 부모 있는데 다른 부모로 PATCH) = 덮어쓰기 허용(단일 부모 유지).
- **base 이슈 미존재/소프트삭제** — POST/GET/DELETE/PATCH 모두 `{key}` 이슈 자체가 없거나 deleted_at NOT NULL이면 404(actor 추출·리소스 조회 순서는 메모리 auth-extraction-before-resource-lookup 따름).

### 성능 가드 (eng-review)
- `listLinks`. outward/inward 각각 issues **단일 LEFT JOIN**으로 상대 이슈 summary/current_state_key 동시 조회(N+1 금지). 방향당 단일 조인이라 cartesian 무위험(메모리 cartesian-product-jooq-leftjoin-count — 다중 조인 아님).
- blocks 순환 CTE는 그래프 크기 bound(1K 규모 무problem).

## 제약 조건 / 비목표 (non-goals)
- **이력(FR-HS)·알림(notification) 미발행** — 링크/부모 변경은 본 FR에서 issue history 기록·watcher 알림 대상 아님(product D-stage 미언급, 범위 집중). 필요 시 후속 FR.
- 권한 가드. component 선례(`AlwaysAllow*PermissionResolver` placeholder)와 동일하게 placeholder resolver. 실 권한코드 게이팅은 FR-PM 통합 시 이연(기존 issue-tracking write surface 관례 일치).
- 링크 그래프 시각화는 FR-LK-02(별도).
- 프론트 UI(D6)·E2E(D7)는 후속 PR.

## 측정 가능한 완료 기준
- issue_links 테이블 + issues.parent_id 마이그레이션(V021) + init_codegen 미러.
- 4개 엔드포인트 구현 + 단위/통합 테스트(불변식 7종 cycle 케이스 포함) 그린.
- `./gradlew :modules:issue-tracking:test ktlintCheck detekt` 그린.
- jOOQ codegen 재생성 후 빌드 통과.

## Brainstorming Check

✅ 통과 (1회 iteration). 직접 기술 스펙 작성(well-specified FR — office-hours 대신, 메모리 bts-spec-office-hours-mismatch). 발견·해소한 gap.
- parent-child hierarchy_level 위계 강제 여부 → Maxi 확정 B(구조만 강제, 위계 이연).
- 자기 결정 항목(선례·범위 기반): blocks 순환=전환 탐색 409, 중복=409, self=422, target/소프트삭제=404, cross-project 허용, relates 대칭 조회 union, 이력·알림 미발행(비목표), 권한 placeholder resolver(component 선례), API에 PATCH /parent 추가(ADR deviation, product D4 보강).
