<!-- notification-dashboard BC — 알림/대시보드/가젯/리포트/Inbox 14 FR + STOMP WebSocket PoC -->

# notification-dashboard BC

**소속 FR**. 14개 (NT 5 + DB 3 + RP 4 + UX-02,03 2).
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

## §2 알림 (FR-NT, 5개)

### §2.1 FR-NT-01 — 이벤트별 알림 정책

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `notify/policy`

- [x] D1. 도메인 — NotificationPolicy (책임. backend-engineer) — PR #118
- [x] D2. 명세 — 이벤트 종류 × 수신자 × 채널 매트릭스 (책임. backend-engineer) — PR #118 (event_type=SDD §9.1.2 9종)
- [x] D3. 데이터 모델 — `notification_policies(project_key?, event_type, recipient_role, channel, enabled)` (책임. db-engineer) — PR #118 (V400~V401, project_key 문자열=BC 격리)
- [x] D4. 백엔드 — 정책 CRUD + 평가 엔진 (책임. backend-engineer) — PR #118 (전역/프로젝트 override replace, SYSTEM_ADMIN)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #118 (단위+통합+ArchUnit+e2e)
- [x] D6. 프론트 UI — 관리자 정책 페이지 (책임. designer → frontend-engineer) — PR #124 (`/admin/notification-policies`, SYSTEM_ADMIN, 전역 정책 CRUD)
- [x] D7. E2E (책임. qa-engineer) — PR #124 (S1~S5 + 비관리자 차단, MSW 헤더 reset)

### §2.2 FR-NT-02 — 채널 (이메일/인앱, Slack=slack-integration BC)

**우선순위**. 필수 | **선행**. §1, §2.1 | **Plan slug**. `notify/channels`

