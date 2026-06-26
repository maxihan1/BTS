# ADR — Gantt 렌더링 = 자체 SVG/CSS (라이브러리 미도입) (FR-TL-01)

> 날짜: 2026-06-26
> 상태: 채택
> 관련 FR: FR-TL-01 (타임라인/로드맵 뷰 — Gantt)
> 관련 SDD: §4.1, §13.3.1
> 관련 PR: #194 (D6/D7 프론트), #192 (D1~D5 백엔드)
> 해소: fr-index §A.3 미해결 결정 #2 ("Gantt 라이브러리 — 자체 SVG vs Recharts vs syncfusion")

## 맥락

FR-TL-01 D6/D7은 백엔드 `GET /api/v1/timeline`(#192)이 반환하는 타임라인 아이템(이슈별 start/due/target date + epicKey)을 Gantt 막대로 시각화하는 프론트엔드 작업이다. fr-index §A.3 #2가 "Gantt 라이브러리 선택"을 미해결 결정으로 남겨두었다(보류 — PoC ADR).

현재 `apps/web`의 시각화 라이브러리는 `@dnd-kit`(보드 드래그), `mermaid`(워크플로우/링크 그래프), `recharts`(워크로그 집계 차트)뿐이며, Gantt 전용 라이브러리는 없다. 레이아웃 결정(Maxi 확정)은 "Epic 그룹 + 자식 행 + start~due 막대 + targetDate 마일스톤, 고정 일 단위 축 + 가로 스크롤"이다(줌은 FR-TL-03 별도 FR로 범위 외).

## 결정

### D1. Gantt 렌더링은 자체 SVG/CSS로 구현한다 (외부 라이브러리 미도입)

후보를 비교한다.

| 후보 | 의존성 | 평가 |
|---|---|---|
| **자체 SVG/CSS (채택)** | 0 (신규 0) | 날짜→x좌표 / 행→y의 단순 기하. 완전 커스터마이징(Epic 그룹 행·한국어 레이블·권한/상태 표시·접근성). 환각 위험 0. BTS 단순성 철학 부합. |
| recharts (기존) | 0 (이미 설치) | floating BarChart 로 Gantt 흉내 가능하나 Gantt 전용 아님. 계층 그룹 행·에픽 묶음·행 레이블 커스터마이징 제약. jsdom width0 테스트 함정. |
| frappe-gantt 등 전용 OSS | +1 신규 | Gantt 전용이나 React 통합이 명령형(매끄럽지 않음). 신규 의존성 환각 위험(learnings — 새 라이브러리 Maxi 확인 필수). 1K 규모 오버킬 가능. |

**근거**.
- **의존성 0**. Gantt 막대 배치는 "날짜를 픽셀 x좌표로 매핑, 이슈를 행 y로 매핑"하는 단순 기하다. 외부 라이브러리의 추상화 없이 순수 함수 + div/SVG로 충분하다.
- **완전한 커스터마이징**. Epic 부모/자식 그룹 행(접기/펼치기), targetDate 마일스톤 ◆, 개방형 막대(날짜 1개), issueType 색 구분, 한국어 레이블, aria 접근성, 권한/상태 표시 — 전용 라이브러리의 테마/슬롯 제약 없이 직접 제어한다.
- **BTS 단순성 철학**. learnings의 "Kafka/OpenSearch 도입 금지(1K 규모 오버킬)" 정신과 동일 — 더 강력한 도구의 유혹보다 규모에 맞는 최소 해법. 새 라이브러리는 환각·버전 함정·번들 비용을 동반한다.
- **테스트 용이성**. 좌표 계산/그룹 조립을 순수 함수로 분리해 단위 테스트한다(jsdom의 `getBBox`/width 0 미구현 함정 회피). 실제 시각 렌더는 E2E(Playwright 실브라우저)에 위임한다.

### D2. 좌표/그룹 로직은 순수 함수로 분리한다

`lib/timeline-layout.ts` — `computeDateRange`(targetDate 포함), `computeBarGeometry`(start/due 조합·클램프·UTC 일수 계산), `assembleEpicGroups`(Epic 트리 조립·미분류). 컴포넌트(`components/timeline/GanttChart.tsx`)는 이 순수 함수 결과를 div/SVG로 렌더만 한다.

**근거**. jsdom은 SVG 측정 API(`getBBox`, 레이아웃 width)를 구현하지 않아 컴포넌트 테스트에서 픽셀을 직접 검증할 수 없다(learnings — mermaid/recharts 동일 함정). 좌표 로직을 순수 함수로 두면 픽셀을 결정적으로 단위 테스트하고, 시각 렌더는 E2E가 보증한다.

## 결과

- `apps/web/package.json` 변경 0 (신규 의존성 없음).
- fr-index §A.3 #2 해소(보류 → 자체 SVG/CSS 채택).
- 향후 FR-TL-02(의존성 라인)·FR-TL-03(줌)도 같은 자체 SVG/CSS 기반에서 확장한다.

## 대안 (기각)

- **recharts 재사용** — 이미 설치되어 의존성 0이나, Gantt 전용이 아니라 계층 그룹/에픽 묶음/행 레이블 커스터마이징에 제약. 채택한 자체 구현이 더 단순하고 제어력이 크다.
- **frappe-gantt/dhtmlx/syncfusion** — Gantt 전용 기능(의존성 라인·줌)을 제공하나 1K 규모엔 오버킬이고, React 통합 명령형 + 신규 의존성 환각 위험. 범위(FR-TL-01) 대비 비용 과다.
