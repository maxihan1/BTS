<!-- personalization BC — 프로필/설정/캘린더/퀵필터/Slash/단축키 12 FR -->

# personalization BC

**소속 FR**. 12개 (PR 4 + PF 3 + CA 2 + UX-01,04,05 3).
**책임**. 사용자 프로필 / 환경 설정 / 캘린더 / UX 편의 (퀵 필터, Slash, 단축키).
**SDD 참조**. 20장 (개인화).
**다른 BC와의 경계**. identity-access의 user 식별 사용. issue-tracking의 할당/마감일 조회 (캘린더). agile-planning의 보드 필터 (퀵 필터). **import 금지 — 이벤트/API만**.

## §0 진입 조건

- [ ] identity-access §2.1 (FR-AU-01 Provider) 완료 (사용자 ID 안정)
- [ ] issue-tracking §6.1 (FR-PL-01 일정) 완료 (캘린더 데이터)
- [ ] agile-planning §2 (FR-BD 보드) 완료 (퀵 필터 컨텍스트)

## §1 기술 검증

이 BC 자체의 PoC는 없음.

## §2 프로필 (FR-PR, 4개)

### §2.1 FR-PR-01 — 사용자 프로필 (이름/아바타/타임존/부서)

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `personal/profile`

- [ ] D1. 도메인 — UserProfile (책임. backend-engineer)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `user_profiles(user_id, display_name, avatar_url, timezone, department)` (책임. db-engineer)
- [ ] D4. 백엔드 — `GET/PATCH /api/v1/users/me/profile`. MinIO 아바타 (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 프로필 페이지 + 아바타 업로드 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.2 FR-PR-02 — 상태 메시지 (이모지 + 텍스트)

**우선순위**. 높음 | **선행**. §2.1 | **Plan slug**. `personal/status-msg`

- [ ] D1. 도메인 — UserStatus (책임. backend-engineer)
- [ ] D2. 명세 — TTL 옵션 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `user_statuses(user_id, emoji, text, expires_at)` (책임. db-engineer)
- [ ] D4. 백엔드 — `PATCH /api/v1/users/me/status` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 아바타 옆 상태 + 설정 모달 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.3 FR-PR-03 — 부재중 (Out of Office)

**우선순위**. 높음 | **선행**. §2.2 | **Plan slug**. `personal/ooo`

- [ ] D1. 도메인 — OutOfOffice (책임. backend-engineer)
- [ ] D2. 명세 — 기간 + 대체 담당자 + 자동 응답 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `user_ooo(user_id, starts_at, ends_at, delegate_user_id)` (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/users/me/ooo` + 할당 시 자동 위임 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — OOO 설정 폼 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.4 FR-PR-04 — LDAP 동기화 필드 vs 사용자 편집 분리

**우선순위**. 필수 | **선행**. §2.1, identity-access §2.2 (LDAP) | **Plan slug**. `personal/ldap-sync-separation`

- [ ] D1. 도메인 — ProfileField (source: LDAP vs USER) (책임. backend-engineer)
- [ ] D2. 명세 — LDAP 우선 필드 / 사용자 우선 필드 매트릭스 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `user_profiles` 컬럼별 source 표시 (책임. db-engineer)
- [ ] D4. 백엔드 — LDAP 동기화 시 USER 필드는 덮어쓰지 않음 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — 충돌 시나리오 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 필드별 "LDAP에서 동기화됨" 라벨 + 편집 불가 표시 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §3 환경 설정 (FR-PF, 3개)

### §3.1 FR-PF-01 — 환경 설정 (테마/언어/날짜포맷)

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `personal/preferences`

- [ ] D1. 도메인 — UserPreferences (책임. backend-engineer)
- [ ] D2. 명세 — 기본값 + 사용자 override (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `user_preferences(theme, locale, date_format, ...)` (책임. db-engineer)
- [ ] D4. 백엔드 — `GET/PATCH /api/v1/users/me/preferences` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — i18next + theme 토글 + date-fns-tz (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.2 FR-PF-02 — 기본 뷰/시작 페이지

**우선순위**. 높음 | **선행**. §3.1 | **Plan slug**. `personal/start-page`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 로그인 후 리다이렉트 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `user_preferences.start_page` (책임. db-engineer)
- [ ] D4. 백엔드 — (활용) (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 설정 페이지 + 로그인 후 자동 라우팅 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.3 FR-PF-03 — 단축키 커스터마이즈

**우선순위**. 중간 | **선행**. §4.3 (FR-UX-05) | **Plan slug**. `personal/keymap-customize`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 충돌 검출 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `user_keymap(action, key_combo)` (책임. db-engineer)
- [ ] D4. 백엔드 — `GET/PATCH /api/v1/users/me/keymap` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 단축키 설정 + 실시간 reassign (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §4 UX 편의 (FR-UX-01, 04, 05)

### §4.1 FR-UX-01 — 퀵 필터 (보드 상단 즉시 필터)

**우선순위**. 필수 | **선행**. agile-planning §2.1 | **Plan slug**. `personal/quick-filters`

- [x] D1. 도메인 — QuickFilter (책임. backend-engineer)
- [x] D2. 명세 — 보드별 사전 정의 필터 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `board_quick_filters(board_id, name, query)` (책임. db-engineer)
- [x] D4. 백엔드 — CRUD API + 보드 응답에 포함 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 보드 상단 필터 칩 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §4.2 FR-UX-04 — Slash 명령어

**우선순위**. 높음 | **선행**. §0 | **Plan slug**. `personal/slash-cmd`

**아키텍처**. 프론트 전용 (ADR [decisions/2026-07-04-fr-ux-04-slash-cmd.md](../../decisions/2026-07-04-fr-ux-04-slash-cmd.md)). 명령 3종(`/goto`·`/search`·`/issue`)이 전부 네비게이션이라 백엔드 executor 미도입 — 클라이언트 라우팅으로 dispatch. cmdk(FR-IS-09 도입) 재사용.

- [ ] D1. 도메인 — 프론트 명령 레지스트리(`commands.ts` 코드 상수). 백엔드 도메인 없음 (책임. frontend-engineer)
- [ ] D2. 명세 — `/goto`(이슈 이동) · `/search`(검색) · `/issue`(새 이슈 폼 프리필), 전부 네비게이션 (책임. frontend-engineer)
- [ ] D3. 데이터 모델 — 없음 (명령 정의는 프론트 코드 상수) (책임. -)
- [ ] D4. 백엔드 — 없음 (프론트 전용, ADR 2026-07-04). `POST /api/v1/commands/execute` 미도입 (책임. -)
- [ ] D5. 백엔드 테스트 — 해당 없음 (프론트 전용) (책임. -)
- [ ] D6. 프론트 UI — cmdk 명령 팔레트 (`Cmd+K`) (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.3 FR-UX-05 — 키보드 단축키

**우선순위**. 높음 | **선행**. §0 | **Plan slug**. `personal/keymap`

- [ ] D1. 도메인 — Shortcut (책임. backend-engineer)
- [ ] D2. 명세 — 기본 단축키 매핑 (Gmail/Linear 스타일 참조) (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (코드 상수 + §3.3 커스텀) (책임. -)
- [ ] D4. 백엔드 — (해당 없음) (책임. -)
- [ ] D5. 백엔드 테스트 — (해당 없음) (책임. -)
- [ ] D6. 프론트 UI — react-hotkeys-hook + 도움말 (`?`) (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §5 캘린더 (FR-CA, 2개)

### §5.1 FR-CA-01 — 개인 캘린더 (할당/마감일 통합)

**우선순위**. 높음 | **선행**. issue-tracking §6.1 (FR-PL-01) | **Plan slug**. `personal/calendar`

- [ ] D1. 도메인 — CalendarEvent (책임. backend-engineer)
- [ ] D2. 명세 — 할당 이슈 + 마감일 + Worklog 일정 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (조회만, 별도 테이블 X) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/users/me/calendar?from=&to=` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 월/주 캘린더 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §5.2 FR-CA-02 — iCal Export (외부 캘린더 연동)

**우선순위**. 높음 | **선행**. §5.1 | **Plan slug**. `personal/icalendar`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — RFC 5545 + 구독 URL 토큰 (책임. backend-engineer + security-engineer)
- [ ] D3. 데이터 모델 — `user_calendar_tokens(user_id, token, revoked_at)` (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /ical/feed/{token}.ics` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — iCal 포맷 검증 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 구독 URL 발급/취소 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §NFR personalization BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| 프로필 조회 | 100ms | ___ | k6 |
| 환경 설정 적용 (이론적 즉시) | 200ms | ___ | Playwright |
| 캘린더 30일 조회 | 500ms | ___ | k6 |
| iCal Export 응답 | 500ms | ___ | k6 |
| 단축키 동작률 | 100% | ___ | E2E 전수 |
| 명령 팔레트 (cmdk) 응답 | 100ms | ___ | Playwright |
| LCP (프로필/설정) | 2.5s | ___ | Lighthouse CI |
| WCAG 2.1 AA | 0 violations | ___ | axe-core |

### BC 완료 조건

- [ ] §2~§5 (12 FR) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] CHANGELOG.md 정리
- [ ] README.md §7 변경 이력에 "personalization BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "personalization BC 완료"
