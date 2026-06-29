# 14. 대시보드 및 리포트

## 14.1 대시보드 (FR-DB)

### 14.1.1 구성
- 그리드 레이아웃 (react-grid-layout)
- 가젯을 자유 배치/리사이즈
- 개인 / 팀 / 공유 대시보드

### 14.1.2 공유 범위
- PRIVATE - 본인만
- TEAM - 지정 사용자
- ORG - 조직 전체
- PUBLIC - 비로그인 (URL 토큰)

## 14.2 표준 가젯 카탈로그 (FR-DB-02)

### 이슈 관련
- `assigned_to_me` - 내 할당 이슈
- `filter_result` - 저장된 필터 결과
- `issue_count` - 필터 결과 건수 (큰 숫자)
- `recently_created` - 최근 생성된 이슈

### 차트
- `pie_chart` - 필드별 분포 (담당자, 상태, 우선순위)
- `bar_chart` - 필드별 막대
- `created_vs_resolved` - 생성 vs 해결 추이
- `sprint_burndown` - 번다운

### 활동
- `activity_stream` - 최근 활동 피드
- `comments_recent` - 최근 댓글

### 정적
- `text_widget` - 마크다운 텍스트
- `link_list` - 링크 모음

## 14.3 가젯 추가/편집

> **구현 deviation (FR-DB-02, ADR `2026-06-29-fr-db-02-gadget-system`)**. 가젯은 별도 엔티티/테이블이 아니라 `dashboards.layout` JSONB 배열 항목으로 임베드된다. 아래 `data class Gadget`은 초기 설계 표기이며, 실제로는 layout 항목(`{ i, x, y, w, h, gadgetType, config }`)에 인라인된다. `gadgetType`은 notification BC의 `GadgetType` enum(12종, 카탈로그 단일 출처)으로 검증한다.

```jsonc
// dashboards.layout JSONB 배열의 한 항목 (가젯)
{
  "i": "g1",            // 인스턴스 키 (배열 내 유일)
  "x": 0, "y": 0,        // 그리드 위치
  "w": 4, "h": 3,        // 그리드 크기
  "gadgetType": "assigned_to_me",  // 미지정 시 legacy 타일
  "config": { }          // 타입별 설정 (형식만 검증)
}
```

UI: 가젯 카탈로그 모달(`GET /api/v1/dashboards/gadget-catalog`로 타입·설정 스키마 조회) → 드래그하여 추가.

## 14.4 가젯 데이터 fetch

각 가젯은 자체 데이터 쿼리:
- `assigned_to_me` → `GET /api/v1/issues?assignee=me&status!=Done`
- `filter_result` → `POST /api/v1/search` (저장된 AQL)
- `pie_chart` → `POST /api/v1/aggregate?groupBy=...`

TanStack Query로 캐시 + 자동 refetch.

## 14.5 대시보드 임베드 (FR-DB-03)

```html
<iframe src="https://atlas/dashboards/{id}?embed=true&token=..." />
```

토큰 기반 익명 접근. 임원 보고용.

## 14.6 다음 챕터

- 데이터 모델 → [05. 데이터 모델](05-data-model.md)
- 프론트엔드 구현 → [21. 프론트엔드 아키텍처](21-frontend.md)
