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

**범위**. 131 FR / 9 BC. **2026-05-20 ~ 2026-07-27**, PR 309건, 커밋 598건.

**상태 (2026-07-27 실측)**. 131 FR 전량의 D1~D7 단계 완료 (`- [x] D«n».` 909건 / 미완 0건).
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
| personalization | 13 (PR 4 · UX 4 · PF 3 · CA 2) | 2026-07-05 ~ 07-25 | 3 (#239~#277 외) | 프로필/환경설정 · 퀵 필터 · 캘린더 · FR-UX-06 Jira Cloud 방식 재설계(22 PR 체인) |
| automation | 7 (AT 7) | 2026-07-10 ~ 07-18 | 14 (#73~#278) | 6 트리거 → pgmq → executor · YAML GitOps 룰 · Git 웹훅(HMAC 서명검증) |
| slack-integration | 6 (SL 6) | 2026-07-08 ~ 07-13 | 9 (#244~#267) | 인바운드 서명검증 자체 컨트롤러 · 채널 매핑 · 봇 토큰 3중 미노출 |

> personalization 은 논리 BC 다. 물리적으로는 identity-access 모듈에 산다 (JdbcTemplate · JWT-only).

### 보안 봉합 트랙 (2026-07-25 ~ 07-27)

경로 토큰(공유 대시보드 등의 URL 비밀값)이 응답·로그로 새는 표면 4곳을 전수 조사해 봉합했다.

| # | 표면 | PR |
|---|---|---|
| N1 | nginx 접속 로그에 토큰 원문 기록 | #311 |
| N2 | 인증된 요청의 `/error` 경로 토큰 노출 | #312 |
| N3 | 전역 `@ControllerAdvice` 의 `ProblemDetail.instance` 자동 채움 | #313 |
| N4 | WorkflowScheme 읽기 API 무가드 (14개월 잠복) | #314 |

부수 산출 — `docs/contracts/workflow-schemes.snapshot.json` (백엔드↔프론트 계약 스냅샷 정본, #317).

### 인프라 / 도구

- Gradle 10 모듈 (9 BC + `app` 배포 조립), Flyway 마이그레이션 117개 / 8 네임스페이스
- React 19 단일 SPA (`apps/web`), TypeScript strict, MSW 모크 276 핸들러
- 테스트 — 백엔드 940 파일(Testcontainers 428) · 프론트 516 파일 8,099 케이스 · E2E 135 스펙
- CI — `frontend-ci.yml`(lint·typecheck·test 3잡) · `infra-ci.yml`(nginx 마스킹 봉인)

---

## 규칙

- 한 항목 = 한 문장. 무엇이 바뀌었고 사용자에게 무슨 의미인지.
- FR ID 와 PR 번호를 반드시 남긴다 (추적 가능성).
- **파괴적 변경**은 `⚠️ BREAKING` 으로 시작한다.
- BC 완료 선언 시 해당 BC 절을 `[Unreleased]` 에서 버전 절로 옮긴다.
