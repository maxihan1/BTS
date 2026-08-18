<!-- 143개 FR 역인덱스 (FR ID → BC → 본문 §x.y) + Open Questions -->

# FR 역인덱스 + Open Questions

## §A.1 FR 역인덱스 (143개 전수)

> 출처. SDD `docs/sdd/02-requirements.md` §2.2 "기능 요구사항 (FR) 상세".
> 검증. `scripts/verify-master-plan.sh` — 143개 모두 product/*.md에 등장해야 함.

### 이슈 관리 (FR-IS, 10개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-IS-01 | 이슈 CRUD, 상태 변경 시 워크플로우 검증 + 알림 | 필수 | issue-tracking | §2.1.1 |
| FR-IS-02 | 이슈 타입 (Epic/Story/Task/Subtask/Bug + 커스텀) | 필수 | issue-tracking | §2.1.2 |
| FR-IS-03 | 담당자 (Reporter 1 / Assignee 1 / Watchers N) | 필수 | issue-tracking | §2.1.3 |
| FR-IS-04 | 본문(Markdown) + 우선순위/라벨/환경/영향도 | 필수 | issue-tracking | §2.1.4 |
| FR-IS-05 | 이슈 일괄 편집 + 일괄 상태 전환 | 높음 | issue-tracking | §2.2.1 |
| FR-IS-06 | 이슈 클론 (옵션) | 중간 | issue-tracking | §2.3.1 |
| FR-IS-07 | Resolution 필드 | 필수 | issue-tracking | §2.1.5 |
| FR-IS-08 | 이슈 인쇄 + PDF 출력 | 중간 | issue-tracking | §2.3.2 |
| FR-IS-09 | 라벨 자동완성 | 높음 | issue-tracking | §2.2.2 |
| FR-IS-10 | 커스텀 필드 인프라 (프로젝트별 정의 + JSONB 값 저장) | 높음 | issue-tracking | §2.4 |

### 프로젝트 관리 (FR-PJ, 4개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-PJ-01 | 프로젝트 생성 (키 검증 · 생성자 자동 PROJECT_ADMIN 멤버십) | 필수 | issue-tracking | §2.2.16 |
| FR-PJ-02 | 프로젝트 목록/조회 (권한 필터링 · 아카이브 기본 제외) | 필수 | issue-tracking | §2.2.16 |
| FR-PJ-03 | 프로젝트 설정 변경 (`name`) | 필수 | issue-tracking | §2.2.16 |
| FR-PJ-04 | 프로젝트 아카이브/해제 (읽기 전용 잠금) | 필수 | issue-tracking | §2.2.16 |

### 컴포넌트 / 버전 (FR-CM, FR-VR, 8개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-CM-01 | 프로젝트별 컴포넌트 CRUD + 컴포넌트 리드 | 필수 | issue-tracking | §3.1.1 |
| FR-CM-02 | 이슈에 다중 컴포넌트 할당 | 필수 | issue-tracking | §3.1.2 |
| FR-CM-03 | 컴포넌트별 기본 담당자 자동 할당 | 높음 | issue-tracking | §3.1.3 |
| FR-CM-04 | 컴포넌트 리드 부재 시 프로젝트 리드 폴백 (2순위) | 중간 | issue-tracking | §3.1.4 |
| FR-VR-01 | 버전 생성 + 시작일/릴리즈 예정일 | 필수 | issue-tracking | §3.2.1 |
| FR-VR-02 | 버전 상태 (Unreleased/Released/Archived) | 필수 | issue-tracking | §3.2.2 |
| FR-VR-03 | Affects/Fix Version 연결 | 필수 | issue-tracking | §3.2.3 |
| FR-VR-04 | 버전 릴리즈 노트 자동 생성 | 중간 | issue-tracking | §3.2.4 |

### 워크플로우 / 자동화 (FR-WF, FR-AT, 14개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-WF-01 | FSM 워크플로우 (상태/전환/조건/검증/후처리) | 필수 | project-workflow | §2.1 |
| FR-WF-02 | 프로젝트별 워크플로우 스킴 + 타입별 매핑 | 필수 | project-workflow | §2.2 |
| FR-WF-03 | 워크플로우 전환 validator/PostAction 런타임 결선 | 필수 | project-workflow | §2.3 |
| FR-WF-04 | 워크플로우 CRUD + 전역 상태 카탈로그 | 필수 | project-workflow | §2.4 |
| FR-WF-05 | 전환 ID 식별자 — 다중 전환 + 전역/최초 전환 | 필수 | project-workflow | §2.5 |
| FR-WF-06 | 전환 규칙(조건/검증기/후처리) 편집 | 필수 | project-workflow | §2.6 |
| FR-WF-07 | 워크플로우 초안·발행 + 상태 이관 마법사 | 필수 | project-workflow | §2.7 |
| FR-AT-01 | 트리거 (생성/변경/댓글/스케줄/Webhook) | 필수 | automation | §2.1 |
| FR-AT-02 | 액션 (필드 변경/담당자/댓글/API 호출) | 필수 | automation | §2.2 |
| FR-AT-03 | 조건 분기 (if-else, 표현식) | 필수 | automation | §2.3 |
| FR-AT-04 | 규칙 충돌 정적 분석 | 필수 | automation | §2.4 |
| FR-AT-05 | 실행 이력 + 디버깅 | 필수 | automation | §2.5 |
| FR-AT-06 | YAML 가져오기/내보내기 (GitOps) | 높음 | automation | §2.6 |
| FR-AT-07 | PR 머지 연동 (Fix Version 자동) | 높음 | automation | §2.7 |

### 알림 (FR-NT, 5개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-NT-01 | 이벤트별 알림 정책 | 필수 | notification-dashboard | §2.1 |
| FR-NT-02 | 채널 (이메일/인앱, Slack=slack-integration BC) | 필수 | notification-dashboard | §2.2 |
| FR-NT-03 | 수신자 정책 (R/A/W/Lead/역할) | 필수 | notification-dashboard | §2.3 |
| FR-NT-04 | 사용자별 알림 구독 설정 | 높음 | notification-dashboard | §2.4 |
| FR-NT-05 | Webhook 알림 채널 — 전환 post-action 이벤트 발행 + 외부 URL HTTP POST 디스패처 | 중간 | notification-dashboard | §2.5 |

### 멘션 / 히스토리 (FR-MN, FR-HS, 4개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-MN-01 | 본문/댓글 @멘션 + 즉시 알림 | 필수 | issue-tracking | §4.1.1 |
| FR-MN-02 | 멘션 자동완성 | 높음 | issue-tracking | §4.1.2 |
| FR-HS-01 | 이슈 변경 이력 | 필수 | issue-tracking | §5.1.1 |
| FR-HS-02 | 히스토리 조회 UI | 필수 | issue-tracking | §5.1.2 |

### 댓글 (FR-CO, 2개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-CO-01 | 이슈 댓글 작성 + 목록 조회 | 필수 | issue-tracking | §4.4.1 |
| FR-CO-02 | 댓글 수정 + 삭제 (소프트) | 필수 | issue-tracking | §4.4.2 |

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

### 권한 관리 (FR-PM, 10개)

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
| FR-PM-09 | 사용자 그룹 (전역 그룹 + 멤버십 인프라, FR-PM-06 선행) | 필수 | identity-access | §4.9 |
| FR-PM-10 | 전역 권한 부여 (`global_permission_grants` — 그룹/사용자 grant + 관리 화면) | 필수 | identity-access | §4.10 |

### 사용성 (FR-UX, 14개)

| FR ID | 한 줄 | 우선순위 | BC | 본문 위치 |
|---|---|---|---|---|
| FR-UX-01 | 퀵 필터 (보드 상단 즉시 필터) | 필수 | personalization | §4.1 |
| FR-UX-02 | 즐겨찾기 / Star | 필수 | notification-dashboard | §5.1 |
| FR-UX-03 | 개인 알림 보관함 (Inbox) | 필수 | notification-dashboard | §5.2 |
| FR-UX-04 | Slash 명령어 | 높음 | personalization | §4.2 |
| FR-UX-05 | 키보드 단축키 | 높음 | personalization | §4.3 |
| FR-UX-06 | UI/UX 전면 개편 (Jira Cloud 방식 — 통합 사이드바 + ADS v2 토큰) | 높음 | personalization | §4.4 |
| FR-UX-07 | 활성 프로젝트 컨텍스트 (URL `?projectKey=` 승격 + 4단 해소) | 높음 | personalization | §4.5 |
| FR-UX-08 | 프로젝트 전환 · 최근 항목 · 내 작업 (스위처 + 사이드바) | 높음 | personalization | §4.6 |
| FR-UX-09 | 이슈 생성 흐름 (생성 모달 · 진입점 3곳 · 생성 필드 3종) | 높음 | personalization | §4.7 |
| FR-UX-10 | 컨텍스트 의존 단축키 (목록 항법 + 상세 액션 — FR-UX-05 이연분 승계) | 높음 | personalization | §4.8 |
| FR-UX-11 | 인라인 편집 (이슈 상세 제목/본문 · 목록 셀) | 높음 | personalization | §4.9 |
| FR-UX-12 | 검색 진입 (커맨드 팔레트 · 상단바 전역 검색) | 높음 | personalization | §4.10 |
| FR-UX-13 | 백로그 사용성 (세로 스택 · 스프린트 다이얼로그 · 필터바) | 높음 | personalization | §4.11 |
| FR-UX-14 | 이슈 카드 밀도 (유형 아이콘 · 라벨 칩 · 추정 + 카드 필드) | 높음 | personalization | §4.12 |

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
| identity-access | 25 | AU(10) + MF(5) + PM(10) |
| issue-tracking | 37 | IS(10) + CM(4) + VR(4) + AC(2) + MN(2) + CO(2) + WT(1) + LK(2) + HS(2) + TM(2) + MV(2) + PJ(4) |
| project-workflow | 7 | WF(7) |
| agile-planning | 14 | BD(3) + BL(2) + EP(2) + TL(3) + TT(2) + PL(2) |
| automation | 7 | AT(7) |
| notification-dashboard | 14 | NT(5) + DB(3) + RP(4) + UX-02,03(2) |
| slack-integration | 6 | SL(6) |
| personalization | 21 | PR(4) + PF(3) + CA(2) + UX-01,04,05,06,07~14(12) |
| search-export-import | 12 | SR(4) + EX(2) + IM(2) + API(4) |
| **합계** | **143** | |

## §A.3 미해결 결정 (Open Questions)

> 실행 직전 확정 필요.
>
> **해소된 항목은 표에서 지우지 않고 `✅ 해소` 로 마킹한다** — 지우면 "왜 그렇게 정했나" 의 추적선이
> 끊긴다. #2 가 그 선례이고 2026-07-29 에 관례로 확정했다. (이 줄은 원래 *"ADR 발행 시 이 표에서
> 제거"* 였으나 실제 운용이 마킹이었다. 규칙을 실측에 맞춘다.)
>
> ⚠️ **해소 판정은 BC 완료와 함께 갱신해야 한다.** 2026-07-29 실측에서 8건 중 5건이
> 낡은 채 방치돼 있었다 — BC 가 완료돼 질문 자체가 무의미해졌는데도 표는 그대로였다.
> **표와 BC 진척이 서로를 보지 않았다.** BC 완료 게이트를 닫을 때 이 표를 함께 훑는다.

| # | 결정 항목 | 결정 시점 | 영향 BC | 상태 |
|---|---|---|---|---|
| 1 | pgmq 이미지 (자체 빌드 vs tembo-io vs coredb) | project-workflow §1 PoC | 전 BC (이벤트 통신) | ✅ 해소(2026-05-22) — Tembo 공식 이미지 채택. ADR `docs/adr/2026-05-22-pgmq-postgres-image.md`. dev `quay.io/tembo/pg16-pgmq:latest` · prod 동일 계열 digest 고정 |
| 2 | Gantt 라이브러리 (자체 SVG vs Recharts vs syncfusion) | agile-planning §1 PoC | agile-planning §4 (타임라인) | ✅ 해소(PR #194) — 자체 SVG/CSS 채택, ADR `docs/adr/2026-06-26-gantt-rendering-self-svg.md` |
| 3 | 9개 BC 진입 순서 (BC 의존 그래프 참조) | 첫 작업 시점 | 전체 | ✅ 해소(2026-07-29) — 9 BC 전량 D1~D7 완료로 **질문이 소멸**했다. 실제 진입 순서는 README §7 변경 이력에 기록돼 있다 |
| 4 | `scripts/verify-master-plan.sh` CI 통합 시점 | 첫 BC 작업 진입 직전 | CI | 🔴 **미해결** — 2026-07-29 실측 `.github/workflows/*` 에 `verify-master-plan` **0건**. 로컬·훅에서만 돌고 있어 CI 가 카운트 drift 를 막지 못한다 |
| 5 | 이슈 키 prefix 결정 (예. `ATL-`, 프로젝트별 prefix) | issue-tracking §2.1.1 진입 시 | issue-tracking + DATA.md | ✅ 해소 — **프로젝트 키 prefix**(`PROJ-123`)로 확정. 정본은 `DATA.md §2 이슈 키 영속성`, 재발급 금지 + 이동/삭제 시 `IssueKeyRedirect` 보존 |
| 6 | identity-access 우선순위 (FR-AU-01 → 02/03 → 04/05/06~10 순) | identity-access 진입 직전 | identity-access 전체 | ✅ 해소(2026-07-29) — identity-access 25 FR(AU 10 · MF 5 · PM 10) 전량 완료로 **질문이 소멸**했다 |
| 7 | TipTap 50블록 PoC (Wiki v0.5+ 준비) 시점 | issue-tracking §2.1.4 (Markdown 본문) 시점 또는 별도 | issue-tracking + 향후 wiki BC | 🔴 **미해결(정당)** — Atlas Wiki v0.5+ 대상이라 Phase 1 범위 밖이다. 본문 에디터를 처음 만들 때 variant 추상 (issue-body \| comment \| wiki) 함께 검증 |
| 8 | issue-tracking 내 FR 진입 순서 (코어 → 보강 → 정리) | issue-tracking 진입 직전 | issue-tracking **37 FR** | ✅ 해소(2026-07-29) — 37 FR 전량 완료로 **질문이 소멸**했다. 실제 진입 순서는 README §7 참조. (이 칸은 오래 `29 FR` 로 낡아 있었다 — 실측 37) |

## §A.4 변경 이력 (append-only)

- 2026-05-20. **재편성**. SDD 17장 Phase 0~4 분할 폐기, BC 단위 완제품 매핑으로 전환. Phase 컬럼 → BC 컬럼.
- 2026-05-20. 초안. SDD `02-requirements.md`에서 117개 FR ID 전수 추출. Open Questions 8건 등재.
- 2026-06-08. **FR-IS-10 신설**. 커스텀 필드 인프라 (issue-tracking). 합계 121→122.
- 2026-06-14. **FR-NT-05 신설**. Webhook 알림 채널 (notification-dashboard, FR-NT-02에서 분리). 합계 122→123. FR-NT-02 "Webhook" 표기 정정(이메일/인앱만).
- 2026-07-17. **FR-PJ-01~04(issue-tracking) · FR-PM-10(identity-access) 신설**(D15, PR-1 일괄 등록). 프로젝트 생성/목록·조회/설정변경/아카이브 + 전역 권한 부여(`global_permission_grants`). 합계 123→128. §A.1 상단 표기 drift 정정(122→128).
- 2026-07-28. **FR-UX-07 신설**(personalization). Jira 인터랙션 패리티 — 활성 프로젝트 컨텍스트·컨텍스트 단축키·인라인 편집·생성 모달. 합계 131→132. FR-UX-05 §4.3이 후속 FR로 명시 제외한 범위를 승계한다.
- 2026-07-29. **FR-UX-07 분할 — FR-UX-08~14 신설**(personalization, +7). 합계 132→139. FR-UX-07이 27 PR 로드맵 전체를 한 FR로 묶은 결과 `product/personalization.md §4.5`의 D1~D7이 `[~]`·`[x]`·`[ ]` 세 상태로 섞였다. **D 단계는 완주 단위**여야 한다 — "도메인 정리(D1)가 절반 완료"인 상태는 성립하지 않는다. 그래서 기능 축으로 쪼갠다. 선례 FR-UX-06(22 PR)이 단일 FR로 D1~D7을 함께 닫은 것은 디자인 스펙 1벌·ADR 1벌로 굴러가는 하나의 캠페인이었기 때문이고, FR-UX-07의 단축키·인라인 편집·생성 모달·백로그는 각자 도메인 정리와 명세가 따로 필요하다. 승계 관계는 F1→UX-07 / F12,F17→UX-08 / F2,F3,B1→UX-09 / F10,F11→UX-10 / F8,F9→UX-11 / F4,F13→UX-12 / F5,F15,F16→UX-13 / F14,B2→UX-14 (로드맵 정본 `~/.claude/plans/ui-ux-sorted-kay.md` §PR 체인의 F번호). 나머지 10 PR(F6·F7·F18~F25)은 기존 FR 결손 봉합이라 chore로 남는다 — 17 + 10 = 27 PR로 로드맵 총량은 불변이다. FR-UX-07은 D1~D7 전량 완료, FR-UX-08~14는 등록만(D1~D7 전량 미착수).
- 2026-07-29. **B1·B2 백엔드 작업을 chore → D4로 승격**(2026-07-28 Maxi 결정 #3 정정, 조용한 변경 아님). 원 결정은 "B1/B2는 기존 FR 결손 봉합이라 chore"였다. 분할 후에는 B1(이슈 생성 시 담당자·우선순위·라벨)이 FR-UX-09의 D4, B2(보드/백로그 카드 필드)가 FR-UX-14의 D4가 된다. "백엔드 없음"으로 비던 칸이 실제 내용으로 채워지는 쪽이 정확하다.
- 2026-07-29. **§A.3 Open Questions 전수 재실측 — 8건 중 5건이 낡아 있었다.** 계기는 `issue-tracking 29 FR`(#8) 드리프트 1건이었으나, 알려진 1건만 고치지 않고 표 전체를 대조하니 **BC 가 완료돼 질문 자체가 소멸했는데도 상태가 그대로인 항목이 4건 더** 나왔다(#1 pgmq — ADR `2026-05-22-pgmq-postgres-image.md` 로 이미 해소된 것을 "보류"로 방치 / #3 9 BC 진입 순서 / #6 identity-access 우선순위 / #8 issue-tracking 진입 순서). **표와 BC 진척이 서로를 보지 않았다** — 지배적 결함 양식 그대로다. 실제 미해결은 2건뿐이다 — #4 `verify-master-plan.sh` CI 미통합(`.github/workflows/*` 실측 0건, 로컬·훅에서만 동작) · #7 TipTap PoC(Wiki v0.5+ 대상이라 Phase 1 범위 밖, 정당하게 열림). #8 의 `29 FR` 은 실측 **37** 로 정정했다. 표 머리말도 실측에 맞췄다 — 규칙은 *"ADR 발행 시 표에서 제거"* 였으나 실제 운용은 `✅ 해소` 마킹이었고(#2 선례), 지우면 "왜 그렇게 정했나" 의 추적선이 끊기므로 마킹 쪽을 정본으로 확정했다. **BC 완료 게이트를 닫을 때 이 표를 함께 훑는다**를 머리말에 못박았다.
- 2026-08-18. **FR-WF-04~07 신설**(project-workflow, +4). 합계 139→143. 워크플로우가 **읽기 전용**이라는 구조적 부재를 닫는다 — 표준 4종이 YAML 에 하드코딩돼 있고 `YamlSeedService` 가 기동마다 DB 를 YAML 로 되돌리며, 워크플로우·상태·전환·검증기를 만들거나 고치는 API 가 하나도 없었다. FR-WF-02 가 구현한 것은 **기존 워크플로우를 이슈 타입에 배정하는 스킴 매핑**이지 워크플로우 자체의 편집이 아니다. Jira Cloud 패리티 기준으로 넷으로 쪼갠다 — WF-04(워크플로우 CRUD + 전역 상태 카탈로그) · WF-05(전환 ID 식별자 — 같은 상태쌍 다중 전환·전역·최초 전환) · WF-06(전환 규칙 편집) · WF-07(초안·발행 + 상태 이관 마법사). **D 단계는 완주 단위**라는 FR-UX-07 분할의 선례를 따라 기능 축으로 나눴다 — 넷은 각각 마이그레이션·API·화면이 따로 필요하고 하나의 D1~D7 로 묶으면 체크박스가 섞인다. 기존 ADR 2건을 대체한다(`2026-05-21-workflow-yaml-vs-db-storage` → DB 정본 · `2026-05-28-workflow-transition-identity-policy` → 전환 ID). 로드맵 정본 `~/.claude/plans/cozy-hatching-otter.md`(10 PR).
