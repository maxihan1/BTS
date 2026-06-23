# FR-DB-01 D6/D7 — 사용자 정의 대시보드 프론트엔드 UI + E2E

> slug: fr-db-01-d6-d7-dashboard-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-06-23

## Brief

FR-DB-01 사용자 정의 대시보드의 D6(프론트엔드 UI, react-grid-layout) + D7(E2E) 단계.
백엔드 D1~D5는 #176으로 완료(notification 모듈 `com.bts.notification.dashboard` 패키지).
Dashboard Aggregate(visibility PRIVATE/TEAM/ORG · OCC · layout JSONB · 소프트삭제) +
dashboard_shares + 목록/단건/생성/수정/삭제 API 제공 중.

이번 작업은 프론트엔드 전용(apps/web). 백엔드 변경 없음(있다면 same-BC view layer patch만).

classify 정정 메모: classify가 "E2E" 키워드로 qa 오분류 → Maxi가 ui/frontend-engineer 확정.

## 도메인 정리

- **BC**: notification (dashboard 패키지 — notification-dashboard BC). 프론트는 apps/web.
- **영향 엔티티**: 신규 없음. 백엔드 #176에서 Dashboard Aggregate + DashboardShare 확립. 프론트는 REST 계약 소비만.
- **새 용어**: 없음. glossary에 이미 존재 — "대시보드(Dashboard)", "공유 범위(Visibility: PRIVATE/TEAM/ORG)", "대시보드 공유(Dashboard Share)". glossary/domain 갱신 불필요.
- **스코프 경계 (★ 결정적)**: glossary 명시 — "컨테이너=FR-DB-01, 가젯 10종=FR-DB-02, URL공유/임베드=FR-DB-03". **위젯(가젯) 콘텐츠 시스템(assigned_to_me·pie_chart 등 12종)은 FR-DB-02 범위로 이번 작업 밖.** FR-DB-01 D6/D7 = 대시보드 CRUD + react-grid-layout 레이아웃 편집 + 공유 설정 UI까지. 백엔드 layout JSONB는 구조 미검증(JSON 유효성만) → 프론트가 react-grid-layout `Layout[]`({i,x,y,w,h}) 형식 정의(ADR D4 명시).
- **기존 결정 충돌**: 없음. 백엔드 ADR [docs/decisions/2026-06-22-fr-db-01-custom-dashboard.md] 존재(D4: layout=JSONB react-grid-layout {i,x,y,w,h} 명시). 프론트 ADR(그리드 라이브러리 선택)은 spec/plan에서 결정.

### 백엔드 계약 요약 (코드 실측, #176)

REST `/api/v1/dashboards` (5종):
- `POST` 201 — CreateDashboardRequest{name, description?, visibility, layout?(기본"[]"), sharedUserIds?}
- `GET ?limit&offset` 200 — DashboardPageResponse{items, total(별도COUNT), limit, offset}. 접근범위 owned∪shared(TEAM)∪ORG, updatedAt desc + id tiebreaker
- `GET /{id}` 200/404 — 권한별(PRIVATE owner만/TEAM owner+shares/ORG 전체), 미접근 404(존재숨김)
- `PATCH /{id}` 200/403/404/409 — PatchDashboardRequest{...?, version 필수(OCC)}. 3-state(null=유지, []=전체제거). owner만(403). version 불일치 409
- `DELETE /{id}` 204/403/404 — 소프트삭제, owner만, 멱등(이미삭제 404)

DashboardResponse{id, ownerId, name, description?(@JsonInclude NON_NULL), visibility(문자열), layout(JSON문자열), sharedUserIds[], createdAt, updatedAt, version}

불변식: name 1~200자, layout 유효JSON·64KB이하, sharedUserIds≤200·TEAM만유효·owner제외, visibility∈{PRIVATE,TEAM,ORG}. 에러코드 대문자 `NOTIF_DASHBOARD_*`.

### 프론트 선례/컨벤션 (조사 실측)

