# FR-IS-01 D6 — 이슈 UI (목록 + 생성 + 상세) — 스펙

> slug: fr-is-01-d6-issuedetail-tsx-ui-tanstack-query · type: ui · BC: issue-tracking · 2026-05-27
> 범위 결정: 상세 + 목록 + 생성 (Maxi). 낙관적 업데이트: 완전 낙관적. 디자인: 시안 2 (사이드 메타패널, Jira풍).

## 사용자 시나리오 (Given-When-Then)

- **S1 목록 조회.** Given 프로젝트에 이슈가 있을 때, When `/issues?projectKey=ATLAS` 접근, Then 페이지네이션된 목록(키·요약·상태)을 본다. 빈 목록이면 빈 상태 안내.
- **S2 생성.** Given 목록 화면, When "새 이슈" → projectKey·summary 입력 후 저장, Then 201 응답의 새 이슈 상세로 이동.
- **S3 단건 조회.** Given 이슈 키, When `/issues/{key}` 접근, Then 로딩 → 성공(요약·상태·메타 렌더) / 404(role=alert 폴백) 분기.
- **S4 요약 수정 (낙관적).** Given 상세 화면, When 제목 편집 후 저장, Then UI 즉시 반영 + `PATCH {summary, expectedVersion}`. 성공 시 응답의 새 `version` 보관. 409 VERSION_CONFLICT 시 낙관적 변경 롤백 + 최신 재조회 + 토스트.
- **S5 상태 표시 (읽기 전용).** Given 상세, When 화면 진입, Then `currentStateKey`를 읽기 전용 배지로 표시. **상태 전이(transition)는 D6 제외** — 백엔드 미연동(메모리 [[issue-transition-backend-gap]], DEFAULT 워크플로우 미시드 + OPEN/open 케이스 불일치). 전이는 후속 slice.
- **S6 소프트 삭제.** Given 상세, When 삭제 + 확인 모달, Then `DELETE`(204) 후 목록으로 이동 + 목록 캐시 무효화.
- **S7 키 영속성 (D7 E2E 본격 검증).** 삭제 후 옛 키로 `GET` → 404 분기. D6는 404 처리까지, redirect 검증은 D7.

## 기능 요구사항 (FR)

- **FR1.** 이슈 목록 페이지 — `Page<IssueResponse>` 렌더 + 페이지네이션 + projectKey 필터.
- **FR2.** 이슈 생성 폼 — projectKey + summary 입력, Jakarta Validation 대응(빈 summary 차단), 201 후 상세로 navigate.
- **FR3.** 이슈 상세 페이지 (`issues.$key.tsx`) — IssueResponse 렌더, 로딩/에러/성공 3-상태. 시안 2 레이아웃(좌 본문 + 우 메타패널).
- **FR4.** 요약 인라인 수정 — 완전 낙관적 useMutation, `expectedVersion` 전송.
- **FR5.** 상태 표시 — `currentStateKey` 읽기 전용 배지. **전이 제외(D6 범위 밖, 백엔드 미연동).**
- **FR6.** 소프트 삭제 — 확인 모달 후 DELETE, 목록 이동.
- **FR7.** 에러 처리 — errorCode → 한국어 사용자 메시지 매핑. 409 VERSION_CONFLICT(수정)는 토스트 + 자동 재조회. (TRANSITION_NOT_ALLOWED는 전이 제외로 D6 비대상.)
- **FR8.** 디자인 — 시안 2(사이드 메타패널) + DESIGN.md 토큰 + 기존 shadcn 컴포넌트. 우측 패널 상태는 select가 아닌 **읽기 전용 배지**로 렌더(전이 제외 반영).

## 비기능 요구사항 (NFR)

