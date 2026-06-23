# FR-DB-01 D6/D7 — 사용자 정의 대시보드 프론트엔드 UI + E2E — 스펙

> slug: fr-db-01-d6-d7-dashboard-ui
> type: ui / frontend-engineer
> 작성: 2026-06-23
> 백엔드 계약: #176 확립 (notification 모듈 `com.bts.notification.dashboard`)
> 백엔드 ADR: docs/decisions/2026-06-22-fr-db-01-custom-dashboard.md

## 0. 스코프 / Maxi 확정 결정

이 작업은 **프론트엔드 전용**(apps/web). 백엔드 변경은 same-BC view layer patch가 불가피할 때만 허용(현재 불필요 예상).

Maxi 확정 (2026-06-23).
1. **그리드 라이브러리 = react-grid-layout 도입** — SDD §14.1.1·ADR D4 명시. 새 외부 의존성 1개.
2. **위젯 = placeholder 타일** — FR-DB-01은 컨테이너 + 레이아웃 시스템까지. 그리드 셀은 "제목만 있는 빈 타일". 실제 위젯 콘텐츠(이슈 목록·차트 등 10종)는 **FR-DB-02 범위로 제외**.
3. **라우트 = `/dashboards` 신설** — `/dashboards`(목록) + `/dashboards/$id`(상세/편집). 기존 `/dashboard`(로그인 후 환영 페이지)는 그대로 유지.

스코프 **밖** (FR-DB-02/03): 위젯 타입별 콘텐츠 렌더(이슈/차트/활동), 위젯 설정(config), URL 토큰 공유·임베드, PUBLIC visibility.

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 대시보드 목록 조회
- **Given** 로그인한 사용자가 네비게이션에서 "대시보드"를 클릭
- **When** `/dashboards` 진입
- **Then** 본인 소유 ∪ 본인에게 공유된(TEAM) ∪ ORG 대시보드가 카드/리스트로 표시된다. 각 항목은 이름·설명·visibility 배지·소유 여부를 보여준다. 목록이 비면 빈 상태(empty state) + "대시보드 만들기" CTA.

### S2. 대시보드 생성
- **Given** `/dashboards`에서 "대시보드 만들기" 클릭
- **When** 이름(필수, 1~200자) + 설명(선택) + visibility(PRIVATE/TEAM/ORG) 입력, TEAM 선택 시 공유 사용자 지정 후 생성
- **Then** `POST /api/v1/dashboards` 호출(layout 기본 `[]`), 성공 시 생성된 `/dashboards/$id` 상세로 이동. 목록 캐시 무효화.

### S3. 대시보드 상세 + 레이아웃 편집 (핵심)
- **Given** 소유자가 `/dashboards/$id` 진입
- **When** "위젯 추가"로 placeholder 타일을 추가하고, 타일을 드래그해 위치를 옮기거나 모서리를 잡아 크기를 바꾸고, "저장" 클릭
- **Then** react-grid-layout이 `Layout[]`({i,x,y,w,h})을 산출 → `PATCH /api/v1/dashboards/$id`에 layout(+version) 전송. 성공 시 version+1 반영. 저장 전 변경은 "변경됨" 표시(dirty state).

### S4. 타일 관리
- **Given** 편집 모드의 소유자
- **When** 타일의 제목을 인라인 편집하거나, 타일 삭제 버튼을 누름
- **Then** 로컬 layout state가 갱신(저장 전까지 미반영). 삭제 시 해당 item이 그리드에서 제거.

### S5. 대시보드 메타/공유 편집
- **Given** 소유자가 상세에서 "설정" 열기
- **When** 이름·설명·visibility·공유 사용자 변경 후 저장
- **Then** `PATCH`로 변경 필드만 전송(3-state: 미변경 필드는 생략). visibility를 TEAM 외로 바꾸면 공유 입력 비활성. 빈 공유 목록 전송 = 전체 해제.

### S6. 대시보드 삭제
- **Given** 소유자
- **When** "삭제" → 확인 다이얼로그 승인
- **Then** `DELETE /api/v1/dashboards/$id`(204). 목록으로 이동 + 캐시 무효화. (백엔드 소프트삭제)

### S7. 비소유자 읽기 전용
- **Given** TEAM 공유 받은 사용자 또는 ORG 사용자가 본인 소유 아닌 대시보드 상세 진입
- **When** 페이지 렌더
- **Then** 그리드는 읽기 전용(드래그/리사이즈/추가/삭제/저장 불가, 버튼 숨김). 메타 정보만 조회.

### S8. 동시 편집 충돌 (OCC)
- **Given** 소유자가 오래된 version으로 저장 시도
- **When** `PATCH`가 409 NOTIF_DASHBOARD_CONFLICT 반환
- **Then** 토스트로 "다른 곳에서 수정됨, 새로고침 필요" 안내. 로컬 변경 보존, 자동 덮어쓰기 금지.