- **react-grid-layout 미도입** — 새 외부 의존성. @dnd-kit(칸반)·recharts(워크로그)는 있으나 그리드+리사이즈 부적합. SDD §14.1.1이 명시적으로 "그리드 레이아웃(react-grid-layout)" 지정. → **spec에서 외부 의존성 도입 Maxi 확인 필요**.
- api 컨벤션: notification BC는 apiGet(읽기, CSRF불요) / apiFetch+X-XSRF-TOKEN(readXsrfToken, 상태변경) + Zod 스키마(파일별 분리, NON_NULL은 .nullish()). 선례 `api/notification-policies.ts`, `api/user-notification-subscriptions.ts`.
- 라우트: TanStack Router code-based. router.ts(.ts, JSX불가) + routes/<route>.tsx(RouteAdapter+Page 분리). 기존 `/dashboard`(환영메시지만) 존재 → 확장 또는 `/dashboards` 신설.
- 차트 jsdom width0 우회: 순수함수 단위 + vi.mock smoke + 실렌더 E2E (recharts 선례).
- MSW: BC별 handlers+fixtures. dashboard 핸들러 미존재 → 신규 작성. 시드 stateful(모듈로드 자동시드).
- i18n: BC별 labels 파일 분리 + 콜론 종결 금지(ko.test.ts).
- E2E 드래그: PointerSensor(mouse.move/down/move>threshold/move/up). react-grid-layout도 유사.

### ★ spec 단계로 넘길 핵심 결정 (2건)

1. **react-grid-layout 외부 의존성 도입** — SDD/ADR가 명시한 정석이나 새 라이브러리이므로 Maxi 확인(DEVELOPMENT.md §외부 의존성). 대안: 순수 CSS Grid 직접 구현(노력 과다)·@dnd-kit 확장(리사이즈 없음).
2. **위젯 placeholder 처리** — FR-DB-01엔 위젯 콘텐츠가 없음(FR-DB-02). 그리드 셀에 무엇을 표시할지(빈 placeholder 타일+제목 / 레이아웃 편집 골격만 / 더미 위젯). 백엔드 layout 자유형식이므로 프론트 결정 필요.

- **관련 ADR**: [docs/decisions/2026-06-22-fr-db-01-custom-dashboard.md](../decisions/2026-06-22-fr-db-01-custom-dashboard.md) (백엔드, 충돌 없음). 프론트 그리드 라이브러리 ADR은 미작성 → spec/plan에서.

## 스펙

전체 스펙. [docs/specs/2026-06-23-fr-db-01-d6-d7-dashboard-ui.md](../specs/2026-06-23-fr-db-01-d6-d7-dashboard-ui.md)

**Maxi 확정 결정 3건 (2026-06-23)**.
1. 그리드 라이브러리 = **react-grid-layout 도입** (새 의존성, SDD §14.1.1·ADR D4 명시)
2. 위젯 = **placeholder 타일** (제목만, 콘텐츠는 FR-DB-02 범위 제외)
3. 라우트 = **`/dashboards` 신설** (목록+상세, 기존 `/dashboard` 환영 유지)

핵심 시나리오 요약.
- `/dashboards` 목록(owned∪shared∪ORG) → 생성 폼(name/visibility/공유) → `/dashboards/$id` 상세
- 상세 = react-grid-layout 12컬럼 그리드. placeholder 타일 추가/드래그/리사이즈/삭제 → 수동 "저장"(PATCH layout+version)
- 권한 게이팅(소유자만 편집, 비소유자 읽기전용) + OCC 409 처리(토스트+로컬보존)
- E2E 드래그는 PointerSensor 방식(칸반 선례), 실렌더 검증은 E2E 위임(jsdom width0)

FR 14개(FR-1~14), 엣지 10개(EC1~10). FR 총수 123 불변(D6/D7 체크박스만 마킹).

## Brainstorming Check

✅ 통과 — 명세 명확 완료 FR이라 office-hours/brainstorming 대신 직접 기술 스펙 + self-review(스펙 §9). Maxi 결정 3건으로 핵심 모호성(의존성·위젯스코프·라우트) 선해소. 누락 gap 없음. 과설계 항목(더미위젯·자동저장·모드토글) self-review에서 배제.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
