# FR-TL-03 — 타임라인 줌 (주/월/분기)

> slug: fr-tl-03-timeline-zoom
> type: ui
> agent: frontend-engineer
> BC: agile-planning
> Plan slug(product): agile/timeline-zoom
> 생성: 2026-06-29

## Brief

FR-TL-03 진행. FR-TL-01에서 만든 자체 SVG/CSS Gantt 타임라인에 줌 레벨(주/월/분기)을 추가한다.
당시 "시간축 = 고정 일 단위 폭 + 가로 스크롤(줌은 FR-TL-03 범위 외)"로 미뤄둔 기능.

- 성격: 프론트엔드 전용 (D4/D5 백엔드 "해당 없음")
- D6: 줌 컨트롤 + 키보드 단축키 (designer → frontend-engineer)
- D7: E2E (qa-engineer)
- SDD §13.3.3: "주 / 월 / 분기 단위" (한 줄 명세 — 셀 크기/단축키/상태 보존은 spec에서 확정)

classify 정정: classify-task가 backend로 오판 → 문서 명세 근거로 ui/frontend-engineer 정정.

## 도메인 정리

- **BC**: agile-planning (타임라인은 agile-planning 소유, FR-TL 시리즈)
- **도메인 모델 영향**: 0 — 새 엔티티 0, 백엔드 0, DB 마이그레이션 0. 순수 프론트 뷰 레이어(시간축 스케일 전환).
- **새 용어**: "줌 레벨"(주/월/분기). 단, 도메인 유비쿼터스 언어가 아니라 **뷰 인터랙션 개념** → glossary 추가 불필요(타임라인 아이템은 이미 등록됨). spec에서 셀 폭·축 단위 정의.
- **기존 결정 충돌**: 없음. ADR `2026-06-26-gantt-rendering-self-svg.md` 결과 §가 "향후 FR-TL-03(줌)도 같은 자체 SVG/CSS 기반에서 확장"을 명시적으로 예고 → **연장 관계**(충돌 아님).
- **핵심 기술 컨텍스트**: FR-TL-01이 줌 인프라를 선반영함.
  - `lib/timeline-layout.ts`: `computeBarGeometry(item, range, dayWidth)` / `computeDependencyLines(rows, range, dayWidth, rowHeight, deps)` 모두 `dayWidth`를 인자로 받음(`DAY_WIDTH_PX = 20`은 기본값일 뿐).
  - 줌 = `dayWidth` 프리셋 전환 + `TimelineAxis` 눈금 단위(주/월/분기) 전환. FR-TL-02 의존성 라인도 같은 dayWidth로 자동 재계산(좌표 단일 출처) → 줌 시 라인 정합 자동 보장.
- **관련 ADR**: 신규 ADR 후보 = "줌 레벨 ↔ dayWidth/축 단위 매핑"(spec에서 프리셋 값 확정 후 작성 여부 결정).
- **grill-with-docs**: 스킵(도메인 영향 0인 순수 뷰 작업, 대화형 도메인 검증 과함).

## 스펙

전체 스펙. [docs/specs/2026-06-29-fr-tl-03-timeline-zoom.md](../specs/2026-06-29-fr-tl-03-timeline-zoom.md)

핵심 요약.
- 줌 = `GanttChart`의 `DAY_WIDTH_PX=20` 하드코딩을 `zoomLevel('week'|'month'|'quarter') → dayWidth/축단위` 매핑으로 교체. 데이터 재요청 0.
- 줌별 프리셋: 주(~28px, 월/일 축) · 월(20px 현행, 월/주 축) · 분기(~6px, 분기/월 축). 기본=월(무회귀).
- 컨트롤(Maxi 확정): 세그먼트(주|월|분기) + −/+ 버튼 / 상태=localStorage `timeline-zoom`(전역, 잘못된 값→월 폴백) / 단축키 1·2·3 직접.
- 순수 함수 분리: `lib/timeline-zoom.ts`(매핑·zoom in/out·parse·축단위) 단위 테스트, 시각은 E2E(ADR D2 정신).
- 의존성 라인(FR-TL-02)은 새 dayWidth로 자동 재계산(좌표 단일 출처). 접힘 상태·기존 E2E 무회귀.

## Brainstorming Check

✅ 통과 (1회). 자체 sanity check로 gap 3건(스크롤 보정 범위 외·i18n timeline-labels·재마운트 key 불변/리스너 cleanup) 발견 후 스펙 보강.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