## 2. 기능 요구사항 (FR)

- **FR-1** `/dashboards` 목록 — `GET`(limit/offset), 카드 표시, 빈 상태, visibility 배지, 소유 여부 표시.
- **FR-2** 대시보드 생성 폼 — name(필수 1~200), description(선택), visibility, TEAM 시 공유 사용자 선택. `POST`.
- **FR-3** `/dashboards/$id` 상세 — react-grid-layout 12컬럼 그리드, layout 렌더.
- **FR-4** 타일 추가/삭제/이동/리사이즈 — 로컬 layout state. 추가 시 고유 `i` 생성, 기본 위치/크기.
- **FR-5** 레이아웃 저장 — `PATCH`(layout + version). dirty 표시, 저장 후 version 갱신.
- **FR-6** 메타/공유 편집 — 3-state PATCH. visibility↔공유 입력 연동.
- **FR-7** 삭제 — 확인 다이얼로그 + `DELETE`.
- **FR-8** 타일 제목 인라인 편집.
- **FR-9** 권한 게이팅 — `ownerId == 현재 사용자`일 때만 편집/삭제/저장/추가 UI 노출. 비소유자 읽기 전용.
- **FR-10** OCC 409 처리 — 토스트 + 로컬 변경 보존.
- **FR-11** 네비게이션 — Header에 "대시보드" 링크 추가(`/dashboards`).
- **FR-12** api 모듈 — `api/dashboards.ts`(Zod 스키마 + apiGet/apiFetch + readXsrfToken). notification BC 컨벤션 준수.
- **FR-13** TanStack Query 훅 — useDashboards(목록)/useDashboard(단건)/생성·수정·삭제 mutation(캐시 무효화).
- **FR-14** MSW 핸들러/fixture — dashboard CRUD 핸들러(stateful, OCC 시뮬레이션 포함) + 시드.

## 3. 비기능 요구사항 (NFR)

- **NFR-1** react-grid-layout React 19 호환 — peer dependency 경고/런타임 오류 확인. 충돌 시 plan에서 호환 버전 고정 또는 `WidthProvider` 패턴 검증. (impl 검증 항목)
- **NFR-2** jsdom 제약 — react-grid-layout은 width 측정 의존. 단위 테스트는 순수 로직(layout 변환/권한 판정) + 컴포넌트 smoke만, **실제 드래그/리사이즈는 E2E**(recharts·@dnd-kit 선례 동일).
- **NFR-3** i18n — `i18n/dashboard-labels.ts`(BC별 분리), 콜론 종결 금지(ko.test.ts 검증).
- **NFR-4** CSRF — 상태 변경(POST/PATCH/DELETE)은 X-XSRF-TOKEN(readXsrfToken). 읽기는 apiGet.
- **NFR-5** Zod ↔ 백엔드 DTO 정합 — `@JsonInclude(NON_NULL)` 필드(description)는 `.nullish()`. layout은 string(JSON), 프론트가 파싱.
- **NFR-6** 타입 안전 — TypeScript strict, `any` 금지. layout item 타입 명시.
- **NFR-7** 접근성 — 타일 제목/버튼 aria-label, 키보드 포커스. (드래그 키보드는 best-effort)

## 4. 데이터 모델 (프론트 정의)

백엔드 layout은 자유형식 JSON 문자열. 프론트가 react-grid-layout `Layout[]` 형식을 정의.

```ts
// 대시보드 타일 = react-grid-layout Layout item + placeholder 메타
interface DashboardTile {
  i: string      // 고유 식별자 (타일 추가 시 생성)
  x: number      // 그리드 x (0~11, 12컬럼)
  y: number      // 그리드 y (행)
  w: number      // 너비 (컬럼 수)
  h: number      // 높이 (행 수)
  title: string  // placeholder 제목 (FR-DB-02에서 type/config 확장 예정)
}
// layout(JSON 문자열) <-> DashboardTile[] 직렬화/역직렬화는 순수 함수로 분리(테스트 대상)
```

- DashboardResponse(백엔드): `{ id, ownerId, name, description?, visibility, layout(string), sharedUserIds[], createdAt, updatedAt, version }`
- 파싱 실패/빈 문자열 → 빈 배열로 폴백(방어). 알 수 없는 필드는 보존하지 않아도 됨(FR-DB-01 범위).

## 5. API 인터페이스 (소비 — 백엔드 #176 확립)

