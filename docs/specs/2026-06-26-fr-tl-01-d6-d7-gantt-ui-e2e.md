# FR-TL-01 D6/D7 타임라인/로드맵 뷰 (Gantt) — 프론트엔드 UI + E2E 스펙

> slug: fr-tl-01-d6-d7-gantt-ui-e2e
> type: ui / frontend-engineer
> BC: agile-planning (프론트). 백엔드 D1~D5 = #192 완료.
> SDD: §4.1 / §13.3.1
> 작성: 2026-06-26 (직접 기술 스펙 — 완료 FR 후속, office-hours 부적합)

## 결정 요약 (Maxi 확정 2026-06-26)

- **Gantt 구현 = 자체 SVG/CSS** (의존성 0). fr-index §A.3 #2 보류 → 해소. ADR 생성(docs/adr/).
- **레이아웃 = Epic 그룹 + 마일스톤** (SDD §13.3.1 — Epic 부모 행 + 자식 행, start~due 막대, targetDate ◆ 마일스톤).
- **시간 축 = 고정 일 단위 폭 + 가로 스크롤** (FR-TL-03 줌은 별도 FR, 범위 외).

## 사용자 시나리오 (Given-When-Then)

- **S1 (happy)**: BROWSE 권한 사용자가 `/projects/{key}/timeline` 진입 → 날짜 있는 가시 이슈가 Gantt 막대로, Epic별 그룹 행으로 렌더된다.
- **S2**: 사용자가 Epic 그룹 헤더의 ▾를 클릭 → 그 그룹의 자식 행이 접힌다(다시 클릭 → 펼침).
- **S3**: 사용자가 막대(또는 행 레이블)를 클릭 → 해당 이슈 상세 `/issues/{key}` 로 이동.
- **S4**: 날짜 있는 이슈가 0건 → "표시할 일정이 없습니다" 빈 상태 안내.
- **S5**: 500개 초과(`truncated=true`) → 상단에 "일부만 표시됨" 배너.
- **S6**: BROWSE 권한 없음(403) → 접근 거부 안내(에러코드 `AGILE_ACCESS_DENIED`).
- **S7**: 미인증(401) → 기존 apiFetch 정책에 따라 로그인 흐름(자동).
- **S8**: board/backlog 페이지에서 "타임라인" 링크로 이 화면에 진입(상호 네비).

## 기능 요구사항 (FR)

- **FR1**: `GET /api/v1/timeline?project={key}` 호출(TanStack Query). 라우트 진입 시 1회 fetch.
- **FR2**: 응답 `items`(이미 startDate→dueDate→key 정렬)를 **Epic 그룹 트리로 조립**(프론트 책임).
  - Epic 그룹 헤더 = `issueType==='epic'` 인 아이템(자신도 막대 렌더).
  - 자식 = `epicKey === <그 Epic.key>` 인 아이템.
  - `epicKey` 가 목록의 어떤 Epic 과도 매칭 안 됨(Epic 이 날짜 없어 제외/cross-project) **또는** `epicKey===null && issueType!=='epic'` → **"미분류" 그룹**.
  - 그룹 내 정렬은 백엔드 정렬(startDate ASC...) 유지. 그룹 순서는 Epic 막대 정렬 기준, "미분류"는 맨 끝.
- **FR3**: 각 아이템 = **타임라인 막대**.
  - startDate+dueDate 둘 다 → `[startDate, dueDate]` 기간 막대(폭 = (dueDate−startDate+1)일).
  - startDate만 → 시작일에 최소폭(1일) 막대 + 우측 개방 표식(끝 미정).
  - dueDate만 → 마감일에 최소폭(1일) 막대 + 좌측 개방 표식(시작 미정).
  - 막대 색 = issueType 별 구분(epic/story/task/bug + 기타).
- **FR4**: `targetDate` 있으면 해당 행 위에 **마일스톤 ◆**(다이아몬드) 표식. 범위 밖이면 가장 가까운 가장자리에 클램프 + 표식으로 범위 밖임을 알림(또는 스크롤 범위에 포함되도록 전체 range 에 targetDate 도 반영).
- **FR5**: 시간 축 헤더 = 전체 range(min(startDate) ~ max(dueDate, targetDate))를 일 단위 고정 폭으로 펼치고, 주(월요일) 눈금 + 월 구분 표시. 컨테이너보다 넓으면 가로 스크롤.
  - **레이아웃 구조**: 2열 — 좌측 이슈 레이블 열(고정 폭, `sticky left-0`)은 가로 스크롤에도 고정, 우측 시간축 영역(헤더 행 + 막대 본문)은 **같은 가로 스크롤 컨테이너**에 두어 헤더 눈금과 막대 위치가 항상 정렬되도록 한다. 세로 스크롤은 전체 공유.
