# FR-TL-02 — 이슈 간 의존성 라인 (blocks 관계)

> slug: fr-tl-02-timeline-deps
> type: api
> agent: backend-engineer
> primary BC: issue-tracking (논리 FR은 agile-planning §4.2)
> 생성: 2026-06-28

## Brief

FR-TL-01에서 구현한 자체 SVG/CSS Gantt 타임라인 위에, `blocks` 관계로 연결된 이슈들을
화살표 라인으로 오버레이한다. 데이터는 FR-LK-01의 `issue_links` 테이블(blocks 관계 포함)을 활용한다.

- 백엔드: `GET /api/v1/timeline/deps?project=...` 신규 엔드포인트 — 타임라인에 표시 중인 이슈들 사이의
  blocks 의존 엣지 목록 반환
- 프론트: 자체 SVG Gantt 위에 의존 라인 SVG 오버레이 (클릭 시 강조)

선행(완료): FR-TL-01(Gantt 뷰), FR-LK-01(이슈 링크 issue_links), FR-LK-02(링크 그래프 BFS)

classify: type=api, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- **BC**: agile-planning(엔드포인트 소유) + issue-tracking(데이터 어댑터) + shared-kernel(cross-BC 포트).
  classify는 issue-tracking으로 잡았으나, 타임라인 엔드포인트는 FR-TL-01부터 agile-planning 소유.
- **영향 엔티티(전부 기존)**:
  - `TimelineItemView`(shared-kernel, FR-TL-01) — 타임라인에 보이는 이슈.
  - `IssueLink` / `LinkType.BLOCKS`(issue-tracking, FR-LK-01) — 단방향 저장(source가 target을 차단).
  - `TimelineLookupPort`(shared-kernel, FR-TL-01) — cross-BC 조회 포트, 메서드 1개 추가 예정.
- **새 용어**: 없음. glossary에 "타임라인 아이템(TimelineItem)"·"링크(Link — blocks 포함)" 이미 정의됨.
  "의존성 라인(dependency line)"은 신규 도메인 엔티티가 아니라 기존 BLOCKS 링크의 **시각화 개념**(UI).
- **신규 스키마**: 0(예상). `issue_links` 활용. 프로젝트 단위 blocks 조회 인덱스 필요 여부는 spec/plan에서 EXPLAIN 확정.
- **기존 결정 충돌**: 없음. FR-TL-01 cross-BC 포트 패턴 + FR-LK-01 blocks 링크의 자연스러운 확장.
- **핵심 결정(ADR 기록)**: FR-LK-02 그래프 엔드포인트(`/issues/{key}/graph`, 단일 이슈 중심·전체 링크 타입·mermaid)는
  목적·BC 소유·시각화가 모두 달라 **재사용하지 않고**, agile-planning에 타임라인 전용 project-scoped BLOCKS 엣지
  엔드포인트(`GET /api/v1/timeline/deps`)를 신설. 양끝 가시성 필터로 비가시 이슈 누출 차단.
- **관련 ADR**: [docs/decisions/2026-06-28-timeline-deps-blocks-overlay.md](../decisions/2026-06-28-timeline-deps-blocks-overlay.md) (생성됨)
- **grill-with-docs 스킵 사유**: 새 유비쿼터스 용어 0건 + 완료된 FR-TL-01/FR-LK-01 패턴의 파생 작업.
  대화형 도메인 검증보다 직접 정리가 적합(learnings `bts-review-plan-autoplan-overkill` 정신).

## 스펙

전체 스펙. [docs/specs/2026-06-28-fr-tl-02-timeline-deps.md](../specs/2026-06-28-fr-tl-02-timeline-deps.md)

핵심 시나리오 요약.
- `GET /api/v1/timeline/deps?project=KEY` → BLOCKS 링크 엣지 `{ blockerKey, blockedKey }` 목록 반환.
- 엣지 = 양끝 이슈가 모두 (동일 프로젝트 + 미삭제 + 타임라인 아이템(날짜 1개+) + viewer 가시) 인 blocks 링크만.
- 한쪽이라도 비가시/cross-project/날짜0 → 엣지 제외(누출·댕글링 차단). 양끝 가시성 SQL 푸시다운.
- 프론트 = 기존 자체 SVG 간트 위 의존 라인 오버레이 + 클릭 강조.

핵심 결정.
- agile-planning이 엔드포인트 소유, `TimelineLookupPort.listBlocksDepsByProject` default 메서드 확장, adapter가 실 구현.
- FR-LK-02 그래프(`/issues/{key}/graph`)는 목적·BC·시각화 달라 재사용 안 함(ADR).
- **PR 분할 = 게이트 1 Maxi 결정**: 풀스택 1 PR(권장) vs 백엔드/프론트 2 PR.

## Brainstorming Check

✅ 통과 (자체 sanity 점검, office-hours 부적합 learning `bts-spec-office-hours-mismatch` 적용).
최대 리스크 = 비가시/cross-project 이슈 키 누출 → 양끝 가시성 SQL 푸시다운으로 차단(D2). 댕글링 라인 = 양끝 타임라인 조건으로 차단. 상호 blocks 두 엣지, self-block DB CHECK 불가, truncated 상한 일관.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
