# ADR — Epic↔자식 연결을 별도 epic_id 컬럼으로 (FR-EP-01)

> 날짜. 2026-06-22
> 상태. 채택(Accepted)
> 관련 FR. FR-EP-01 (Epic 이슈 타입 + 자식 이슈 연결), FR-EP-02 (Epic 진행률 집계, 후속)
> BC. issue-tracking
> 관련 SDD. §5.1 (issues.epic_id 예약), §5.8 (Epic 관리)
> 관련 ADR. [[2026-06-13-issue-link-vs-parent-child-separation]] (링크 vs parent-child 분리)

## 맥락

`epic`은 이미 표준 이슈 타입(V003)이며 `hierarchy_level=1`(최상위, V005)이다. 그러나 "어떤 이슈가 이 Epic에 속한다"는 **연결 메커니즘**은 미구현이었다.

기존 자기참조 컬럼 `issues.parent_id`(V021, FR-LK-01)는 ADR 2026-06-13에서 **Subtask → 부모 Story/Task** 구조적 계층 전용으로 확정됐다. `issue_links`는 같은 ADR에서 `blocks/relates/duplicates/clones` 4종으로 닫혔다.

FR-EP-01 product D3 명세는 데이터 모델을 "`issues.epic_id` 또는 `issue_links` 활용"으로 열어 뒀다. 한편 SDD §5.1은 `epic_id BIGINT FK NULL — 소속 Epic (FR-EP-01)`을 이미 예약하고, SDD §5.8은 `Issue.epic_id`(자식→Epic)와 `Issue.parent_id`(Subtask→부모)를 **별도**로 명시한다.

구현 시작 시점 코드 상태. `issues.epic_id` 컬럼 없음. `issues.parent_id UUID NULL`(V021) 존재. `issues.id`는 UUID(V001). 최신 마이그레이션 V027.

## 결정

**Epic↔자식 연결을 별도 `issues.epic_id` 컬럼으로 구현한다.** (Maxi 확정, 2026-06-22)

```sql
ALTER TABLE issues ADD COLUMN epic_id UUID NULL REFERENCES issues(id);
CREATE INDEX idx_issues_epic_id ON issues(epic_id);
```

- `parent_id`(Subtask→부모)와 **완전히 별개**의 자기참조 FK. 한 이슈는 (선택적) 부모 1개 + (선택적) 소속 Epic 1개를 독립적으로 가질 수 있다.
- V021의 `parent_id` 패턴을 그대로 미러(UUID NULL · 자기참조 실 FK · `ON DELETE` 기본(NO ACTION) · FK 인덱스).

근거.
- **SDD §5.8/§5.1 정석**. SDD가 epic_id와 parent_id를 이미 별도 컬럼으로 정의 → 데이터 모델 정정 불필요.
- **Jira parity**. Jira는 Epic Link(자식→Epic)와 Subtask parent(자식→부모)를 분리한다. BTS는 Atlas Issues로 Jira 모델을 따른다(완제품 기준).
- **ADR 2026-06-13 분리 원칙 일관**. 링크/구조계층을 별개로 둔 결정과 같은 방향. Epic 소속도 별개 차원.
- **단일 Epic 자연 강제 + 의미 보존**. 컬럼 하나라 "자식은 Epic 하나" 불변식을 스키마 수준에서 강제. parent_id에 섞으면 Subtask 계층과 Epic 소속이라는 두 의미가 한 컬럼에 혼재한다.

## 도메인 불변식

