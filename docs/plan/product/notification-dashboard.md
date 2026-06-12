<!-- notification-dashboard BC — 알림/대시보드/가젯/리포트/Inbox 13 FR + STOMP WebSocket PoC -->

# notification-dashboard BC

**소속 FR**. 13개 (NT 4 + DB 3 + RP 4 + UX-02,03 2).
**책임**. 알림 정책/채널/수신자/구독 + 대시보드/가젯/리포트 + 즐겨찾기/Inbox.
**SDD 참조**. 09장 (알림), 14장 (대시보드/리포트).
**다른 BC와의 경계**. 모든 BC의 pgmq 이벤트 수신자. Slack은 별도 BC(slack-integration).

## §0 진입 조건

- [ ] identity-access §2.9 (세션) 완료 (Inbox는 사용자 의존)
- [ ] issue-tracking §2.1.1 (이벤트 발행 대상) 완료
- [ ] §1 기술 검증 통과 (아래)

## §1 기술 검증

### §1.1 STOMP WebSocket 재연결 PoC (1일)

**SDD**. 21.8. **checklist.md 위임**. §1.10. **ADR 후보**. 없음.

- [ ] Spring WebSocket (STOMP) 서버 동작
- [ ] `@stomp/stompjs` + `reconnecting-websocket` 클라이언트 동작
- [ ] 지수 백오프 5s → 60s 검증
- [ ] 네트워크 분리/복귀 시나리오 통합 테스트 통과
- [ ] 알림 지연 p95 < 1s

## §2 알림 (FR-NT, 4개)

### §2.1 FR-NT-01 — 이벤트별 알림 정책

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `notify/policy`

- [x] D1. 도메인 — NotificationPolicy (책임. backend-engineer) — PR #118
- [x] D2. 명세 — 이벤트 종류 × 수신자 × 채널 매트릭스 (책임. backend-engineer) — PR #118 (event_type=SDD §9.1.2 9종)
- [x] D3. 데이터 모델 — `notification_policies(project_key?, event_type, recipient_role, channel, enabled)` (책임. db-engineer) — PR #118 (V400~V401, project_key 문자열=BC 격리)
- [x] D4. 백엔드 — 정책 CRUD + 평가 엔진 (책임. backend-engineer) — PR #118 (전역/프로젝트 override replace, SYSTEM_ADMIN)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #118 (단위+통합+ArchUnit+e2e)
- [x] D6. 프론트 UI — 관리자 정책 페이지 (책임. designer → frontend-engineer) — PR #124 (`/admin/notification-policies`, SYSTEM_ADMIN, 전역 정책 CRUD)
- [x] D7. E2E (책임. qa-engineer) — PR #124 (S1~S5 + 비관리자 차단, MSW 헤더 reset)

### §2.2 FR-NT-02 — 채널 (이메일/인앱/Webhook, Slack=slack-integration BC)

**우선순위**. 필수 | **선행**. §1, §2.1 | **Plan slug**. `notify/channels`

**범위(이번 PR)**. 인앱(WebSocket) slice — 발송 코어(notifications + NotificationWorker pgmq consumer + NotificationChannelSender 추상) + STOMP 서버/JWT 인증 + 멘션·담당·리포터 수신. `[~]`(D1·D4·D5)는 인앱 채널만 완료이며 이메일/Webhook 채널은 동일 추상 위 후속 PR. D6·D7(프론트/E2E)도 후속 PR.