- **FR6**: Epic 그룹 **접기/펼치기** 토글(로컬 UI 상태). 기본 펼침.
  - Epic 헤더 행은 ▾토글(button, 좌측 레이블 열) + Epic 자신의 막대(우측 시간축)를 함께 가진다. **토글 클릭 영역(button)과 막대 클릭(이슈 상세 이동, S3) 영역을 분리**해 이벤트 충돌을 막는다(button stopPropagation). 자식 0개 Epic 은 토글 비활성/숨김.
- **FR7**: 행 레이블 = `{key} {summary}` + 담당자(assigneeId→표시, 기존 사용자 표시 패턴 재사용) + 현재 상태(currentStateKey).
- **FR8**: 로딩=Skeleton, 빈=안내, truncated=배너, 403=접근 거부, 그 외 에러=토스트/안내(board 페이지 에러 처리 패턴 재사용).
- **FR9**: board/backlog 페이지에 "타임라인" 링크 추가(상호 네비, 기존 board↔backlog 링크 패턴 확장).

## 비기능 요구사항 (NFR)

- **NFR1 (성능)**: 상한 500행(백엔드 truncate). 단순 렌더로 처리, 행 가상화는 도입 안 함(500행 SVG/CSS 허용 범위, 후속 perf 리뷰 지적 시 재검토). div/SVG 좌표 계산은 순수 함수로 O(n).
- **NFR2 (결정성)**: 막대 위치/그룹화는 순수 함수 → 단위 테스트로 픽셀 좌표 검증(jsdom width0 무관, recharts 패턴 — memory: fr-tt-02 순수함수+smoke).
- **NFR3 (접근성)**: 막대/마일스톤 aria-label(이슈 키+기간), 그룹 토글 button + aria-expanded, 키보드 포커스 가능.
- **NFR4 (날짜 안전)**: 날짜는 ISO `LocalDate` 문자열 파싱. UTC 기준 일수 계산(타임존 시프트 회피 — `Date.UTC` 사용, memory: date-input-iso-instant-query-param 정신).

## API 인터페이스 (백엔드 #192 확정 — invent 금지)

`GET /api/v1/timeline?project={key}` → `200 DataResponse<TimelineResponse>`
- `TimelineResponse { items: TimelineItemResponse[], truncated: boolean }`
- `TimelineItemResponse { key: string, summary: string, issueType: string(소문자), currentStateKey: string, assigneeId: string|null(UUID), startDate: string|null(ISO date), dueDate: string|null(ISO date), targetDate: string|null(ISO date), epicKey: string|null }`
- 에러: 401(미인증) / 403(BROWSE 없음, errorCode AGILE_ACCESS_DENIED) / 400(project 누락 — 라우트가 보장하므로 정상 흐름선 미발생).

Zod 스키마(`api/timeline.ts`)는 위 DTO를 **정확히** 미러(필드 추가/삭제 금지). issueType/currentStateKey 등 enum 은 string 으로 받고 표시 레이어에서 분기(미지 타입 폴백).

## 데이터 모델 변경