- **자식 자격**. `epic_id`는 `hierarchy_level=0`(story/task/bug) 이슈에만 부여한다. Epic(level 1)은 epic_id를 가질 수 없다(Epic-of-Epic 금지). Subtask(level -1)는 epic_id 직접 부여 금지 — 부모 Story를 통해 Epic에 간접 소속(Jira 모델).
- **대상 타입**. `epic_id`가 가리키는 이슈는 반드시 Epic 타입(`hierarchy_level=1`)이어야 한다.
- **순환 불가**. epic_id는 level 0 → level 1 단방향만 가리키므로 사이클이 구조적으로 불가능(추가 가드 불요).
- **단일 부모/단일 Epic**. parent_id·epic_id 각각 컬럼 1개 → 다중 불가.
- **같은 프로젝트**(spec 단계 확정 예정). Jira는 Epic과 자식이 같은 프로젝트. 1차는 동일 프로젝트 제약을 기본으로 한다(spec에서 명문화).

## 폐기한 대안

- **B. 기존 parent_id 재사용(모던 Jira 단일 parent 계층).** 컬럼 하나로 Story→Epic·Subtask→Story 통일하나 **SDD §5.8(별도 컬럼 명시)과 충돌**하고, parent_id 한 컬럼에 "서브태스크 계층"과 "Epic 소속" 두 의미가 혼재한다. FR-EP-02 진행률 집계 시 2계층(Epic 직속 Story + 그 Story의 Subtask)이 같은 컬럼으로 섞여 "직속 자식" 정의가 모호해진다.
- **C. issue_links에 'epic' link_type 추가.** ADR 2026-06-13의 4종 한정 결정을 되돌려야 하고, 단일 Epic 강제·계층 위계 강제가 약하며, fr-index/SDD 전수 동기화(verify-master-plan) 비용이 발생한다.

## deviation 기록 (SDD ↔ 코드)

- **SDD §5.1 `epic_id` 타입이 `BIGINT FK`로 명시**되어 있으나 실제 `issues.id`는 `UUID`(V001) → `epic_id`는 **UUID FK**로 구현한다. parent_id·issue_links와 동일한 deviation(ADR 2026-06-13). SDD 본문 불변(데이터 모델 정본 유지), 본 ADR + product 인라인에 deviation 기록.
- **hierarchy_level 값**. SDD §5 본문은 `0=Subtask, 1=Standard, 2=Epic`로 표기하나 실제 코드(V005)는 `epic=1, story/task/bug=0, subtask=-1`. 코드가 정본(learnings 2026-05-20 phantom 원칙). 본 ADR의 불변식은 코드 값(epic=1, 자식=0) 기준.
- **단건 IssueResponse.epic 노출의 보안등급 무필터(의도적)**. 자식 단건 조회 시 노출하는 소속 Epic 요약(epicKey + summary)은 parent self-join(IssueRepository.kt:522)과 동형으로 `deleted_at`만 필터하고 보안등급(security level) 필터는 적용하지 않는다. 자식 단건 진입 자체가 자식 VIEW(404-hide)로 보호되므로 무권한 진입은 차단된다. parent 선례와의 일관성을 위해 의도적으로 수용(보안 plan-review C2). 자식 **목록**(GET epic-children)은 이와 별개로 board 동형 BROWSE 진입 + accessibleLevels 푸시다운으로 누출을 차단한다.

## 영향

- 데이터 모델. `issues.epic_id UUID NULL`(V028) + `idx_issues_epic_id` + `init_codegen.sql` 미러 필수(jooq-init-codegen-mirror).
- 도메인. `Issue.epicId` 필드 + 자격/대상타입 불변식 가드. epic_id 설정/해제 시 도메인 메서드(updateFields 경로 또는 전용 mutation, spec 단계 확정).
- API. `POST /api/v1/issues/{key}/epic-children`(자식 연결), 해제/조회 엔드포인트는 spec 단계 확정. FR-EP-02 `GET /api/v1/epics/{key}/progress`는 epic_id 직속 자식 기준.
- 범위. 이번 PR은 백엔드 D1~D5. 프론트(D6 Epic 페이지 + 자식 목록)·E2E(D7)는 후속 PR(fr-ep-01-d6-d7-*).
- FR 카운트. FR-EP-01은 기존 FR이라 총수 123 불변.
