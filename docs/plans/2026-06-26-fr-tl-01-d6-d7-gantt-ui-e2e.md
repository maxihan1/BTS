# FR-TL-01 D6/D7 타임라인/로드맵 뷰 (Gantt) — 프론트엔드 UI + E2E

> slug: fr-tl-01-d6-d7-gantt-ui-e2e
> type: ui
> agent: frontend-engineer
> BC: agile-planning (프론트), 백엔드 D1~D5 = #192 완료
> SDD: §4.1 / §13.3.1
> 생성: 2026-06-26

## Brief

사용자 원문: "fr-tl-01 d6, d7 진행해줘"

FR-TL-01 — 타임라인/로드맵 뷰 (Gantt). 필수 우선순위. agile-planning BC. SDD §4.1 / §13.3.1.
백엔드 D1~D5 는 PR #192 로 완료 — `GET /api/v1/timeline?project={key}` 존재 (타임라인 아이템 평면 목록 + epicKey + truncated 반환).
이번 작업 = D6(프론트 Gantt 렌더) + D7(E2E). 이슈의 Start/Due/Target Date 기반 타임라인을 Gantt 막대로 시각화.

classify: type=qa 오판 → ui/frontend-engineer 교정 (E2E 키워드 오판 함정).

범위 결정(Maxi 2026-06-26):
- 신규 worktree (FR-SR-03 PR2 와 격리)
- Gantt 라이브러리는 domain/spec 단계에서 후보 조사 → ADR trade-off 제시 → Maxi 승인

## 도메인 정리

- **BC**: agile-planning (프론트엔드). 백엔드 D1~D5 = #192 완료.
- **grill-with-docs 생략 사유**: 도메인 모델(타임라인 아이템)이 백엔드 #192 + glossary 로 이미 확정. 새 용어 0. 완료된 도메인엔 대화형 검증 부적합(memory: bts-spec-office-hours-mismatch). 핵심 미결정은 도메인 언어가 아니라 Gantt 라이브러리(기술 결정) → spec ADR 로 이연.

### API 계약 (백엔드 #192 실측 — invent 금지, memory: frontend-zod-backend-dto-contract-gap)

`GET /api/v1/timeline?project={key}` → `DataResponse<TimelineResponse>` 봉투.
- `TimelineResponse { items: TimelineItemResponse[], truncated: boolean }`
- `TimelineItemResponse { key, summary, issueType, currentStateKey, assigneeId: UUID?|null, startDate: LocalDate?|null, dueDate: LocalDate?|null, targetDate: LocalDate?|null, epicKey: String?|null }`
- **issueType 은 소문자**(`epic`/`story`/`task`/`bug`) — `TimelineItemResponse.kt:18` KDoc. 프론트 enum/분기 소문자 기준.
- **날짜 = ISO `LocalDate` 문자열**(`"2026-07-20"`). production 직렬화 ISO 확정(게이트2 재리뷰 충실화). 슬라이스 테스트는 `[y,m,d]` 배열일 수 있음(memory: enablewebmvc-slice-localdate-array-serialization) — 프론트 Zod 는 production ISO 기준.
- **정렬**: startDate ASC NULLS LAST → dueDate ASC NULLS LAST → key ASC (백엔드가 이미 정렬). 프론트 재정렬 불필요.
- **권한/에러**: BROWSE 필요. 미인증 401 / BROWSE 없음 403 / `project` 누락 400 / 500개 초과 시 `truncated=true`(부분 누락 배너).

### 트리 조립 = 프론트 책임

백엔드는 평면 목록 + `epicKey` 만 반환. Epic 부모/자식(Story·Task) 그룹화는 프론트가 `epicKey` 로 조립. Epic 자신은 `issueType=epic` + `epicKey=null`. epicKey 가 가리키는 Epic 이 목록에 없을 수 있음(날짜 없는 Epic 등) → "Epic 없음/미분류" 그룹 폴백 필요.

### 새 용어 / 기존 결정 충돌

- 새 용어: **0** (타임라인 아이템 이미 glossary 등록).
- 기존 결정 충돌: **없음**.
- 관련 ADR: **Gantt 라이브러리 선택** = fr-index §A.3 #2 보류 → 이번 spec 단계에서 확정 + Maxi 승인 후 ADR 생성(docs/adr/, memory: bts-adr-dual-folder-convention).

### Gantt 라이브러리 후보 (spec ADR 에서 확정 — 현재 후보 정리만)

| 후보 | 의존성 | trade-off |
|---|---|---|
| **자체 SVG/CSS Gantt** | 0 (신규 0) | 날짜→x좌표 / 행→y 단순 기하. 완전 커스터마이징 · 환각 위험 0 · BTS 단순성 철학 부합(memory: learnings Kafka/OpenSearch 도입 금지 정신). 스크롤/줌은 직접 구현(FR-TL-03 줌은 범위 외). **유력 후보**. |
| **recharts (기존)** | 0 (이미 설치) | floating BarChart 로 Gantt 흉내. 날짜 축은 됨. 계층 그룹 행·에픽 묶음·행 레이블 커스터마이징 제약. Gantt 전용 아님. |
| **frappe-gantt 등 전용 OSS** | +1 신규 | Gantt 전용이나 React 통합 명령형(매끄럽지 않음)·새 의존성 환각 위험(memory: learnings, Maxi 확인 필수). 1K 규모 오버킬 가능. |

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