- **없음** (프론트 전용, 백엔드 #192 완료). 신규 마이그레이션 0.

## 엣지 케이스

- **EC1**: startDate만 / dueDate만 있는 이슈 → 개방형 최소폭 막대(FR3).
- **EC2**: startDate > dueDate(비정상 데이터) → 막대 폭 음수 방지(최소폭으로 클램프 + 콘솔 경고 없이 방어적 렌더).
- **EC3**: targetDate가 [rangeStart, rangeEnd] 밖 → range 계산에 targetDate 포함(FR4/FR5)하여 항상 화면 내.
- **EC4**: items 빈 배열 → 빈 상태(S4).
- **EC5**: truncated=true → 배너(S5).
- **EC6**: epicKey가 목록 어떤 Epic 과도 매칭 안 됨 → "미분류" 그룹(FR2).
- **EC7**: Epic 자신은 날짜 없어 목록에서 빠지고 자식만 있음 → 자식들은 epicKey 매칭 실패 → "미분류"(EC6과 동일 경로). (Epic 막대 부재는 정상 — 백엔드가 날짜 없는 Epic 제외.)
- **EC8**: 모든 이슈가 같은 날(rangeStart==rangeEnd) → range 폭 0 division 방지(최소 1일).
- **EC9**: 매우 긴 기간(수년) → 가로 스크롤(고정 일 폭). 가상화 없음(NFR1).
- **EC10**: 403/401/네트워크 오류 → 각 상태 안내(FR8).
- **EC11**: assigneeId 있으나 사용자 조회 실패/미배정 → "미배정" 폴백.

## 제약 조건

- BC 격리: agile-planning 프론트. 백엔드 호출은 `/api/v1/timeline` 만(이미 존재).
- 새 라이브러리 도입 0(자체 SVG/CSS). package.json 변경 없음.
- 기존 패턴 재사용: file-based route(Adapter+Page), `useProjectPermissions`(네비/접근 게이팅), i18n 라벨 파일, Skeleton, AGILE_ 에러코드, apiFetch. **FavoriteButton 미사용**(타임라인은 즐겨찾기 대상 ISSUE/FILTER/DASHBOARD/PROJECT 아님).
- **issueType 막대 색**: 기존 issueType 색/아이콘 토큰(board 카드·이슈 타입 표시)이 있으면 재사용, 없으면 timeline 전용 색 맵 신규 정의(epic/story/task/bug/기타 폴백). impl 단계 grep 으로 출처 확정.
- DEVELOPMENT.md 절대 규칙 19개 + 콜론 종결 금지(i18n ko 포함, memory: fr-mf-05 ko.test 검증).

## 측정 가능한 완료 기준

- **단위 테스트**: ① 막대 좌표 계산 순수 함수(start/due 조합·범위·클램프·EC1/2/8) ② Epic 그룹 트리 조립 순수 함수(EC6/7·정렬·미분류) ③ 날짜 range 계산(targetDate 포함).
- **컴포넌트 테스트**: GanttChart 렌더(막대 수·레이블), 그룹 접기/펼치기, 빈/truncated/403 상태, 막대 클릭→네비.
- **E2E (Playwright + MSW)**: happy path — 타임라인 진입 → Epic 그룹 + 막대 렌더 확인 → 이슈 클릭 → 상세 이동. (시각화는 실 브라우저 렌더 검증 — memory: 시각화는 E2E 실렌더.)
- **MSW 핸들러**: `GET /api/v1/timeline` 시드(Epic+자식+미분류+truncated 시나리오). 공유 store 모듈 로드 자동 시드(memory: fr-bd-01 신규 store 자동 시드).
- **검증**: pnpm lint + typecheck(tsconfig.app) + test + E2E. ko i18n 콜론 종결 0.

## ADR (생성 예정)

`docs/adr/2026-06-26-gantt-rendering-self-svg.md` — "Gantt 렌더링 = 자체 SVG/CSS (라이브러리 미도입)". fr-index §A.3 #2 해소. 후보 비교(자체 vs recharts vs frappe-gantt) + 결정 근거(의존성 0·커스터마이징·환각 위험 0·1K 규모 단순성) 기록.

## Brainstorming Check

✅ 통과 (1회 직접 sanity — 완료 FR 후속, brainstorming 스킬 부적합). gap 4건 발견 후 본문 반영.
- **G1** 레이아웃 구조(좌측 sticky 레이블 열 + 우측 시간축 단일 스크롤 동기화) → FR5 보강.
- **G2** Epic 헤더 행 토글/막대 클릭 영역 충돌 → FR6 보강(button stopPropagation·자식0 토글 숨김).
- **G3** issueType 막대 색 출처 불명 → 제약에 "기존 토큰 재사용 or 신규 정의(impl grep)" 명시.
- **G4** FavoriteButton 부적합(타임라인은 즐겨찾기 대상 아님) → 제거.

이미 커버 확인: 날짜 0/1/2개(EC1) · start>due(EC2) · targetDate 범위밖(EC3) · 빈/truncated/403(EC4/5/10) · epicKey 미매칭·날짜없는 Epic(EC6/7) · range폭0(EC8) · 타임존 시프트(NFR4 UTC) · jsdom width0(NFR2 순수함수) · cross-BC 격리 단일 엔드포인트.