| 동작 | 메서드 | 경로 | 비고 |
|---|---|---|---|
| 목록 | GET | `/api/v1/dashboards?limit&offset` | DashboardPageResponse |
| 단건 | GET | `/api/v1/dashboards/{id}` | 404=존재숨김 |
| 생성 | POST | `/api/v1/dashboards` | 201, layout 기본 "[]" |
| 수정 | PATCH | `/api/v1/dashboards/{id}` | version 필수, 3-state, 409 |
| 삭제 | DELETE | `/api/v1/dashboards/{id}` | 204, 소프트삭제 |

에러코드(대문자): `NOTIF_DASHBOARD_INVALID`(400) / `_FORBIDDEN`(403) / `_NOT_FOUND`(404) / `_CONFLICT`(409). 미인증 401.

## 6. 엣지 케이스

- **EC1** 빈 목록 → empty state + 생성 CTA.
- **EC2** 빈 레이아웃(타일 0) → "위젯을 추가하세요" 안내, 저장 가능.
- **EC3** 비소유자 상세 → 읽기 전용(모든 편집 UI 숨김, FR-9).
- **EC4** OCC 409 → 토스트 + 로컬 보존(자동 덮어쓰기 금지, FR-10).
- **EC5** 삭제된/미접근 대시보드 직접 URL 진입 → 404 → "찾을 수 없음" + 목록 링크.
- **EC6** layout JSON 파싱 실패(손상 데이터) → 빈 배열 폴백 + 콘솔 경고(크래시 금지).
- **EC7** visibility=TEAM인데 공유 0명 → 허용(백엔드 허용), 소유자만 접근.
- **EC8** name 빈 값/201자 → 클라이언트 검증으로 제출 차단 + 인라인 에러(백엔드 400 방어).
- **EC9** layout 64KB 근접(타일 과다) → 백엔드 400 시 토스트 안내(클라이언트 하드 제한 불요, 방어만).
- **EC10** 생성 직후 상세 진입 시 version=0(초기) → 첫 PATCH는 version 0으로.

## 7. 제약 조건

- BC 격리 — notification BC 한정. 다른 BC import 금지.
- 백엔드 변경 최소 — 필요 시 same-BC view layer만(현재 불필요).
- 기존 라우트 `/dashboard`(환영) 보존.
- react-grid-layout 외 새 의존성 도입 금지(추가 필요 시 Maxi 확인).
- 공유 사용자 선택 UI — 기존 사용자 검색 API 재사용 여부 plan에서 확인(없으면 UUID 직접 입력 또는 기존 패턴 따름).

## 8. 측정 가능한 완료 기준

- `pnpm typecheck` 0 에러, `pnpm lint` clean, `pnpm test`(vitest) 전체 통과(신규 단위 포함).
- 신규 단위: layout 직렬화/역직렬화 순수 함수, 권한 판정, api Zod 파싱, 컴포넌트 smoke.
- **E2E(Playwright)**: S1(목록) · S2(생성) · S3(타일 추가+드래그+리사이즈+저장) · S6(삭제) · S7(비소유자 읽기전용) · S8(OCC 409). 드래그는 PointerSensor 방식(mouse.move/down/move>threshold/move/up).
- 기존 E2E 회귀 0(특히 `/dashboard` 환영 경로, 네비게이션).
- i18n 콜론 종결 금지 테스트 통과.
- FR 카운트 변동 없음(FR-DB-01은 D6/D7 완료로 product 체크박스만 마킹, 총 123 FR 불변).

## 9. Self-review (brainstorming 대체 — 명세 명확 완료 FR)

직접 작성 스펙을 self-review로 흔든 결과.

- **Q: placeholder 타일에 제목만 있으면 시연이 빈약하지 않나?** → FR-DB-01의 목적은 레이아웃 시스템 완성. 타일 제목 + 빈 본문 + "FR-DB-02에서 콘텐츠" 안내로 충분. 더미 위젯은 스코프 침범(Maxi가 placeholder 확정).
- **Q: 공유 사용자 선택 UI는?** → 기존 사용자 검색/멘션 자동완성 API(GET users?query=) 재사용 가능성 plan에서 확인. 없으면 최소 입력 방식. (FR-DB-01 핵심 아님, 과설계 금지)
- **Q: 편집 모드 vs 보기 모드 토글 필요?** → 소유자는 항상 편집 가능(드래그 활성) + 저장 버튼. 별도 모드 토글은 과설계 — dirty state로 충분. plan에서 UX 확정.
- **Q: 자동 저장 vs 수동 저장?** → 수동 저장(명시적 "저장" 버튼). OCC·네트워크 부담·의도치 않은 저장 방지. 자동 저장은 과설계.
- **Q: react-grid-layout React 19 미지원 위험?** → NFR-1로 명시, impl 첫 검증 항목. 충돌 시 Maxi 보고.
- **결론**: 누락 gap 없음. Maxi 결정 3건으로 핵심 모호성 해소됨. plan으로 진행.
