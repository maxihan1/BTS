<!-- BC 단위 변경 이력 — 9개 BC 완료 게이트가 요구하는 정본 (docs/plan/product/*.md §BC 완료 게이트) -->

# CHANGELOG

Atlas Issues (BTS) 의 변경 이력. **바운디드 컨텍스트(BC — 책임 범위로 나눈 도메인 단위) 단위**로 요약한다.

- **형식**. [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/) 를 BC 단위로 각색.
- **버전**. 아직 릴리스 태그가 없다 (`0.0.1-SNAPSHOT`). Phase 1 완료 후 `0.1.0` 을 끊는다.
- **정본 관계**. FR 단위 진척은 `docs/plan/product/<bc>.md` 의 D1~D7 체크박스가 정본이고,
  이 파일은 그것을 BC 단위로 요약한다. 두 곳이 어긋나면 product 파일이 이긴다.
- **갱신 시점**. `/bts-merge` 직후 (BC 완료 게이트 `CHANGELOG.md 정리` 항목).

---

## [Unreleased] — Phase 1

**범위**. 139 FR / 9 BC. **2026-05-20 ~ 2026-07-29**, PR 309건, 커밋 598건.

**상태 (2026-07-29 재실측)**. 139 FR 중 **FR-UX-08~14 를 제외한 전량**의 D1~D7 단계 완료
(`- [x] D«n».` 916건 / 미완 49건 — 전량 FR-UX-08~14, `docs/plan/product/personalization.md §4`).
FR-UX-07 은 **활성 프로젝트 컨텍스트**로 범위를 좁혀 PR #320 에서 D1~D7 을 마쳤고, 한 FR 에 묶여 있던
27 PR 로드맵의 나머지는 기능 단위 7개 FR(FR-UX-08~14)로 분리해 **등록만** 했다 (2026-07-29 Maxi 확정 분할).
미완이 6건 → 49건으로 늘어난 것은 회귀가 아니라 로드맵 잔여를 문서에 드러낸 결과다.
BC "완료 선언" 은 별개 절차다 — 각 BC 의 §NFR 측정표 · 이 파일 정리 · **Maxi 1인 선언**이 남아 있다
(`docs/plan/product/<bc>.md §BC 완료 게이트`).

### BC 요약

| BC | FR | 기간 | PR | 대표 산출 |
|---|---|---|---|---|
| identity-access | 25 (AU 10 · MF 5 · PM 10) | 2026-05-20 ~ 07-18 | 42 (#8~#134 외) | 플러그형 AuthN Provider · LDAP/SAML/OIDC SSO · TOTP/백업코드/WebAuthn MFA · 권한 스킴 · 감사 로그 |
| issue-tracking | 37 (IS 10 · CM 4 · VR 4 · PJ 4 · AC 2 · MN 2 · CO 2 · LK 2 · HS 2 · TM 2 · MV 2 · WT 1) | 2026-05-22 ~ 07-27 | 67 (#17~#316) | 이슈 CRUD/일괄편집/클론 · JSONB 커스텀 필드 · MinIO 첨부 · flexmark 멘션 · 이슈 이동+키 리다이렉트 · 프로젝트 CRUD · 댓글 |
| project-workflow | 3 (WF 3) | 2026-05-22 ~ 07-11 | 9 (#10~#66 외) | YAML FSM 워크플로우 · 워크플로우/권한 스킴 · 스킴 배정 |
| agile-planning | 14 (BD 3 · TL 3 · BL 2 · EP 2 · TT 2 · PL 2) | 2026-06-19 ~ 06-29 | 15 (#168~#202) | @dnd-kit 칸반 · LexoRank 백로그 · 에픽 링크 · Gantt(자체 SVG) · 워크로그 |
| notification-dashboard | 14 (NT 5 · RP 4 · DB 3 · UX 2) | 2026-06-11 ~ 07-03 | 28 (#118~#231) | STOMP WebSocket 알림 · Inbox 멱등 · 대시보드 가젯 · 공유 토큰 · 번다운/CFD/사이클타임 |
| search-export-import | 12 (SR 4 · API 4 · EX 2 · IM 2) | 2026-06-23 ~ 07-04 | 15 (#180~#226) | AQL 손수 파서 + `pg_trgm` · 한글 FTS(simple) · CSV/XLSX Export(수식 인젝션 방어) · pgmq+MinIO Import |
| personalization | 14 완료 / 21 등록 (PR 4 · UX 5 · PF 3 · CA 2 완료 + UX-08~14 7 미착수) | 2026-07-05 ~ 07-29 | 3 (#239~#320 외) | 프로필/환경설정 · 퀵 필터 · 캘린더 · FR-UX-06 Jira Cloud 방식 재설계(22 PR 체인) · FR-UX-07 활성 프로젝트 컨텍스트 |
| automation | 7 (AT 7) | 2026-07-10 ~ 07-18 | 14 (#73~#278) | 6 트리거 → pgmq → executor · YAML GitOps 룰 · Git 웹훅(HMAC 서명검증) |
| slack-integration | 6 (SL 6) | 2026-07-08 ~ 07-13 | 9 (#244~#267) | 인바운드 서명검증 자체 컨트롤러 · 채널 매핑 · 봇 토큰 3중 미노출 |

> personalization 은 논리 BC 다. 물리적으로는 identity-access 모듈에 산다 (JdbcTemplate · JWT-only).
> FR-UX-07 은 **활성 프로젝트 컨텍스트**로 좁혀져 PR #320 에서 완결됐다. 한 FR 이 27 PR 로드맵 전체를
> 덮고 있어 D1(도메인 정리)이 "절반 완료" 라는 성립 불가능한 상태가 됐던 것이 분할 근거다.
> 잔여 로드맵은 **FR-UX-08~14 7건**으로 등록만 해 뒀다 — 프로젝트 전환·생성 흐름·컨텍스트 단축키·
> 인라인 편집·검색 진입·백로그 사용성·카드 밀도. 완료 시 이 표를 21 완료로 갱신한다.
> **파생 결정 (2026-07-28 결정의 명시적 정정)**. 백엔드 작업 B1(생성 필드 3종)·B2(카드 필드)는
> "기존 FR 결손 봉합이라 chore" 로 분류돼 있었으나, 분할 후 **B1 = FR-UX-09 의 D4 · B2 = FR-UX-14 의 D4**
> 로 승격한다. "백엔드 없음" 으로 비던 칸이 실제 내용으로 채워지는 쪽이 정확하다.

### 보안 봉합 트랙 (2026-07-25 ~ 07-27)

경로 토큰(공유 대시보드 등의 URL 비밀값)이 응답·로그로 새는 표면 4곳을 전수 조사해 봉합했다.

| # | 표면 | PR |
|---|---|---|
| N1 | nginx 접속 로그에 토큰 원문 기록 | #311 |
| N2 | 인증된 요청의 `/error` 경로 토큰 노출 | #312 |
| N3 | 전역 `@ControllerAdvice` 의 `ProblemDetail.instance` 자동 채움 | #313 |
| N4 | WorkflowScheme 읽기 API 무가드 (14개월 잠복) | #314 |

부수 산출 — `docs/contracts/workflow-schemes.snapshot.json` (백엔드↔프론트 계약 스냅샷 정본, #317).

### 기술부채 정리 라운드 (2026-07-27, `chore/debt-zero-2026-07-27`)

> PR 번호는 머지 시 채운다. 작업 기록은 `docs/plans/2026-07-27-debt-zero.md`.

`TODOS.md` 후속 항목을 전수 재실측해 해소한 라운드. **부채 기록 자체가 부정확한 항목이 여럿이라**
(개수·구성원·증상·처방까지 사실과 달랐다) 착수 전 실재 재확인을 규칙으로 삼았다.

#### 이슈 자식 엔티티 권한 봉합 (FR-LK · FR-AC · FR-IS)

이슈에 딸린 링크·부모·첨부에 권한 검사가 **없거나 절반만** 있던 곳을 닫았다. 이전에는 로그인만
했으면 자기가 참여하지 않는 프로젝트의 이슈에도 링크를 걸고 지울 수 있었다.

| 표면 | 이전 | 이후 |
|---|---|---|
| 이슈 링크·부모 API 전 핸들러 | 권한 검사 0건 (`.authenticated()` 뿐) | 생성은 **양끝 이슈** 쓰기 권한, 해제는 대상 이슈 쓰기 권한 |
| 링크 삭제 | 전역 `linkId` 로 삭제 — **남의 링크를 지울 수 있었다**(IDOR) | 경로 이슈 소속으로 `WHERE` 절 한정, 미소속은 404 |
| 링크 목록 | 볼 수 없는 상대 이슈의 제목·상태까지 응답에 실림 | 상대 이슈 읽기 권한 없으면 **목록에서 제외**(403 아님) |
| 이슈 관계 그래프 | 중심·이웃 모두 무가드 | 중심 없으면 404, 이웃은 탐색 관문에서 필터 |
| 첨부 삭제 | 이슈 편집 권한만 — 남이 올린 파일을 지울 수 있었다 | 업로더 ∨ 모더레이터(`SOFT_DELETE`) |
| 이슈 보안등급 게이트 | 읽기(`VIEW`)에만 적용 | 쓰기 계열 전체로 확대 |

403 대신 404·제외를 고른 이유 — 403 은 "그건 존재한다" 는 신호라, 응답 코드 차이만으로 볼 수 없는
이슈나 남의 링크 id 를 열거할 수 있다. 정책 행렬 정본은 `docs/plan/product/issue-tracking.md §A`.

#### 댓글 삭제 통지 (FR-CO-02 의 빠진 절반)

내 댓글이 모더레이터에게 지워져도 **아무 신호가 없었다.** 알림 이벤트 `issue.comment_deleted` +
수신자 역할 `COMMENT_AUTHOR` + 기본 정책 시드(V410)를 추가해 당사자에게만 인앱으로 알린다.
외부 웹훅은 막았다 — 삭제된 댓글이 있었다는 사실 자체가 밖으로 퍼지면 모더레이션 목적에 반한다.

#### 운영자 관점

- **백엔드 CI 신설** — 지금까지 백엔드는 CI 가 **아예 없어** main 머지 검증이 로컬 1회성이었다.
  `backend-ci.yml` 4잡(모듈 매트릭스 9 · prod 조립 부팅 · ktlint+detekt · 워크플로우 판별식).
- **springdoc `/v3/api-docs` 외부 미노출 봉인** — 안전했던 유일한 이유가 "nginx 가 그 경로를 프록시하지
  않는다" 는 배포 토폴로지였는데 그걸 지키는 장치가 없었다. `infra-ci.yml` 에 3축 판별식 배선.
- **CORS `PATCH`·`Content-Disposition` 허용 누락 봉합** — 첨부 다운로드 파일명이 브라우저에 전달되지
  않던 원인. 허용 목록↔실제 매핑 차집합 0 을 조립 레벨에서 강제.
- **목록 API 응답 크기 상한** — offset 페이징 2지점에 애플리케이션 상한 주입.

#### 회귀 차단 판별식 — "두 목록이 서로를 안 본다"

이 저장소의 지배적 결함 양식은 한쪽 목록만 자라고 짝이 조용히 멈추는 것이다. 이번 라운드에서
차집합 0 을 CI 로 강제한 짝 — CORS 허용 메서드↔컨트롤러 매핑 · CI 매트릭스↔Gradle 모듈 ·
`BC_KEYWORDS`↔도메인 어휘 · 스킬 분기표↔`TaskType` · 앱 권한 화이트리스트↔DB `CHECK` ·
계약 스냅샷↔실제 응답 · 알림 enum 3지점↔프론트 미러.

계약 스냅샷 커버리지를 8 → 11 엔드포인트로 넓히고 **후퇴 불가**로 동결
(`docs/contracts/core-read.snapshot.json`).

#### 문서 정정

- 계획 문서의 미완 체크박스를 저장소 실측과 정합 (문서상 미완 114건 → 실측 51건).
- `issue-tracking.md §A` — 봉합 완료 반영. 프로덕션 코드 주석이 이 표를 정본으로 지목하는데
  **표가 그 코드의 게이트를 "없다" 고 단언**하던 상태였다.
- `notification-dashboard.md` — 알림 이벤트 개수를 고정 숫자로 적어 enum 이 자라자 거짓이 됐다.
  파생값은 개수 대신 파생식으로 적는다.

#### 통합 테스트 flaky 근본 해소

`awaitility` 타임아웃의 원인은 느림이 아니라 **메시지 도둑질**이었다. 싱글턴 컨테이너를 공유하는
두 컨텍스트가 각자 `@Scheduled` 워커로 같은 pgmq 큐를 폴링해, 먼저 읽은 쪽이 소비하면 다른 쪽은
영원히 0건을 본다. ⇒ 판별 질문은 **「늦게 오나, 아예 안 오나」** — 후자면 타임아웃 증액이 절대 안 듣는다.

### 인프라 / 도구 (2026-07-27 실측)

- Gradle 10 모듈 (9 BC + `app` 배포 조립), Flyway 마이그레이션 118개 / 8 네임스페이스
- React 19 단일 SPA (`apps/web`), TypeScript strict, MSW 모크 276 핸들러
- 테스트 — 백엔드 1,003 파일(Testcontainers 429) · 프론트 521 파일 · E2E 135 스펙
  <br>※ 최초 기록에 `433` 이라 적었으나 재현되지 않아 **429** 로 정정했다. 판별식은
  `git grep -lE "Testcontainers|PostgreSQLContainer|AbstractIntegrationTest" -- 'backend/**/src/test/**/*.kt'`
  이고, main 기준선 **428 을 정확히 재현**하는 것으로 생존을 확인했다. 브랜치 차집합은 **1건**
  (`CommentEditDeleteHistoryE2EIntegrationTest.kt`). 「개수를 잘못 셌다」가 주제인 문서에서
  같은 실수를 반복할 뻔했다 — 숫자를 적을 땐 기준선 재현부터 한다.
- CI — `backend-ci.yml`(모듈 매트릭스 9 + 조립 부팅 + 린트) · `frontend-ci.yml`(lint·typecheck·test 3잡) ·
  `infra-ci.yml`(nginx 마스킹 · springdoc 미노출 봉인) · `workflow-scripts-ci.yml`(판별식 6종)
  <br>※ 워크플로우 판별식 잡은 2026-07-30 에 `backend-ci` 에서 전용 워크플로우로 **이관**됐다.

### CI 러너 — self-hosted 전환 (2026-07-30, PR #324)

**2026-07-29 17:32 KST 부터 GitHub Actions 결제 차단으로 CI 전체가 죽어 있었다.** 잡이 `steps=0` 으로
배정조차 되지 않았고(차단 이후 실행 **12/12** 동일 어노테이션), 그 사이 **#322 · #323 이 CI 0회로
머지**됐다. 이 상태는 잡이 `failure` 로 표시돼 「테스트가 깨졌다」와 구분되지 않는 것이 더 나쁘다.

- self-hosted 러너 `maxi-mac-bts`(osx-arm64) 등록. `runs-on` **9곳 전수** → `[self-hosted, bts-local]`.
  결제 차단이 self-hosted 에는 적용되지 않음을 확인 사격(run `30508738275`, `steps=4 success`)으로 실증
- **판별식 신설** `ci-runner-label-alignment.test.ts` — 워크플로우 파일 목록을 **선언하지 않고 런타임에
  훑는다.** 목록을 상수로 두면 실제 디렉터리와 갈라지는 두 목록이 되기 때문이다(지배 결함 양식).
  목록이 하나면 갈라질 수 없다. 뮤테이션 **8/8**, 그중 M8(INPUTS 밖 신규 워크플로우)이 이 설계를 실증
- **판별식 워크플로우 분리** — `paths` 가 워크플로우 레벨이라 판별식 입력을 `backend-ci` 트리거에
  얹으면 워크플로우 파일 한 줄 수정이 12잡(러너 1대에서 50~60분)을 끌고 온다
- **선재 트리거 갭 2건 해소** — `docs/plan/product/**` 와 `.claude/skills/**` 는 지금까지 **어느
  워크플로우도 걸지 않아** 그 파일만 바꾸는 PR 에서 판별식이 0회 실행됐다 (#320 에서 실증)
- assembly 잡 DB 를 `55433` 으로 분리 (로컬 dev postgres 5433 선점 회피 + 공유 dev DB 가짜초록 차단).
  `application.yml` 의 `${BTS_DB_URL:…}` 오버라이드 지점을 그대로 써 **Kotlin 0줄**
- **★`services:` → `docker run` 스텝.** GitHub Actions 의 서비스 컨테이너는 **Linux 러너 전용**이라
  macOS 에서는 `Initialize containers` 에서 죽는다(`Container operations are only supported on Linux
  runners`, run `30514310932` 실측). 스텝 안 `docker run` 은 정상이다 — 같은 실행의 infra-ci nginx 잡이
  양성 대조군이었다. 회귀는 판별식이 차단하고, 컨테이너 정리를 `if: always()` 로 돌린다
  (self-hosted 는 머신이 살아남아 안 지우면 다음 실행이 포트 충돌로 죽는다)
- 운영 절차 — `docs/runbooks/self-hosted-runner.md`. ★러너가 꺼지면 `failure` 가 아니라 **`queued`
  무한 대기**다 (`timeout-minutes` 는 큐 대기를 세지 않는다)
- **근본 원인 미해결.** 이 러너는 결제 차단을 우회할 뿐이다 — GitHub 호스팅 러너가 필요한 상황은
  여전히 막힌다

---

## 규칙

- 한 항목 = 한 문장. 무엇이 바뀌었고 사용자에게 무슨 의미인지.
- FR ID 와 PR 번호를 반드시 남긴다 (추적 가능성).
- **파괴적 변경**은 `⚠️ BREAKING` 으로 시작한다.
- BC 완료 선언 시 해당 BC 절을 `[Unreleased]` 에서 버전 절로 옮긴다.
