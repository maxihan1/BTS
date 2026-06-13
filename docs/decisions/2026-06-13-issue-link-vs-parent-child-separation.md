# ADR — 이슈 링크와 parent-child 계층의 분리 (FR-LK-01)

> 날짜. 2026-06-13
> 상태. 채택(Accepted)
> 관련 FR. FR-LK-01 (이슈 링크), FR-IS-02 (이슈 타입 — parent_id 예고)
> BC. issue-tracking
> 관련 SDD. §5.7 (IssueLink), §5.8 (Epic/Subtask 계층)

## 맥락

FR-LK-01 제목(`fr-index.md`)은 링크 종류를 `blocks/relates/duplicates/clones/parent-child` 5종으로 나열한다. 그러나 SDD 데이터 모델 §5.7의 `IssueLink.link_type`은 `blocks/relates/duplicates/clones` 4종만 정의하고, parent-child는 §5.8에서 별도로 `Issue.parent_id`(Subtask → 부모 Story/Task)로 모델링한다. 즉 **fr-index 제목과 SDD 데이터 모델이 drift** 상태였다.

또한 FR-IS-02 D2(PR #36)는 "Epic-Subtask 계층은 `hierarchyLevel` 메타데이터로, **parent_id 강제는 후속 FR**"로 명시해, parent_id 도입을 의도적으로 미뤄 두었다. FR-LK-01이 그 "후속 FR" 후보다.

구현 시작 시점 코드 상태. `issue_links` 테이블 없음, `issues.parent_id` 컬럼 없음(greenfield). `issues.id`는 UUID.

## 결정

**링크(reference link)와 parent-child(구조적 계층)를 별개 메커니즘으로 분리한다.** (Maxi 확정, 2026-06-13)

1. **`issue_links` 테이블** — `link_type ∈ {blocks, relates, duplicates, clones}` 4종만. SDD §5.7과 일치.
2. **`issues.parent_id` 컬럼** — parent-child(Subtask → 부모) 구조적 계층. SDD §5.8 정석.

근거.
- **Jira 정확성**. Jira는 이슈 링크(blocks/relates/…)와 서브태스크 부모(structural parent)를 완전히 분리한다. 링크 패널로 부모-자식을 만들 수 없다. BTS는 Atlas Issues로 Jira 모델을 따른다(완제품 기준).
- **단일 부모 자연 강제**. `parent_id` 컬럼은 컬럼 하나라 "자식은 부모 하나" 불변식을 스키마 수준에서 자연 강제. 링크 테이블에 넣으면 부분 unique 제약 + 애플리케이션 가드를 별도로 짜야 한다.
- **계층 의미 보존**. parent-child는 hierarchy_level 위계(Subtask < 부모) 제약이 있어 일반 참조 링크와 의미가 다르다. 같은 테이블에 섞으면 의미가 흐려진다.
- **SDD 데이터 모델 무변경**. 옵션 B는 SDD §5.7/§5.8과 그대로 일치 → SDD 데이터 모델 정정 불필요.

## 폐기한 대안

- **A. parent-child를 5번째 link_type으로 통합.** fr-index 제목과 일치하고 단일 테이블로 단순하나, 단일 부모/계층 위계 강제가 약하고 SDD §5.8·Jira 의미와 어긋난다.
- **C. parent-child를 FR-LK-01 범위에서 제외(4종만), 별도 Subtask FR로 분리.** 링크 테이블은 깨끗해지나 fr-index/SDD 제목 변경 → verify-master-plan 전수 동기화 비용 발생, FR-IS-02가 예고한 parent_id 후속 FR이 미아가 된다.

## deviation 기록 (SDD ↔ 코드)

- **SDD §5.7 `IssueLink.source_id/target_id` 타입이 `BIGINT`로 명시**되어 있으나 실제 `issues.id`는 `UUID`(V001). → `issue_links`의 source/target은 **UUID FK**로 구현한다. 코드가 정본(learnings 2026-05-20 phantom 엔티티 원칙). SDD 본문은 불변(api-design 정본 유지), 본 ADR + product 인라인에 deviation 기록.
- `issue_links.id`는 SDD대로 BIGINT identity(링크는 외부 영구 인용 대상 아님 — 이슈 키와 달리 surrogate 키로 충분).

## 영향

- 데이터 모델. 신규 `issue_links`(UUID source/target, link_type 4종) + `issues.parent_id UUID NULL` 컬럼. `init_codegen.sql` 미러 필수.
- 도메인. `LinkType` enum(4종), 방향성/역방향 표시, parent-child 계층 불변식(단일 부모·acyclic·hierarchy_level 위계).
- 범위. 이번 PR은 백엔드 D1~D5. 프론트 UI(D6)·E2E(D7)는 후속 PR.
- cycle 검출(FR-LK-01 D2). blocks 그래프 acyclic + parent_id 조상 체인 acyclic 두 갈래로 적용.