**범위(누적)**. 인앱(WebSocket) slice(PR #126/#137) + **이메일 채널**(EmailChannelSender — Spring Mail/MimeMessageHelper UTF-8 + Testcontainers MailHog, PR #139) — **FR-NT-02 범위(인앱+이메일) 전부 완료**. **Webhook 채널은 FR-NT-05로 분리·완료**(per-user 알림 모델과 맞지 않아 `WebhookRequested` 이벤트 디스패처로 재설계 + 전이-이벤트 발행 파이프라인이 cross-BC라 별도 FR, ADR 2026-06-12 amendment + §2.5 FR-NT-05 참조). Slack=slack-integration BC, Teams=범위 밖. D6·D7(프론트/E2E)은 인앱 채널 기준 완료(PR #137; 이메일은 시스템 자동 발송이라 별도 UI 불요, 채널 구독 on/off는 FR-NT-04).

- [x] D1. 도메인 — Channel 추상 + 인앱·이메일 구현 (Webhook=FR-NT-05 분리) (책임. backend-engineer)
- [x] D2. 명세 — fanout + 재시도 정책 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `notifications(payload, channel, status)` (책임. db-engineer)
- [x] D4. 백엔드 — pgmq consumer → 인앱(STOMP) + 이메일(Spring Mail). Webhook=FR-NT-05 분리(WebhookRequested 디스패처), Slack=slack-integration BC (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — Testcontainers MailHog (이메일 발송→수신 + 한국어 제목 보존 검증) (책임. backend-engineer)
- [x] D6. 프론트 UI — STOMP 클라이언트 + 토스트 (sonner) (책임. frontend-engineer) — PR #137 (`/ws` 연결 + `/user/queue/notifications` 구독 + Zod 파싱 + sonner 토스트, 인증 연동 hook)
- [x] D7. E2E (책임. qa-engineer) — PR #137 (Playwright routeWebSocket STOMP 핸드셰이크 mock, S1 title+body / S2 body=null)

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| WebSocket 알림 지연 | 1s | ___ |

### §2.3 FR-NT-03 — 수신자 정책 (R/A/W/Lead/역할)

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `notify/recipients`

- [x] D1. 도메인 — RecipientRole 5종 해석 (책임. backend-engineer) — PR #159
- [x] D2. 명세 — 수신자 해석 규칙 + 보안수준 visibility 필터 (책임. backend-engineer) — PR #159
- [x] D3. 데이터 모델 — (정책 활용, 마이그레이션 0 — 기존 테이블 읽기) (책임. db-engineer) — PR #159
- [x] D4. 백엔드 — EventRecipientResolver 5역할(WATCHER/COMPONENT_LEAD/PREVIOUS_ASSIGNEE/PROJECT_MEMBER/PROJECT_ADMIN) + cross-BC 포트 3종 + IssueVisibilityPort(VIEW 매트릭스+보안등급 재사용, RULE_OWNER는 automation BC 부재로 skip) (책임. backend-engineer) — PR #159
- [x] D5. 백엔드 테스트 — 단위 + Testcontainers 통합 + ArchUnit (책임. backend-engineer) — PR #159
- [x] D6. 프론트 UI — 정책 페이지 확장 (책임. frontend-engineer) — PR #164 (수신자 역할 설명 동적 헬퍼 + RULE_OWNER 미지원 비활성, 9줄 범례 대신 선택역할 1줄 헬퍼+상시 안내. 공유 select.tsx 변경 0, 서버 카탈로그 위에서만 동작)
- [x] D7. E2E (책임. qa-engineer) — PR #164 (S6 활성역할 정책 생성→SPA 영속, S7 RULE_OWNER aria-disabled+동적헬퍼+상시안내. 기존 S1~S5 보존)

### §2.4 FR-NT-04 — 사용자별 알림 구독 설정

**우선순위**. 높음 | **선행**. §2.1 | **Plan slug**. `notify/user-subscription`

**범위**. opt-out 기본(행 없으면 수신) + 관리자 정책(FR-NT-01)과 AND 결합(사용자는 끄기만, reduce-only). 채널 IN_APP·EMAIL만 사용자 설정(SLACK=별도 BC·TEAMS=범위밖·WEBHOOK=FR-NT-05). 이벤트 NotificationEventType 10종 전부. NotificationWorker가 발송 직전 배치 필터. ADR `2026-06-19-fr-nt-04-user-notification-subscription`. **FR-NT-04 전체 완료**(PR #162).

- [x] D1. 도메인 — UserSubscription (책임. backend-engineer) — PR #162 (CONFIGURABLE_CHANNELS 단일출처, Clock 주입)
- [x] D2. 명세 — opt-in/out 단위 (책임. backend-engineer) — PR #162 (opt-out 기본 + AND 결합, 이벤트×채널 단위)
- [x] D3. 데이터 모델 — `user_notification_subs(user_id, event_type, channel, enabled)` (책임. db-engineer) — PR #162 (Flyway V404 + init_codegen 미러, UNIQUE + partial index)
- [x] D4. 백엔드 — `GET/PATCH /api/v1/users/me/notifications` (책임. backend-engineer) — PR #162 (currentActorId 401, 명시 크기상한, 워커 구독 필터)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #162 (도메인·repository·service·controller·워커 필터 Testcontainers, EC9/created_at 단언)
- [x] D6. 프론트 UI — 개인 설정 페이지 (책임. designer → frontend-engineer) — PR #162 (`/settings/notifications` data-driven 매트릭스 토글, 버튼 토글)
- [x] D7. E2E (책임. qa-engineer) — PR #162 (Playwright S1 매트릭스 표시 + S4 토글·SPA 재진입 영속)

### §2.5 FR-NT-05 — Webhook 알림 채널 (전이 post-action 이벤트 발행 + HTTP POST 디스패처)

**우선순위**. 중간 | **선행**. §2.1, §2.2 | **분리 근거**. FR-NT-02에서 분리 (ADR `2026-06-14-fr-nt-05-transition-event-outbox.md`) | **Plan slug**. `fr-nt-05-transition-event-publish`(PR1) · `fr-nt-05-webhook-dispatcher`(PR2) · `fr-nt-05-d6-post-action-api`(PR-A) · `fr-nt-05-d6-d7-post-action-ui`(PR-B)

**범위**. cross-BC라 PR 4개로 분할. PR 1 (issue-tracking BC) — 전이 post-action emitEvents를 `q_transition_events` pgmq 큐에 발행하는 파이프라인. PR 2 (notification BC) — `WebhookRequested` 소비 + 외부 URL HTTP POST 디스패처. PR-A (project-workflow BC) — 전이 post-action 런타임 CRUD API(D6 백엔드 enabler). PR-B (apps/web) — 워크플로우 post-action 설정 UI + E2E(D6/D7). **FR-NT-05 전체 완료**(D1~D7 [x]).

- [x] D1. 도메인 — TransitionEventPublisher (책임. backend-engineer) — PR #140 (PR 1, issue-tracking)
- [x] D2. 명세 — 큐/이벤트 계약 (`q_transition_events`, DomainEvent 직렬화) (책임. backend-engineer) — PR #140
- [x] D3. 데이터 모델 — Flyway V022 `q_transition_events` 큐 생성 + init_codegen 미러 (책임. db-engineer) — PR #140
- [x] D4. 백엔드 — `transitionIssue()` emitEvents 배선 (PR 1) + WebhookRequested HTTP POST 디스패처 (PR 2, notification BC: WebhookDispatchWorker/WebhookDispatcher/WebhookUrlValidator) (책임. backend-engineer) — PR2
- [x] D5. 백엔드 테스트 — Testcontainers: 전이→큐 enqueue 확인(PR1) + WebhookRequested 소비→HTTP POST/SSRF 차단/생명주기(PR2) (책임. backend-engineer) — PR2
- [x] D6. 프론트 UI — 워크플로우 post-action 설정 UI (선행. project-workflow post-action CRUD API 신설) (책임. designer → frontend-engineer) — PR #144 (workflows.$key 하단 admin 섹션, CALL_WEBHOOK url/method CRUD, isSystemAdmin 게이팅, PR-A #143 API 소비)
- [x] D7. E2E (책임. qa-engineer) — PR #144 (Playwright: admin 추가→목록→수정→삭제 + 비admin 미노출, MSW stateful post-action 핸들러)

## §3 대시보드 (FR-DB, 3개)

### §3.1 FR-DB-01 — 사용자 정의 대시보드 (개인/팀/공유)

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `dashboard/custom`

- [x] D1. 도메인 — Dashboard Aggregate (책임. backend-engineer) — PR #176 (Dashboard Root + DashboardShare + DashboardVisibility, 불변식 정규화/OCC/64KB)
- [x] D2. 명세 — 권한 (개인/팀/공유) (책임. backend-engineer) — PR #176 (PRIVATE/TEAM=dashboard_shares 명시 사용자/ORG, ADR 2026-06-22, PUBLIC=FR-DB-03)
- [x] D3. 데이터 모델 — `dashboards(owner_id, visibility, layout)` (책임. db-engineer) — PR #176 (V405 + init_codegen 미러, deleted_at 소프트삭제, dashboard_shares FK CASCADE, 부분 인덱스)
- [x] D4. 백엔드 — CRUD API (책임. backend-engineer + security-engineer) — PR #176 (visibility 권한 404 숨김/403, OCC WHERE절, UNION 목록 페이지네이션, NOTIF_DASHBOARD_*)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #176 (430 tests, ArchUnit BC격리 vacuous방지, 적대적리뷰 B1/B2 입력검증 400 수정)
- [x] D6. 프론트 UI — react-grid-layout (책임. designer → frontend-engineer) — PR #178 (react-grid-layout@1.5 WidthProvider 12컬럼+모바일 overflow-x-auto, placeholder 타일, `/dashboards` 목록+상세 CRUD, 권한 게이팅 canEditDashboard, OCC invalidate-only, 설정저장 layout 원자저장)
- [x] D7. E2E (책임. qa-engineer) — PR #178 (S1~S8 + S1b 카드네비 회귀가드 + 접근성 키보드 + 회귀, S3b 드래그/리사이즈는 RGL headless containerWidth=0 제약 SKIP→DashboardGrid 단위 커버)

### §3.2 FR-DB-02 — 가젯 시스템 (10종+ 표준)

**우선순위**. 필수 | **선행**. §3.1 | **Plan slug**. `dashboard/gadgets`

> **데이터 모델 deviation (ADR `2026-06-29-fr-db-02-gadget-system`, Maxi 확정)**. 별도 `dashboard_gadgets` 테이블 미채택. 가젯은 기존 `dashboards.layout` JSONB 배열 항목으로 임베드(`{i,x,y,w,h,gadgetType,config}`) — 위치 단일 진실원천, FR-DB-01 ADR D4 계승, 신규 테이블/마이그레이션 0. 가젯 **데이터**는 프론트가 기존 BC API(/search/aql 등) 직접 호출(SDD 14.4), notification BC는 **설정 저장·검증 + 카탈로그 API**만 담당(원안 "데이터 API 10종"은 BC 격리 위반이라 기각). pie/bar용 필드별 집계 엔드포인트는 issue-tracking BC(별도 PR). MVP 가시 가젯=AQL/정적 6종(즉시)+집계 3종(집계 PR 후)+선행 FR 의존 3종(후속), 카탈로그 enum 12종 정의.

**PR 분할**. PR1(#205) = 백엔드 가젯 저장·검증(D1~D5). PR2 = 프론트 컴포넌트·카탈로그·E2E(D6/D7).

- [x] D1. 도메인 — GadgetType enum 12종(SDD 14.2) + per-type config 형식검증 + layout 임베드 (책임. backend-engineer + Maxi) — PR #205 (단일 출처 디스크립터, favorites식 형식만 검증)
- [x] D2. 명세 (책임. backend-engineer) — PR #205 (spec `2026-06-29-fr-db-02-gadgets`, gadgetType 선택/enabled strict/라우팅 EC12)
- [x] D3. 데이터 모델 — `dashboards.layout` JSON 임베드(별도 테이블 아님) (책임. backend-engineer) — PR #205 (신규 마이그레이션 0, `Dashboard.validateLayout` 가젯-aware 확장)
- [x] D4. 백엔드 — 가젯 저장·검증 + `GET /dashboards/gadget-catalog` 카탈로그 API (책임. backend-engineer) — PR #205 (데이터 API 아님 — 프론트 직접 fetch)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #205 (도메인 40 + 컨트롤러 + Testcontainers HTTP end-to-end round-trip/라우팅/errorCode)
- [x] D6. 프론트 UI — Gadget 컴포넌트 MVP 6종 + 카탈로그 (책임. designer → frontend-engineer) — PR #209 (이슈 4종 assigned_to_me/recently_created/filter_result/issue_count + 정적 2종 text_widget/link_list, 카탈로그 모달 12종 enum·enabled=false 6종 "준비 중" 게이팅, 프로젝트 지정형 데이터 fetch[fetchIssues assignee+created_at DESC / searchAql / fetchFilter, projectKey 필수], "가젯 추가" 단일 버튼 일원화[legacy 빈 위젯 추가 폐기]+빈 그리드 1차 CTA 모달 연결, XSS 3계층[text plain·link http/https 화이트리스트+noopener]. 차트 4종 pie/bar/created_vs_resolved/sprint_burndown + activity 2종은 enabled=false 카탈로그 등록만 — 렌더는 집계 엔드포인트[issue-tracking BC 별도 PR]·선행 FR 의존으로 후속)
- [x] D7. E2E (책임. qa-engineer) — PR #209 (S1 추가+저장 / S2 recently_created 이슈 렌더 / S4 link_list noopener / S6 enabled=false 게이팅 / S7 비소유 읽기전용. 드래그/리사이즈는 RGL headless containerWidth=0 제약 SKIP→단위 커버)

### §3.3 FR-DB-03 — 대시보드 공유 (URL, 임베드)

**우선순위**. 높음 | **선행**. §3.1 | **Plan slug**. `dashboard/share`

> **PR 분할**. PR1(#216) = 백엔드 D1~D5. PR2 = 프론트 D6/D7(공유 모달·익명 뷰·E2E). ADR `2026-07-02-fr-db-03-dashboard-share`(직교 토큰 모델·익명 뷰=정적 가젯만·iframe MVP same-origin+스니펫).

- [x] D1. 도메인 — DashboardShareToken(불투명 토큰 SHA-256 hex·취소=하드삭제 ephemeral) + ShareTokenMinter + AnonymousLayoutSanitizer(STATIC 화이트리스트 fail-closed) (책임. backend-engineer + security-engineer) — PR #216
- [x] D2. 명세 — 공유 URL 토큰 + iframe 임베드 + 권한 (책임. backend-engineer + security-engineer) — PR #216 (직교 토큰=visibility 불변·익명뷰 정적가젯만·spec 2026-07-02)
- [x] D3. 데이터 모델 — `dashboard_share_tokens`(token_hash TEXT UNIQUE·FK CASCADE·V408) (책임. db-engineer) — PR #216 (init_codegen 미러)
- [x] D4. 백엔드 — 관리 `POST/GET/DELETE /api/v1/dashboards/{id}/shares`(소유자·MAX 20) + 익명 `GET /api/v1/public/dashboards/{token}`(permitAll GET·정화·404 열거차단) (책임. backend-engineer + security-engineer) — PR #216 (SecurityConfig cross-BC permitAll)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) — PR #216 (토큰 해싱·열거차단 404·정화 fail-closed·Clock 만료·교차조회 차단·HTTP e2e 라운드트립)
- [x] D6. 프론트 UI — 공유 모달 + 임베드 코드 복사 (책임. designer → frontend-engineer) — PR #217 (공유 모달[링크생성·복사·PRIVATE/TEAM amber경고·임베드 스니펫·발급목록·인라인취소·빈상태], 상세페이지 공유버튼 canEditDashboard 게이팅, 익명 뷰 라우트 `/dashboards/shared/{token}` raw fetch + PublicGadgetRenderer 정적 화이트리스트 fail-closed[데이터가젯=로그인필요 플레이스홀더·useGadgetData 미호출] + DashboardGrid publicMode 읽기전용 + embed=1 크롬최소화)
- [x] D7. E2E (책임. qa-engineer) — PR #217 (발급→익명열람[정적 렌더+데이터 플레이스홀더+편집UI부재]→무효/취소 토큰 404, MSW 공유 store stateful. 부수 hot-fix: useGadgetData AQL 외피[.data/.meta.page.totalElements] main CI red 해소)

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

> **구현 범위 (#184, Maxi 확정 2026-06-24)**. 백엔드 D1~D5만 본 PR — 프론트 D6/D7(Star 버튼·즐겨찾기 사이드바·E2E)은 후속 PR. notification 모듈 `com.bts.notification.favorite` 패키지(FR-DB-01 dashboard 패키지 선례). 대상 타입 4종(ISSUE/FILTER/DASHBOARD/PROJECT, FILTER는 FR-SR-03 필터 저장 미구현이라 정의만·실사용 후속). 즐겨찾기 해제=하드 삭제(DATA.md §3 동기화), 등록 시 형식만 검증(대상 존재/권한 미검증=개인 북마크·BC 격리), target_id 문자열·FK 미적용, UNIQUE(user_id,target_type,target_id) 멱등. ADR `2026-06-24-fr-ux-02-favorites`.

- [x] D1. 도메인 — Favorite (책임. backend-engineer) (완료. PR #184 — Favorite Aggregate + FavoriteTargetType enum 4종 + FavoriteDomainException, 명시 형식검증)
- [x] D2. 명세 — 대상 (이슈/필터/대시보드/프로젝트) (책임. backend-engineer) (완료. PR #184 — 4종 타입, 형식만 검증, 멱등 API)
- [x] D3. 데이터 모델 — `favorites(user_id, target_type, target_id)` (책임. db-engineer) (완료. PR #184 — V406 + init_codegen 미러, 하드삭제·FK없음·UNIQUE 복합)
- [x] D4. 백엔드 — `POST/DELETE /api/v1/favorites` (책임. backend-engineer) (완료. PR #184 — POST 멱등 201/200·DELETE 대상기준 204·GET 본인 목록, currentActorId 401, FavoriteExceptionHandler basePackages 한정)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) (완료. PR #184 — 도메인 단위·service mockk·repository Testcontainers(누출/멱등)·controller 슬라이스·스키마 마이그레이션)
- [x] D6. 프론트 UI — Star 버튼 + 즐겨찾기 사이드바 (책임. designer → frontend-engineer) (완료. PR #185 — Star 토글 3종 배치(이슈/대시보드/프로젝트) + Header ⭐ 드롭다운(타입별 그룹, 좌측 사이드바 인프라 부재로 Header 드롭다운 채택), api/favorites.ts(워처 패턴 계승·invalidate-only), targetId 라벨 그대로(제목 해석 안 함), MSW stateful 핸들러)
- [x] D7. E2E (책임. qa-engineer) (완료. PR #185 — favorites.spec.ts 4시나리오: Star 토글 라운드트립·드롭다운 건수단언·SPA Link 이동·빈 상태)

### §5.2 FR-UX-03 — 개인 알림 보관함 (Inbox)

**우선순위**. 필수 | **선행**. §2.2 | **Plan slug**. `dashboard/inbox`

> **구현 범위 (#186, Maxi 확정 2026-06-25)**. 백엔드 D1~D5만 본 PR — 프론트 D6/D7(Inbox 페이지·카운트 뱃지·E2E)은 후속 PR. **데이터 모델 deviation**. 별도 `inbox_items` 조인 테이블 폐기 → 기존 `notifications`(FR-NT-02 V402, 수신자별 fanout + `read_at` 보유) 확장(V407: `archived_at` + `actor_user_id`). read/archive 2축 독립. 기능 범위는 SDD 9.2 고급 포함(코어 + 검색(텍스트/발신자/기간) + 일괄 읽음). **프론트 deviation(#187, Maxi 확정 2026-06-25)**. 그룹화(issueKey 묶음렌더)는 미채택 — 평면 목록 + issueKey 검색 필터로 충족(백엔드 응답에 그룹 메타 없음, 묶음렌더 deferred). ADR `2026-06-25-fr-ux-03-inbox-data-model`.

- [x] D1. 도메인 — Notification 상태 전이(markRead/markUnread/archive/unarchive, 멱등) (책임. backend-engineer) (완료. PR #186 — 별도 InboxItem 미신설, 기존 Notification Aggregate에 archivedAt/actorUserId 필드 + copy 기반 전이)
- [x] D2. 명세 — 읽음/안읽음 + 보관 + 필터 (책임. backend-engineer) (완료. PR #186 — read/archive 2축, 탭(all/unread/archived), 검색, 일괄 읽음)
- [x] D3. 데이터 모델 — `notifications` 확장(`archived_at`, `actor_user_id`, 미읽음 부분 인덱스) (책임. db-engineer) (완료. PR #186 — V407 + init_codegen 미러. 별도 inbox_items 테이블 폐기, notifications가 이미 수신자별 fanout이라 1:1 중복 회피)
- [x] D4. 백엔드 — `GET /api/v1/users/me/inbox` + 미읽음 카운트 + 읽음/보관 토글 + 일괄 읽음 API (책임. backend-engineer) (완료. PR #186 — 본인+IN_APP 한정, 401/404 격리, no-bump UPDATE(COALESCE 시각 보존), PATCH 204, InboxExceptionHandler 격리)
- [x] D5. 백엔드 테스트 (책임. backend-engineer) (완료. PR #186 — 도메인 단위·repository Testcontainers(탭/검색/페이지네이션/멱등 보존/타인 격리/IN_APP)·service mockk·controller 슬라이스·worker actor 저장 회귀·V407 스키마 마이그레이션)
- [x] D6. 프론트 UI — Inbox 페이지 + 카운트 뱃지 (책임. designer → frontend-engineer) (완료. PR #187 — Header 🔔 종+미읽음 뱃지(N=0 숨김·>99 "99+")→`/inbox` 페이지(탭 3종·검색·페이지네이션·읽음/보관 토글·전체 읽음). 발신자=fetchUsersByIds 이름 해석(null="시스템"). **그룹화 deviation**. 평면 목록 채택 — SDD 9.2 "issueKey 묶음렌더"는 미채택, issueKey는 검색 필터로 제공(백엔드 응답에 그룹 메타 없음). STOMP 새 알림 시 unread-count+목록 invalidate. **기간 필터는 bare date→ISO Instant 변환**(백엔드 Instant? 정합))
- [x] D7. E2E (책임. qa-engineer) (완료. PR #187 — inbox.spec.ts 8 시나리오: 진입·탭전환·읽음/보관 라운드트립·일괄읽음·검색·빈상태·이슈이동. E2E-9 보관함 빈 상태는 분별 시드 인프라 부재로 SKIP)

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

- [ ] §2~§5 (14 FR) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] CHANGELOG.md 정리
- [ ] README.md §7 변경 이력에 "notification-dashboard BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "notification-dashboard BC 완료"
