# FR-SL-06 D6 UI (프로젝트 설정 → Slack 채널 관리) + D7 E2E — 스펙

> slug: fr-sl-06-d6-d7-slack-channel-mapping-ui
> 작성: 2026-07-13
> BC: slack-integration (프론트엔드 소비)
> 선행: PR-A(#264 매핑 CRUD API)·PR-B(#266 채널 라우팅) 완료. 백엔드 계약 확정.

## 개요

프로젝트 관리자가 **프로젝트 설정 → "Slack 채널"** 화면에서 이 프로젝트의 활동을
어느 Slack 채널로 브로드캐스트할지 매핑을 관리한다(생성/목록/수정/삭제). 각 매핑은
채널 + 이벤트 필터(event_filter)를 가진다. 백엔드는 완비됐고 이 작업은 순수 UI + E2E다.

선례 = `projects.$projectKey.settings.automation.tsx`(동형: projectKey 스코프 + admin 게이트
+ 룰 CRUD 리스트/폼다이얼로그). 이 패턴을 그대로 미러한다.

## 사용자 시나리오 (Given-When-Then)

**S1. 매핑 목록 조회**
- Given 프로젝트 관리자가 프로젝트 설정에 들어와 있고
- When "Slack 채널" 탭을 열면
- Then 이 프로젝트에 등록된 채널 매핑 목록(채널명/ID + 선택된 이벤트 유형)이 보인다. 없으면 빈 상태 안내.

**S2. 매핑 생성**
- Given 관리자가 "Slack 채널" 화면에 있고
- When "채널 추가"를 눌러 채널 ID(+표시명)와 이벤트 유형 1개 이상을 선택해 저장하면
- Then 201 응답 후 목록에 새 매핑이 나타난다.

**S3. 매핑 수정**
- Given 기존 매핑이 있고
- When 매핑의 "수정"을 눌러 채널 또는 이벤트 필터를 바꿔 저장하면
- Then 200 응답 후 목록이 갱신된다(PATCH 부분 수정).

**S4. 매핑 삭제**
- Given 기존 매핑이 있고
- When "삭제"를 눌러 확인하면
- Then 204 응답 후 목록에서 사라진다. (하드 삭제 — 이후 게시만 중단, 과거 게시 소급 안 됨: ADR D4)

**S5. 이벤트 유형 미선택 방지**
- Given 관리자가 매핑을 생성/수정 중이고
- When 이벤트 유형을 하나도 선택하지 않고 저장하면
- Then 저장이 막히거나(클라이언트 가드) 백엔드 400을 받아 폼에 에러가 표시된다.

**S6. 비관리자 접근**
- Given 프로젝트 관리자가 아닌 사용자가
- When 이 화면의 API를 호출하면
- Then 백엔드가 404(존재 비노출)를 반환하고, UI는 "찾을 수 없음/권한 없음" 상태를 보여준다.

## 기능 요구사항 (FR)

- **FR1. 라우트** — `projects/$projectKey/settings/slack-channels` 신규 라우트 + 설정 네비게이션에 "Slack 채널" 항목 추가.
- **FR2. 목록** — `GET /channel-mappings?projectKey=` 결과를 리스트로 렌더. 로딩/에러/빈 상태 3종.
- **FR3. 생성** — 폼 다이얼로그(채널 ID, 채널 표시명(선택), 이벤트 유형 다중선택) → `POST`. 성공 시 목록 invalidate.
- **FR4. 수정** — 기존 매핑을 폼에 프리필 → `PATCH`(변경 필드만). 성공 시 목록 invalidate.
- **FR5. 삭제** — 확인 후 `DELETE`. 성공 시 목록 invalidate.
- **FR6. 이벤트 필터 UI** — event_filter 10종을 사람이 읽을 수 있는 라벨의 체크박스/토글로 제공(그룹핑: 이슈/스프린트/자동화).
- **FR7. API 클라이언트** — `src/api/slack.ts`에 Zod 스키마 + 4 함수 추가(기존 slack API 관례 유지).

## 비기능 요구사항 (NFR)

- **NFR1. 계약 정합** — Zod 스키마가 백엔드 `ChannelMappingResponse`와 1:1. backend DTO invent 금지(메모리 `frontend-zod-backend-dto-contract-gap`).
- **NFR2. 단위 테스트 가능** — Page를 RouteAdapter(useParams) + props-Page로 분리(automation 선례).
- **NFR3. MSW** — 신규 핸들러 파일 + **전역 등록 필수**(메모리 `msw-global-handler-registration-gap`). stateful CRUD(생성→목록 반영).
- **NFR4. 디자인 정합** — DESIGN.md 토큰 준수. automation 설정 페이지와 시각적 일관(`p-8 space-y-6 max-w-2xl` 헤더 패턴).
- **NFR5. E2E 견고성** — 텍스트 중복 액션 버튼은 컨테이너 한정/`data-testid`(메모리 `playwright-getbyrole-exact-strict-mode`).

## API 인터페이스 (REST — 백엔드 완료, 참조)

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/api/v1/slack/channel-mappings` | `{projectKey, channelId, channelName?, eventTypes: string[]}` | 201 + 뷰 |
| GET | `/api/v1/slack/channel-mappings?projectKey=` | — | 200 + 뷰[] |
| PATCH | `/api/v1/slack/channel-mappings/{id}` | `{channelId?, channelName?, eventTypes?}` | 200 + 뷰 |
| DELETE | `/api/v1/slack/channel-mappings/{id}` | — | 204 |

뷰 = `{id, projectKey, channelId, channelName: string|null, eventTypes: string[], createdAt, updatedAt}`.

**에러 응답 (실제 백엔드 코드 정독 확정 — 구현 중 정정)**:
- **403 `SLACK_CHANNEL_MAPPING_FORBIDDEN`** — **create/list** 권한 거부(`requireManagePermission`). 즉 **목록(GET) 비관리자 조회는 403**(404 아님). → 목록 화면은 403을 "권한 없음"으로 처리해야 함.
- **404 `SLACK_CHANNEL_MAPPING_NOT_FOUND`** — **update/delete** 대상 미존재 OR 권한 거부(존재 비노출로 수렴, 하드닝 1). update/delete 전용.
- **409 `SLACK_CHANNEL_MAPPING_CONFLICT`** — 같은 (team+project+channel) 중복. "이미 동일한 채널 매핑이 존재합니다."
- **409 `WORKSPACE_NOT_INSTALLED`** — 워크스페이스 설치 0건(prefix 없음 — FR-SL-02 connect 공유).
- **400 `SLACK_CHANNEL_MAPPING_INVALID`**(빈/미지 eventTypes) · **400 `SLACK_CHANNEL_MAPPING_BAD_REQUEST`**(malformed).
- **401 `SLACK_UNAUTHENTICATED`** — PAT/미인증.

에러 바디 `{code, message}`. 폼/목록은 `ApiError.status`/`code`로 분기.
> **정정 이력**: 초안은 목록 권한거부를 404로 오기재. 실제 코드는 create/list=403, update/delete=404(비대칭, 존재 비노출). 목록 UI는 **403(및 방어적 404)**을 access-denied로 처리.

event_filter 허용값 10종 + 제안 한글 라벨:
| wireValue | 라벨 | 그룹 |
|---|---|---|
| issue.created | 이슈 생성 | 이슈 |
| issue.assigned | 담당자 지정 | 이슈 |
| issue.transitioned | 상태 전이 | 이슈 |
| issue.commented | 댓글 작성 | 이슈 |
| issue.due_soon | 마감 임박 | 이슈 |
| issue.overdue | 마감 초과 | 이슈 |
| issue.mentioned | 멘션 | 이슈 |
| sprint.started | 스프린트 시작 | 스프린트 |
| sprint.ended | 스프린트 종료 | 스프린트 |
| automation.failed | 자동화 실패 | 자동화 |

## 데이터 모델 변경

없음. 백엔드(V704/V705) 완료.

## 엣지 케이스

- **EC1. 이벤트 유형 0개** — 클라이언트 가드(저장 버튼 disable 또는 검증 메시지) + 백엔드 400 fallback. 둘 다 커버.
- **EC2. 권한 없음/미존재 → 404** — 목록 조회 404 시 "권한 없음/찾을 수 없음" 상태. (403 아님 — ADR 하드닝)
- **EC3. malformed 입력 → 400** — 폼 에러 표시.
- **EC4. 워크스페이스 미설치** — 매핑 생성 시 백엔드가 **409 `WORKSPACE_NOT_INSTALLED`**를 반환한다(권위 경로). 폼이 그 메시지("Slack 워크스페이스가 먼저 연결돼야 합니다" 취지)를 표시. 추가로 화면 진입 시 `GET /installation`으로 미설치를 미리 안내하면 UX 개선(선택적, plan에서 확정).
- **EC5. 채널 ID 미확인** — 아래 §제약 C1 참조.
- **EC6. 동일 채널+프로젝트 중복 매핑** — 백엔드 확정: (team+project+channel) UNIQUE → **409 `CONFLICT`**. UI 폼이 "이미 동일한 채널 매핑이 존재합니다" 표시. create·update 양쪽 커버.

## 제약 조건

- **C1. 채널 ID 직접 입력** — 백엔드는 `channelId`를 자유 문자열(Slack `C…` id)로 받고, **채널 목록 조회 API가 없다**(conversations.list 미노출). 따라서 UI는 채널 선택 드롭다운을 만들 수 없고, 관리자가 **채널 ID를 직접 입력**한다(+표시명 선택). 채널 picker는 별도 백엔드 엔드포인트가 필요해 이 작업 범위 밖. **게이트1에서 Maxi 확정 필요** — (a) 채널 ID 직접입력으로 진행(추천, 백엔드 완비 범위) vs (b) 채널 picker 위해 백엔드 엔드포인트 추가(범위 확장).
- **C2. team_id 비노출** — 단일 워크스페이스 설치라 UI는 team을 고르지 않는다.
- **C3. JWT 전용** — PAT 401. 기존 인증 라우트 가드 재사용.

## 측정 가능한 완료 기준

1. `projects/$projectKey/settings/slack-channels` 라우트 접근 → 매핑 목록/생성/수정/삭제 전 과정 동작.
2. 단위 테스트: Page 컴포넌트 + API 클라이언트 + 폼 검증. `pnpm test` + `tsc --noEmit` green.
3. D7 E2E: 아래 시나리오 통과. 기존 E2E 회귀 0(메모리 `ui-pr-defer-e2e-regression-latent`).
4. `pnpm verify`(lint+typecheck+test+build) green.

## D7 E2E 시나리오 (Playwright)

- **E1. happy path** — 설정 진입 → Slack 채널 탭 → 채널 추가(ID+이벤트 2개) → 목록에 표시 → 이벤트 필터 수정 → 삭제.
- **E2. 이벤트 미선택 가드** — 이벤트 0개로 저장 시도 → 저장 막힘/에러.
- **E3. 빈 상태** — 매핑 0개 프로젝트 → 빈 상태 안내 렌더.
- (MSW stateful 시나리오로 구동. serviceWorkers block 금지 — 메모리 `e2e-msw-serviceworker-block`.)

## Brainstorming Check

✅ 통과 (1회 iteration). 발견·보강한 gap.
- 백엔드가 **409 두 종류**(중복 CONFLICT / 워크스페이스 미설치 WORKSPACE_NOT_INSTALLED)를 반환함을 코드에서 확인 → API 계약 표 + EC4/EC6에 폼 처리 명시(과소명세 보정).
- 그 외 시나리오/엣지(권한404·malformed400·이벤트0개·채널ID직접입력·team_id비노출)는 커버 확인.
- 미해결 결정 1건 = **C1 채널 ID 직접입력 vs 채널 picker(백엔드 확장)** → 게이트1에서 Maxi 확정.
