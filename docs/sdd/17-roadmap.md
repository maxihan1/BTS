# 17. 로드맵

## 17.1 단계별 일정 (1인 + Claude Code 모델)

```
Phase 0 (PoC):           T+0 ~ T+1개월    검증
Phase 1 (MVP):           T+1 ~ T+3개월    이슈 CRUD + 워크플로우 + 인증
Phase 2 (Core):          T+3 ~ T+5개월    스프린트 + 백로그 + 알림 + 2FA + Slack 알림
Phase 3 (Pro):           T+5 ~ T+7개월    자동화 + 타임라인 + 대시보드 + 리포트 + Slack 양방향
Phase 4 (Polish):        T+7 ~ T+8.5개월  SSO 확장 + WebAuthn + 감사 + PDF + 캘린더

─ GA (Atlas Issues): T+8.5개월 ─

Phase 5 (Wiki MVP):      T+8.5 ~ T+12개월 페이지 + 기본 50종 블록 + 백링크
Phase 6 (Wiki Core):     T+12 ~ T+18개월  Database 뷰 + 검색 통합 + 임베드
Phase 7 (Wiki Pro):      T+18 ~ T+21개월  실시간 협업 (Yjs) + 권한 모델

─ GA (Atlas Wiki): T+21개월 ─
```

## 17.2 Phase 0 PoC (1개월)

자세한 항목은 [22. Claude Code 환경](22-claude-code-env.md) 22.6 참조.

22일 (3주) 분량 13개 항목:
- 워크플로우 FSM
- AQL 파서
- LexoRank
- AuthProvider 인터페이스
- pgmq 통합
- React 19 + TanStack Router
- Gantt 라이브러리 비교
- TipTap 50종 블록 가능성
- @dnd-kit 1,000개 부하
- STOMP WebSocket 안정성
- CLAUDE.md 효과성
- Skills 트리거 정확성
- Claude Code → Maxi 검토 사이클

## 17.3 Phase 1 MVP (2개월)

**목표**: 사내 동료 5명이 실제 사용 가능한 최소 기능

- 이슈 CRUD (FR-IS-01~04, FR-IS-07~09)
- 워크플로우 (FR-WF-01~02) - 표준 4종
- 댓글, 첨부 (FR-AC-01~02)
- 멘션 자동완성 (FR-MN-01~02)
- 컴포넌트, 버전 (FR-CM, FR-VR)
- 검색 (FR-SR-01~02) - 기본 AQL
- LDAP 로그인 (FR-AU-02)
- SAML SSO (FR-AU-03)
- 기본 프로필 (FR-PR-01)
- 인앱 알림 (FR-NT-02)
- 이메일 알림

## 17.4 Phase 2 Core (2개월)

**목표**: 본격 운영 가능한 기능

- 스프린트, 보드 (FR-BD)
- 백로그 LexoRank (FR-BL)
- 에픽-스토리 계층 (FR-EP)
- Worklog (FR-TT)
- 권한 스킴 (FR-PM)
- 알림 정책 (FR-NT-01, FR-NT-04)
- 즐겨찾기 (FR-UX-02)
- Inbox (FR-UX-03)
- 2FA TOTP + 백업 코드 (FR-MF-01~02)
- Slack 알림 (FR-SL-02)
- 이슈 클론, 이동 (FR-IS-06, FR-MV)
- 라벨 자동완성 (FR-IS-09)

## 17.5 Phase 3 Pro (2개월)

**목표**: 차별화 기능

- 자동화 엔진 (FR-AT) - TCA 모델
- 타임라인/로드맵 (FR-TL)
- 대시보드 + 가젯 (FR-DB)
- 리포트 차트 5종 (FR-RP)
- Slack Unfurl + Slash + Interactive (FR-SL-03~05)
- 채널 ↔ 프로젝트 매핑 (FR-SL-06)
- AQL 고급 (FR-SR-03~04)
- Export CSV/XLSX (FR-EX)
- 한글 형태소 검색

## 17.6 Phase 4 Polish (1.5개월)

**목표**: 운영 안정화

- OIDC SSO (FR-AU-04)
- 로컬 계정 (FR-AU-05)
- WebAuthn (FR-MF-03)
- 신뢰 디바이스 (FR-MF-05)
- 권한 강제 (FR-MF-04)
- 감사 로그 + 보안 알림 (FR-AU-10)
- 이슈 PDF (FR-IS-08)
- 개인 캘린더 + iCal (FR-CA)
- Import (FR-IM)
- 환경 설정 (FR-PF)
- 활동 피드, 최근 본 (FR-PR-02, 20장)

## 17.7 GA T+8.5개월

**준비**:
- 모든 Phase 1~4 기능 안정화
- 부하 테스트 (100 동접 시뮬레이션)
- 보안 감사
- 사내 데이터로 마이그레이션 리허설
- 운영 매뉴얼 + Runbook
- 사용자 가이드 + 트레이닝

**실제 이전**:
- 사내 1~2 부서 파일럿 (2주)
- 전사 확대 (1개월)
- Jira 아카이브

## 17.8 Phase 5+ Wiki (별도 SDD)

v0.5 별도 문서 예정. 핵심:

- TipTap 확장 (50종 블록)
- Database 뷰 (Table/Board/Calendar/Gallery/Timeline)
- 백링크
- Atlas Issues 임베드 (AQL Query Block)
- 검색 통합
- 실시간 협업 편집 (Yjs CRDT)

## 17.9 우선순위 결정 기준

- **필수**: GA 전 필수 기능
- **높음**: GA 전 강력 권장
- **중간**: GA 이후 가능
- **낮음**: Backlog

자세한 FR 우선순위는 [02. 요구사항](02-requirements.md) 참조.

## 17.10 위험 관리

| 위험 | 영향 | 완화 |
|---|---|---|
| Claude의 환각 | 버그 | Skills + 강화된 테스트 + Maxi 검토 |
| 1인 의존성 | 휴가/병가 시 중단 | 문서화로 인수인계 가능성 확보 |
| 컨텍스트 한계 | 큰 작업 시 누락 | 모듈 단위, Skills 분리 |
| Gantt 복잡도 | 일정 지연 | Phase 0에서 라이브러리 결정 |
| 사용자 피드백 부족 | 잘못된 방향 | 사내 동료 정기 인터뷰 |
| Slack 정책 변경 | 통합 영향 | 추상 계층 + 다른 도구 대안 |

## 17.11 다음 챕터

- 부록 → [18. 부록](18-appendix.md)
- 개발 환경 → [22. Claude Code 환경](22-claude-code-env.md)
