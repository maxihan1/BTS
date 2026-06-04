<!-- 117개 FR 역인덱스 (FR ID → BC → 본문 §x.y) + Open Questions -->

# FR 역인덱스 + Open Questions

## §A.1 FR 역인덱스 (117개 전수)

> 출처. SDD `docs/sdd/02-requirements.md` §2.2 "기능 요구사항 (FR) 상세".
> 검증. `scripts/verify-master-plan.sh` — 117개 모두 product/*.md에 등장해야 함.

### 이슈 관리 (FR-IS, 9개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-IS-01 | 이슈 CRUD, 상태 변경 시 워크플로우 검증 + 알림 | 필수 | issue-tracking | §2.1.1 |
| FR-IS-02 | 이슈 타입 (Epic/Story/Task/Subtask/Bug + 커스텀) | 필수 | issue-tracking | §2.1.2 |
| FR-IS-03 | 담당자 (Reporter 1 / Assignee 1 / Watchers N) | 필수 | issue-tracking | §2.1.3 |
| FR-IS-04 | 본문(Markdown) + 우선순위/라벨/환경/영향도 | 필수 | issue-tracking | §2.1.4 |
| FR-IS-05 | 이슈 일괄 편집 + 일괄 상태 전이 | 높음 | issue-tracking | §2.2.1 |
| FR-IS-06 | 이슈 클론 (옵션) | 중간 | issue-tracking | §2.3.1 |
| FR-IS-07 | Resolution 필드 | 필수 | issue-tracking | §2.1.5 |
| FR-IS-08 | 이슈 인쇄 + PDF 출력 | 중간 | issue-tracking | §2.3.2 |
| FR-IS-09 | 라벨 자동완성 | 높음 | issue-tracking | §2.2.2 |

### 컴포넌트 / 버전 (FR-CM, FR-VR, 7개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-CM-01 | 프로젝트별 컴포넌트 CRUD + 컴포넌트 리드 | 필수 | issue-tracking | §3.1.1 |
| FR-CM-02 | 이슈에 다중 컴포넌트 할당 | 필수 | issue-tracking | §3.1.2 |
| FR-CM-03 | 컴포넌트별 기본 담당자 자동 할당 | 높음 | issue-tracking | §3.1.3 |
| FR-VR-01 | 버전 생성 + 시작일/릴리즈 예정일 | 필수 | issue-tracking | §3.2.1 |
| FR-VR-02 | 버전 상태 (Unreleased/Released/Archived) | 필수 | issue-tracking | §3.2.2 |
| FR-VR-03 | Affects/Fix Version 연결 | 필수 | issue-tracking | §3.2.3 |
| FR-VR-04 | 버전 릴리즈 노트 자동 생성 | 중간 | issue-tracking | §3.2.4 |

### 워크플로우 / 자동화 (FR-WF, FR-AT, 10개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-WF-01 | FSM 워크플로우 (상태/전이/조건/검증/후처리) | 필수 | project-workflow | §2.1 |
| FR-WF-02 | 프로젝트별 워크플로우 스킴 + 타입별 매핑 | 필수 | project-workflow | §2.2 |
| FR-WF-03 | 워크플로우 전이 validator/PostAction 런타임 결선 | 필수 | project-workflow | §2.3 |
| FR-AT-01 | 트리거 (생성/변경/댓글/스케줄/Webhook) | 필수 | automation | §2.1 |
| FR-AT-02 | 액션 (필드 변경/담당자/댓글/API 호출) | 필수 | automation | §2.2 |
| FR-AT-03 | 조건 분기 (if-else, 표현식) | 필수 | automation | §2.3 |
| FR-AT-04 | 규칙 충돌 정적 분석 | 필수 | automation | §2.4 |
| FR-AT-05 | 실행 이력 + 디버깅 | 필수 | automation | §2.5 |
| FR-AT-06 | YAML 가져오기/내보내기 (GitOps) | 높음 | automation | §2.6 |
| FR-AT-07 | PR 머지 연동 (Fix Version 자동) | 높음 | automation | §2.7 |

### 알림 (FR-NT, 4개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-NT-01 | 이벤트별 알림 정책 | 필수 | notification-dashboard | §2.1 |
| FR-NT-02 | 채널 (이메일/인앱/Slack/Teams/Webhook) | 필수 | notification-dashboard | §2.2 |
| FR-NT-03 | 수신자 정책 (R/A/W/Lead/역할) | 필수 | notification-dashboard | §2.3 |
| FR-NT-04 | 사용자별 알림 구독 설정 | 높음 | notification-dashboard | §2.4 |

### 멘션 / 히스토리 (FR-MN, FR-HS, 4개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-MN-01 | 본문/댓글 @멘션 + 즉시 알림 | 필수 | issue-tracking | §4.1.1 |
| FR-MN-02 | 멘션 자동완성 | 높음 | issue-tracking | §4.1.2 |
| FR-HS-01 | 이슈 변경 이력 | 필수 | issue-tracking | §5.1.1 |
| FR-HS-02 | 히스토리 조회 UI | 필수 | issue-tracking | §5.1.2 |

### 템플릿 / 링크 / 첨부 / Watcher (FR-TM, FR-LK, FR-AC, FR-WT, 7개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-TM-01 | 프로젝트+타입별 본문 템플릿 | 필수 | issue-tracking | §5.2.1 |
| FR-TM-02 | 템플릿 변수 (작성자/일자/프로젝트) | 높음 | issue-tracking | §5.2.2 |
| FR-LK-01 | 링크 (blocks/relates/duplicates/clones/parent-child) | 필수 | issue-tracking | §5.3.1 |
| FR-LK-02 | 링크 그래프 시각화 | 중간 | issue-tracking | §5.3.2 |
| FR-AC-01 | 첨부 업로드 (최대 100MB/파일) | 필수 | issue-tracking | §4.2.1 |
| FR-AC-02 | 첨부 미리보기 (이미지/PDF/동영상) | 높음 | issue-tracking | §4.2.2 |
| FR-WT-01 | Watcher 추가/제거 + 자동 Watcher | 필수 | issue-tracking | §4.3.1 |

### 일정 / 시간 추적 / 이동 (FR-PL, FR-TT, FR-MV, 6개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-PL-01 | 일정 필드 (Start/Due/Target Date) | 필수 | agile-planning | §6.1 |
| FR-PL-02 | 지연/임박 자동 알림 | 높음 | agile-planning | §6.2 |
| FR-TT-01 | Worklog (추정/실제/잔여 시간) | 필수 | agile-planning | §5.1 |
| FR-TT-02 | 이슈/사용자/기간별 시간 집계 | 높음 | agile-planning | §5.2 |
| FR-MV-01 | 프로젝트 간 이슈 이동 | 필수 | issue-tracking | §6.1.1 |
| FR-MV-02 | 이동 시 히스토리 보존 + 링크 유지 | 필수 | issue-tracking | §6.1.2 |

### 보드 / 백로그 / 타임라인 (FR-BD, FR-BL, FR-TL, 8개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-BD-01 | 칸반 보드 (컬럼 표시, 드래그앤드롭) | 필수 | agile-planning | §2.1 |
| FR-BD-02 | 보드 필터 (담당자/라벨/컴포넌트) | 필수 | agile-planning | §2.2 |
| FR-BD-03 | WIP 제한 + 스윔레인 | 높음 | agile-planning | §2.3 |
| FR-BL-01 | 백로그 우선순위 정렬 (LexoRank) | 필수 | agile-planning | §3.1 |
| FR-BL-02 | 백로그 → 스프린트 드래그 이동 | 필수 | agile-planning | §3.2 |
| FR-TL-01 | 타임라인/로드맵 뷰 (Gantt) | 필수 | agile-planning | §4.1 |
| FR-TL-02 | 이슈 간 의존성 라인 (blocks) | 필수 | agile-planning | §4.2 |
| FR-TL-03 | 타임라인 줌 (주/월/분기) | 높음 | agile-planning | §4.3 |

### 에픽 (FR-EP, 2개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-EP-01 | Epic 이슈 타입 + 자식 이슈 연결 | 필수 | agile-planning | §7.1 |
| FR-EP-02 | Epic 진행률 자동 집계 | 필수 | agile-planning | §7.2 |

### 검색 / Export / Import / API (FR-SR, FR-EX, FR-IM, FR-API, 12개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-SR-01 | 이슈 필터 (다중 필드 조합) | 필수 | search-export-import | §2.1 |
| FR-SR-02 | AQL 텍스트 쿼리 (JQL 호환) | 필수 | search-export-import | §2.2 |
| FR-SR-03 | 필터 저장 및 공유 | 필수 | search-export-import | §2.3 |
| FR-SR-04 | 한글 형태소 기반 전문 검색 | 필수 | search-export-import | §2.4 |
| FR-EX-01 | 필터 결과 CSV/XLSX Export | 필수 | search-export-import | §3.1 |
| FR-EX-02 | 대용량(>1만건) 비동기 Export | 높음 | search-export-import | §3.2 |
| FR-IM-01 | CSV/JSON Import (Jira 마이그레이션) | 필수 | search-export-import | §4.1 |
| FR-IM-02 | Import 매핑 UI | 필수 | search-export-import | §4.2 |
| FR-API-01 | 이슈 CRUD REST API | 필수 | search-export-import | §5.1 |
| FR-API-02 | AQL 검색 REST API | 필수 | search-export-import | §5.2 |
| FR-API-03 | Webhook (외부 시스템 통지) | 필수 | search-export-import | §5.3 |
| FR-API-04 | Personal Access Token | 필수 | search-export-import | §5.4 |

### 대시보드 / 리포트 (FR-DB, FR-RP, 7개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-DB-01 | 사용자 정의 대시보드 | 필수 | notification-dashboard | §3.1 |
| FR-DB-02 | 가젯 시스템 (10종+) | 필수 | notification-dashboard | §3.2 |
| FR-DB-03 | 대시보드 공유 (URL, 임베드) | 높음 | notification-dashboard | §3.3 |
| FR-RP-01 | 번다운 / 번업 차트 | 필수 | notification-dashboard | §4.1 |
| FR-RP-02 | 벨로시티 차트 | 필수 | notification-dashboard | §4.2 |
| FR-RP-03 | CFD (Cumulative Flow Diagram) | 필수 | notification-dashboard | §4.3 |
| FR-RP-04 | Cycle Time / Lead Time 분포 | 높음 | notification-dashboard | §4.4 |

### 권한 관리 (FR-PM, 8개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-PM-01 | 프로젝트 행정 (관리자/멤버 관리) | 필수 | identity-access | §4.1 |
| FR-PM-02 | 이슈 등록/수정/삭제 권한 분리 | 필수 | identity-access | §4.2 |
| FR-PM-03 | 버전/컴포넌트 등록 권한 | 필수 | identity-access | §4.3 |
| FR-PM-04 | 워크플로우/자동화 관리 권한 | 필수 | identity-access | §4.4 |
| FR-PM-05 | 이슈 접근 (Browse, View) | 필수 | identity-access | §4.5 |
| FR-PM-06 | 이슈 보안 수준 | 필수 | identity-access | §4.6 |
| FR-PM-07 | 필드 수준 권한 | 높음 | identity-access | §4.7 |
| FR-PM-08 | 전역 시스템 관리자 역할/권한 인프라 (FR-PM-04·FR-AU-05 선행) | 필수 | identity-access | §4.8 |

### 사용성 (FR-UX, 5개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-UX-01 | 퀵 필터 (보드 상단 즉시 필터) | 필수 | personalization | §4.1 |
| FR-UX-02 | 즐겨찾기 / Star | 필수 | notification-dashboard | §5.1 |
| FR-UX-03 | 개인 알림 보관함 (Inbox) | 필수 | notification-dashboard | §5.2 |
| FR-UX-04 | Slash 명령어 | 높음 | personalization | §4.2 |
| FR-UX-05 | 키보드 단축키 | 높음 | personalization | §4.3 |

### 인증 (FR-AU, 10개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-AU-01 | 플러그형 AuthenticationProvider 구조 | 필수 | identity-access | §2.1 |
| FR-AU-02 | LDAP/AD 연동 | 필수 | identity-access | §2.2 |
| FR-AU-03 | SAML 2.0 SSO | 필수 | identity-access | §2.3 |
| FR-AU-04 | OIDC SSO | 필수 | identity-access | §2.4 |
| FR-AU-05 | 로컬 계정 (외부 협력사용) | 필수 | identity-access | §2.5 |
| FR-AU-06 | 다중 Provider 동시 활성화 | 필수 | identity-access | §2.6 |
| FR-AU-07 | 도메인 기반 자동 라우팅 | 높음 | identity-access | §2.7 |
| FR-AU-08 | 계정 통합 (Account Linking) | 높음 | identity-access | §2.8 |
| FR-AU-09 | 세션/토큰 관리 (JWT + Refresh + PAT) | 필수 | identity-access | §2.9 |
| FR-AU-10 | 인증 감사 로그 | 필수 | identity-access | §2.10 |

### 다중 요소 인증 (FR-MF, 5개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-MF-01 | TOTP (Authenticator 앱) | 필수 | identity-access | §3.1 |
| FR-MF-02 | 백업 코드 (Recovery Codes) | 필수 | identity-access | §3.2 |
| FR-MF-03 | WebAuthn (Passkey/하드웨어 키) | 선택 | identity-access | §3.3 |
| FR-MF-04 | 강제 정책 (관리자 + 민감 프로젝트) | 필수 | identity-access | §3.4 |
| FR-MF-05 | 신뢰 디바이스 (30일 면제) | 높음 | identity-access | §3.5 |

### Slack 통합 (FR-SL, 6개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-SL-01 | Slack App + Bot Token 방식 | 필수 | slack-integration | §2.1 |
| FR-SL-02 | 알림 발송 (DM + 채널) | 필수 | slack-integration | §2.2 |
| FR-SL-03 | Unfurl (Atlas URL 자동 카드) | 높음 | slack-integration | §3.1 |
| FR-SL-04 | Slash 명령어 (`/atlas ...`) | 높음 | slack-integration | §3.2 |
| FR-SL-05 | 인터랙티브 메시지 (버튼/메뉴) | 높음 | slack-integration | §3.3 |
| FR-SL-06 | 채널 ↔ 프로젝트 매핑 | 필수 | slack-integration | §2.3 |

### 개인화 (FR-PR, FR-PF, FR-CA, 9개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-PR-01 | 사용자 프로필 (이름/아바타/타임존/부서) | 필수 | personalization | §2.1 |
| FR-PR-02 | 상태 메시지 (이모지 + 텍스트) | 높음 | personalization | §2.2 |
| FR-PR-03 | 부재중 (Out of Office) | 높음 | personalization | §2.3 |
| FR-PR-04 | LDAP 동기화 필드 vs 사용자 편집 분리 | 필수 | personalization | §2.4 |
| FR-PF-01 | 환경 설정 (테마/언어/날짜포맷) | 필수 | personalization | §3.1 |
| FR-PF-02 | 기본 뷰/시작 페이지 | 높음 | personalization | §3.2 |
| FR-PF-03 | 단축키 커스터마이즈 | 중간 | personalization | §3.3 |
| FR-CA-01 | 개인 캘린더 (할당/마감일 통합) | 높음 | personalization | §5.1 |
| FR-CA-02 | iCal Export (외부 캘린더 연동) | 높음 | personalization | §5.2 |

## §A.2 BC ↔ FR 매핑 카운트

| BC | FR 카운트 | 주요 그룹 |
|---|---|---|
| identity-access | 22 | AU(10) + MF(5) + PM(7) |
| issue-tracking | 29 | IS(9) + CM(3) + VR(4) + AC(2) + MN(2) + WT(1) + LK(2) + HS(2) + TM(2) + MV(2) |
| project-workflow | 2 | WF(2) |
| agile-planning | 14 | BD(3) + BL(2) + EP(2) + TL(3) + TT(2) + PL(2) |
| automation | 7 | AT(7) |
| notification-dashboard | 13 | NT(4) + DB(3) + RP(4) + UX-02,03(2) |
| slack-integration | 6 | SL(6) |
| personalization | 12 | PR(4) + PF(3) + CA(2) + UX-01,04,05(3) |
| search-export-import | 12 | SR(4) + EX(2) + IM(2) + API(4) |
| **합계** | **117** | |

## §A.3 미해결 결정 (Open Questions)

> 실행 직전 확정 필요. ADR 발행 시 이 표에서 제거하고 `docs/adr/<date>-<topic>.md` 생성.

| # | 결정 항목 | 결정 시점 | 영향 BC | 상태 |
|---|---|---|---|---|
| 1 | pgmq 이미지 (자체 빌드 vs tembo-io vs coredb) | project-workflow §1 PoC | 전 BC (이벤트 통신) | 보류 — `docs/poc/context-notes.md` 2026-05-19 기록 |
| 2 | Gantt 라이브러리 (자체 SVG vs Recharts vs syncfusion) | agile-planning §1 PoC | agile-planning §4 (타임라인) | 보류 — PoC ADR |
| 3 | 9개 BC 진입 순서 (BC 의존 그래프 참조) | 첫 작업 시점 | 전체 | Maxi 결정 영역 — README §0.7 권장 순서 참조 |
| 4 | `scripts/verify-master-plan.sh` CI 통합 시점 | 첫 BC 작업 진입 직전 | CI | 작성 후 즉시 통합 권장 |
| 5 | 이슈 키 prefix 결정 (예. `ATL-`, 프로젝트별 prefix) | issue-tracking §2.1.1 진입 시 | issue-tracking + DATA.md | DATA.md §이슈키 영속성 가이드 따름 |
| 6 | identity-access 우선순위 (FR-AU-01 → 02/03 → 04/05/06~10 순) | identity-access 진입 직전 | identity-access 전체 | LDAP/SAML 사내 우선 → OIDC/Passkey/감사 순 권장 |
| 7 | TipTap 50블록 PoC (Wiki v0.5+ 준비) 시점 | issue-tracking §2.1.4 (Markdown 본문) 시점 또는 별도 | issue-tracking + 향후 wiki BC | 본문 에디터를 처음 만들 때 variant 추상 (issue-body | comment | wiki) 함께 검증 |
| 8 | issue-tracking 내 FR 진입 순서 (코어 → 보강 → 정리) | issue-tracking 진입 직전 | issue-tracking 29 FR | IS-01~04, 07 우선 → CM/VR → AC/MN/WT → LK/HS/TM/MV/PDF 순 권장 |

## §A.4 변경 이력 (append-only)

- 2026-05-20. **재편성**. SDD 17장 Phase 0~4 분할 폐기, BC 단위 완제품 매핑으로 전환. Phase 컬럼 → BC 컬럼.
- 2026-05-20. 초안. SDD `02-requirements.md`에서 117개 FR ID 전수 추출. Open Questions 8건 등재.