- [~] D1. 도메인 — Channel 추상 + 5종 구현 (책임. backend-engineer)
- [x] D2. 명세 — fanout + 재시도 정책 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `notifications(payload, channel, status)` (책임. db-engineer)
- [~] D4. 백엔드 — pgmq consumer → 이메일(Spring Mail) + WebSocket(STOMP) + (Slack은 slack-integration BC) + Webhook (책임. backend-engineer)
- [~] D5. 백엔드 테스트 — Testcontainers MailHog (책임. backend-engineer)
- [ ] D6. 프론트 UI — STOMP 클라이언트 + 토스트 (sonner) (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| WebSocket 알림 지연 | 1s | ___ |

### §2.3 FR-NT-03 — 수신자 정책 (R/A/W/Lead/역할)

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `notify/recipients`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 수신자 해석 규칙 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (정책 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — RecipientResolver (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 정책 페이지 확장 (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.4 FR-NT-04 — 사용자별 알림 구독 설정

**우선순위**. 높음 | **선행**. §2.1 | **Plan slug**. `notify/user-subscription`

- [ ] D1. 도메인 — UserSubscription (책임. backend-engineer)
- [ ] D2. 명세 — opt-in/out 단위 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `user_notification_subs(user_id, event_type, channel, enabled)` (책임. db-engineer)
- [ ] D4. 백엔드 — `GET/PATCH /api/v1/users/me/notifications` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 개인 설정 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §3 대시보드 (FR-DB, 3개)

### §3.1 FR-DB-01 — 사용자 정의 대시보드 (개인/팀/공유)

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `dashboard/custom`

- [ ] D1. 도메인 — Dashboard Aggregate (책임. backend-engineer)
- [ ] D2. 명세 — 권한 (개인/팀/공유) (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `dashboards(owner_id, visibility, layout)` (책임. db-engineer)
- [ ] D4. 백엔드 — CRUD API (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — react-grid-layout (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.2 FR-DB-02 — 가젯 시스템 (10종+ 표준)

**우선순위**. 필수 | **선행**. §3.1 | **Plan slug**. `dashboard/gadgets`

- [ ] D1. 도메인 — Gadget 다형성 + 10종 명세 (책임. backend-engineer + Maxi)
- [ ] D2. 명세 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `dashboard_gadgets(gadget_type, config)` (책임. db-engineer)
- [ ] D4. 백엔드 — Gadget 데이터 API 10종 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — Gadget 컴포넌트 10종 + 카탈로그 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.3 FR-DB-03 — 대시보드 공유 (URL, 임베드)

**우선순위**. 높음 | **선행**. §3.1 | **Plan slug**. `dashboard/share`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 공유 URL + iframe 임베드 + 권한 (책임. backend-engineer + security-engineer)
- [ ] D3. 데이터 모델 — `dashboard_share_tokens` (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/dashboards/{id}/share` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 공유 모달 + 임베드 코드 복사 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §4 리포트 (FR-RP, 4개)

### §4.1 FR-RP-01 — 번다운 / 번업 차트

**우선순위**. 필수 | **선행**. agile-planning §3.2 (스프린트) | **Plan slug**. `report/burndown`

- [ ] D1. 도메인 — BurndownPoint (책임. backend-engineer)
- [ ] D2. 명세 — 계산 알고리즘 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (sprint + worklog 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/sprints/{id}/burndown` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — recharts 라인 차트 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.2 FR-RP-02 — 벨로시티 차트

**우선순위**. 필수 | **선행**. §4.1 | **Plan slug**. `report/velocity`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 스토리포인트 vs 완료 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/projects/{id}/velocity` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — recharts 바 차트 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.3 FR-RP-03 — CFD (Cumulative Flow Diagram)

**우선순위**. 필수 | **선행**. §4.1 | **Plan slug**. `report/cfd`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — 상태별 누적 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_history` 활용 + 인덱스 (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/projects/{id}/cfd?from=&to=` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — recharts 영역 차트 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.4 FR-RP-04 — Cycle Time / Lead Time 분포

**우선순위**. 높음 | **선행**. §4.3 | **Plan slug**. `report/cycle-lead-time`

- [ ] D1. 도메인 — CycleTime / LeadTime VO (책임. backend-engineer)
- [ ] D2. 명세 — 상태 시작/종료 시점 정의 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `issue_history` 활용 (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/projects/{id}/cycle-time` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 히스토그램 + 박스플롯 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §5 즐겨찾기 / Inbox (FR-UX-02, FR-UX-03)

### §5.1 FR-UX-02 — 즐겨찾기 / Star

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `dashboard/star`

- [ ] D1. 도메인 — Favorite (책임. backend-engineer)
- [ ] D2. 명세 — 대상 (이슈/필터/대시보드/프로젝트) (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `favorites(user_id, target_type, target_id)` (책임. db-engineer)
- [ ] D4. 백엔드 — `POST/DELETE /api/v1/favorites` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — Star 버튼 + 즐겨찾기 사이드바 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §5.2 FR-UX-03 — 개인 알림 보관함 (Inbox)

**우선순위**. 필수 | **선행**. §2.2 | **Plan slug**. `dashboard/inbox`

- [ ] D1. 도메인 — InboxItem (책임. backend-engineer)
- [ ] D2. 명세 — 읽음/안읽음 + 보관 + 필터 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `inbox_items(user_id, notification_id, read_at, archived_at)` (책임. db-engineer)
- [ ] D4. 백엔드 — `GET /api/v1/users/me/inbox` + 상태 변경 API (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — Inbox 페이지 + 카운트 뱃지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §NFR notification-dashboard BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| WebSocket 알림 지연 | 1s | ___ | Playwright + STOMP trace |
| 이메일 발송 (큐 → MailHog) | 5s | ___ | Testcontainers |
| 대시보드 렌더 (10 가젯) | 2s | ___ | Playwright trace |
| 번다운 차트 데이터 조회 | 500ms | ___ | k6 |
| CFD 30일 데이터 조회 | 1s | ___ | k6 |
| Inbox 100건 페이지네이션 | 300ms | ___ | k6 |
| LCP (대시보드) | 2.5s | ___ | Lighthouse CI |
| 메인 번들 (gzip) | 200KB | ___ | bundle-analyzer (recharts 분리) |
| WCAG 2.1 AA | 0 violations | ___ | axe-core |

### BC 완료 조건

- [ ] §2~§5 (13 FR) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] CHANGELOG.md 정리
- [ ] README.md §7 변경 이력에 "notification-dashboard BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "notification-dashboard BC 완료"
