# FR-TL-02 D6/D7 — 타임라인 의존 라인(blocks 오버레이) 프론트엔드 UI + E2E

> slug: fr-tl-02-d6-d7-blocks-ui-e2e
> type: ui
> agent: frontend-engineer
> primary BC: agile-planning (프론트)
> 생성: 2026-06-29

## Brief

FR-TL-02 백엔드 D1~D5(#200)는 머지 완료. 남은 프론트 D6~D7을 구현한다.
FR-TL-01에서 만든 자체 SVG/CSS Gantt 타임라인 위에, `blocks` 관계로 연결된 이슈들을
화살표 라인으로 오버레이하고 클릭 시 강조한다.

**백엔드 계약(머지 완료, #200)**.
- `GET /api/v1/timeline/deps?project=KEY` → `{data:{deps:[{blockerKey,blockedKey}],truncated}}`
- 엣지 = 양끝 이슈가 모두 (동일 프로젝트 + 미삭제 + 타임라인 아이템 + viewer 가시)인 BLOCKS 링크만.
  비가시/cross-project/날짜0 이슈는 백엔드에서 이미 제외 → 프론트는 누출 걱정 없이 그대로 렌더.
- blockerKey=차단측(source), blockedKey=피차단측(target).
- DTO nullable 0 (blockerKey/blockedKey: String, truncated: Boolean) → @JsonInclude(NON_NULL)↔Zod drift 무관.

**기존 plan 참조**: docs/plans/2026-06-28-fr-tl-02-timeline-deps.md §"(후속 PR) 프론트 D6~D7 — 개요" (T6~T11).
**게이트 1 PR 분할 결정**: 백엔드/프론트 2 PR (백엔드 #200 종료, 이 PR이 프론트).

classify: type=ui, agent=frontend-engineer, primary_bc=agile-planning (classifier qa 오판 교정).

## 도메인 정리

- **BC**: agile-planning(프론트 소비 측). 백엔드 엔드포인트도 agile-planning(`TimelineController.getDeps`).
- **영향 엔티티(전부 기존)**: 신규 0.
  - 소비 대상 — `TimelineDepEdge{blockerKey, blockedKey}`(백엔드 DTO, #200) + `TimelineItem`(FR-TL-01 Gantt 막대).
  - 시각화 — 자체 SVG Gantt(FR-TL-01) 위 의존 라인 오버레이.
- **새 용어**: 0. glossary에 "타임라인 아이템(TimelineItem)"·"링크(Link — blocks 포함)" 이미 정의됨.
  "의존성 라인(dependency line)"은 신규 도메인 엔티티가 아니라 기존 BLOCKS 링크의 **시각화 개념**(UI) → glossary 추가 불필요.
- **새 도메인 모델 변경**: 0 (순수 프론트 작업, 백엔드 계약 무변경).
- **기존 결정 충돌**: 없음. ADR 결정(별도 project-scoped BLOCKS 엣지 엔드포인트 + 양끝 가시성 백엔드 필터)을 프론트가 그대로 소비.
  비가시/cross-project/날짜0 이슈는 백엔드에서 이미 제외되므로 프론트는 누출 판정 책임 0.
- **관련 ADR**: [docs/decisions/2026-06-28-timeline-deps-blocks-overlay.md](../decisions/2026-06-28-timeline-deps-blocks-overlay.md) (기존, 프론트 무변경) ·
  [docs/adr/2026-06-26-gantt-rendering-self-svg.md](../adr/2026-06-26-gantt-rendering-self-svg.md) (자체 SVG 렌더, FR-TL-01).
- **grill-with-docs 스킵 사유**: 신규 유비쿼터스 용어 0 + 완료된 FR-TL-01/FR-TL-02 백엔드 패턴의 파생 프론트 작업.
  대화형 도메인 검증보다 직접 정리가 적합(learnings `bts-spec-office-hours-mismatch` 정신).

## 스펙

전체 스펙. [docs/specs/2026-06-29-fr-tl-02-d6-d7-blocks-ui-e2e.md](../specs/2026-06-29-fr-tl-02-d6-d7-blocks-ui-e2e.md)

핵심 시나리오 요약.
- 자체 div Gantt(FR-TL-01) 위 **SVG 오버레이**로 blocks 의존 라인(blocker 막대 우→blocked 막대 좌 화살표) 렌더.
- 라인 클릭 강조 + 재클릭/빈영역 클릭 해제(S2/S3).
- 접힌 에픽 그룹·미존재 막대로의 라인은 미렌더(S4/EC1) — `flattenVisibleRows`로 현재 보이는 행만 대상.
- deps truncated 누락 경고(S6), deps 실패는 간트 안돌릴 best-effort(EC6).

핵심 설계.
- **세로 좌표 = GanttChart 행 배치와 동일 출처**. `flattenVisibleRows(groups, collapsedGroups)`를 추출하고 GanttChart가 실제로 사용 → drift 차단.
- 좌표 순수함수 `computeDependencyLines`(jsdom 안전, getBBox 미사용).
- 백엔드 계약(#200) 무변경, 기존 timeline 무회귀.

## Brainstorming Check

✅ 통과 (자체 sanity, office-hours 부적합 learning `bts-spec-office-hours-mismatch` 적용).
최대 리스크 = 세로 좌표가 GanttChart 행 배치와 어긋남(접기/미분류 헤더) → `flattenVisibleRows` 단일 출처화 + positive/negative control 단위 테스트(vacuous 차단).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