- **낙관적 업데이트 일관 패턴.** useMutation `onMutate`(cancelQueries + snapshot + 캐시 선반영) → `onError`(snapshot 롤백) → `onSettled`(invalidateQueries 재조회). 수정·전이에 적용.
- **응답 래퍼 비대칭.** 단건/생성/수정/전이 = `{ data: IssueResponse }`, 목록 = `Page<IssueResponse>`(래퍼 없음). API 클라이언트·Zod 스키마가 두 형태 모두 처리.
- **타입 안전.** TS strict. Zod 응답 파싱. 필드명은 REST 계약 정본(`currentStateKey`, 전이 요청은 `toStatusKey`).
- **접근성.** WCAG AA. 에러 `role="alert"`, 키보드 탐색, 터치 타깃 44px, label 연결.
- **테스트.** Vitest + React Testing Library 단위 + MSW API mock. `vi.mock`은 컴포넌트/함수 단위 좁은 범위(learnings 2026-05-26 worker scope leak 회피).
- **로깅.** `eslint no-console` 준수 — 프론트 로깅 ADR(PR #12) 따름.

## API 인터페이스 (정본 — backend 변경 없음)

`com.bts.issue.adapter.inbound.rest`, base `/api/v1/issues`.

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/` | `{ projectKey, summary }` | 201 `{ data: IssueResponse }` + Location |
| GET | `/{key}` | — | 200 `{ data: IssueResponse }` |
| GET | `/?projectKey=&page=&size=` | — | 200 `Page<IssueResponse>` (래퍼 없음) |
| PATCH | `/{key}` | `{ summary?, expectedVersion }` | 200 `{ data: IssueResponse }` |
| POST | `/{key}/transition` | `{ toStatusKey, expectedVersion }` | 200 `{ data: IssueResponse }` |
| DELETE | `/{key}` | — | 204 |

`IssueResponse = { key, id, projectKey, summary, currentStateKey, reporterId, version, createdAt?, updatedAt? }`

에러(RFC 7807 ProblemDetail + `errorCode`): VALIDATION_FAILED(400) · UNAUTHENTICATED(401) · ACCESS_DENIED(403) · ISSUE_NOT_FOUND(404) · PROJECT_NOT_FOUND(404) · KEY_PREFIX_RESERVED(409) · VERSION_CONFLICT(409) · TRANSITION_NOT_ALLOWED(409) · INTERNAL_ERROR(500).

## 데이터 모델 변경

**없음.** 프론트 전용. 백엔드 D1~D5 완료(PR #17/#23/#24).

## 엣지 케이스

1. 빈 목록 → 빈 상태 카드.
2. 매우 긴 summary → 말줄임(목록) / 줄바꿈(상세).
3. 404 이슈(없거나 소프트 삭제) → role=alert 폴백.
4. **409 VERSION_CONFLICT (요약 수정).** 낙관적 반영 롤백 + 재조회 + 토스트.
5. 500 INTERNAL_ERROR(pgmq 발행 실패 롤백 등) → 일반 오류 토스트 + 재시도 안내.
6. `createdAt`/`updatedAt` null → "—" 표기.
7. 생성 시 빈 summary → 폼 검증 차단(서버 400 도달 전).
8. (전이 엣지 케이스 — TRANSITION_NOT_ALLOWED 등 — 은 전이 제외로 D6 비대상.)

## 제약 조건

- **BC 격리.** issue-tracking 프론트만. 백엔드 계약 변경 0(있다면 별 PR). learnings 2026-05-22 BC 격리 예외 패턴은 적용 안 함(백엔드 변경 불필요).
- **라우팅.** code-based adapter 패턴(`workflows.$key.tsx` 선례) — `issues.$key.tsx`에 `IssueDetailPage`(props 기반) + `IssueDetailRouteAdapter`(useParams) export, `router.ts` 등록.
- **API 모듈.** `@/api/issues.ts` 신규 — `@/api/client.ts`의 `apiFetch` + Zod 스키마 사용.
- **백엔드 미가용.** D6 단위 테스트는 MSW로 충분. D7 E2E는 백엔드 clean build 선결(메모리 [[backend-clean-build-broken]]).

## 측정 가능한 완료 기준

- `pnpm verify`(lint + typecheck + test + build) green.
- 단위 테스트: 목록·생성·상세·요약수정·삭제 happy path + 낙관적 롤백(409 VERSION_CONFLICT) + 404 폴백 커버.
- D7 E2E 시나리오 중 D6 범위(생성→조회→수정→소프트삭제)를 UI로 완주 가능(백엔드 기동 시). 전이·키 영속성 검증은 전이 백엔드 wiring 수정 후.
- 디자인 시안 2 레이아웃 일치 + WCAG AA(키보드 탐색 + role=alert).

## 디자인 시안

선택. 시안 2 (사이드 메타패널, Jira풍). 목업. `apps/web/public/mockups/fr-is-01-d6-issuedetail-2.html`.
좌측 본문(키 breadcrumb + 제목 + 설명 placeholder), 우측 고정 메타패널(상태 **읽기전용 배지** / 보고자 / 프로젝트 / 버전 / 생성·수정 / 삭제). 모바일은 우측 패널이 본문 아래 스택.
(목업의 상태 select 드롭다운은 전이 제외 결정에 따라 구현 시 읽기전용 배지로 대체.)

## Brainstorming Check

✅ 통과 (1 iteration). 발견·반영.
- **gap (Maxi 결정): 상태 전이 백엔드 미연동** — `workflowKey="DEFAULT"` 미시드 + `currentStateKey="OPEN"`(대문자) vs 워크플로우 `open`(소문자). 단위 테스트는 port mock로 통과. → **전이 D6 제외**, 상태 읽기전용. 메모리 [[issue-transition-backend-gap]] 기록.
- **gap (기본값 적용): projectKey 입력 방식** — 프로젝트 목록 API/화면 부재. → 생성 폼은 projectKey 자유 입력(텍스트), 서버 PROJECT_NOT_FOUND(404)로 검증. 프로젝트 선택기는 FR-PM 후속.
- **gap (기본값 적용): 목록 진입 동선** — issues 라우트로의 네비게이션 부재. → dashboard/네비에 "이슈" 링크 1개 추가.
