# 18. 부록

## 18.1 용어집

| 용어 | 정의 |
|---|---|
| Atlas | 본 시스템 코드네임 |
| Issues | Atlas의 이슈 트래커 모듈 |
| Wiki | Atlas의 위키 모듈 (v0.5+) |
| AQL | Atlas Query Language (JQL 호환) |
| FSM | Finite State Machine (워크플로우 엔진) |
| TCA | Trigger-Condition-Action (자동화 모델) |
| LexoRank | 백로그 정렬을 위한 알파벳 키 알고리즘 |
| pgmq | PostgreSQL 큐 확장 |
| PAT | Personal Access Token |
| SSO | Single Sign-On |
| TOTP | Time-based One-Time Password (2FA) |
| WebAuthn | 패스키 / 하드웨어 보안 키 표준 |
| BEP | Break-Even Point (손익분기) |
| TCO | Total Cost of Ownership |
| SDD | Software Design Document |
| ADR | Architecture Decision Record |

## 18.2 약어

| 약어 | 풀어쓰기 |
|---|---|
| FR | Functional Requirement |
| NFR | Non-Functional Requirement |
| CRUD | Create-Read-Update-Delete |
| FTS | Full-Text Search |
| SLA | Service Level Agreement |
| RPO | Recovery Point Objective |
| RTO | Recovery Time Objective |
| PoC | Proof of Concept |
| MVP | Minimum Viable Product |
| GA | General Availability |

## 18.3 외부 표준 참조

- OpenAPI 3.1
- RFC 6749 (OAuth 2.0)
- RFC 7519 (JWT)
- RFC 7807 (Problem Details for HTTP APIs)
- SAML 2.0
- OIDC 1.0
- WebAuthn Level 2 (W3C)
- iCalendar (RFC 5545)
- WCAG 2.1 Level AA

## 18.4 라이브러리 라이선스 요약

핵심 라이브러리는 모두 OSS 친화 라이선스:

| 라이브러리 | 라이선스 |
|---|---|
| Spring Boot | Apache 2.0 |
| Kotlin | Apache 2.0 |
| PostgreSQL | PostgreSQL License (BSD 유사) |
| Redis | BSD 3-Clause |
| MinIO | AGPL v3 (자체 배포 시 주의) |
| Keycloak | Apache 2.0 |
| React | MIT |
| TanStack 라이브러리 | MIT |
| Tailwind CSS | MIT |
| shadcn/ui | MIT |
| TipTap | MIT (Core) + Pro 일부 유료 |
| ANTLR | BSD 3-Clause |
| jOOQ | Apache 2.0 (Open Source 버전, 상용 DB 시 Pro 필요) |

**주의**: MinIO AGPL은 자체 배포 가능하나 코드 수정 시 공개 의무. NCP Object Storage로 대체 시 회피 가능.

## 18.5 변경 이력

| 버전 | 날짜 | 작성자 | 주요 변경 |
|---|---|---|---|
| v0.1 | 2026-05-19 | Maxi | 초안. 엔터프라이즈 가정 |
| v0.2 | 2026-05-19 | Maxi | 22개 요구사항 1급 시민 매핑 |
| v0.3 | 2026-05-19 | Maxi | Lean Architecture, Jira 14개 보강 |
| v0.4 | 2026-05-19 | Maxi | 인증/2FA/Slack 본격 통합/개인화 |
| v0.4.1 | 2026-05-19 | Maxi | 프론트엔드 아키텍처 (21장) |
| v0.5.0 | 2026-05-19 | Maxi | Markdown 통합 재작성, 22장 Claude Code 환경 추가, 1인+AI 모델 |

## 18.6 결정 로그 (요약)

| 결정 | 챕터 | 결정일 |
|---|---|---|
| Kafka 제거 → pgmq | 03.6 | v0.3 |
| OpenSearch 제거 → PostgreSQL FTS | 03.5 | v0.3 |
| EKS 제거 → Docker Compose | 04.5 | v0.3 |
| AWS → Naver Cloud (기본) | 03.8 | v0.3 |
| Pluggable AuthenticationProvider | 19장 | v0.4 |
| TipTap 단일 에디터 | 03.7 | v0.4.1 |
| Markdown SDD | 22장 | v0.5.0 |
| 1인 + Claude Code 개발 모델 | 22장 | v0.5.0 |

상세 ADR은 `docs/adr/` 폴더에 (향후 추가).

## 18.7 참조 문서

- [docs/sdd/README.md](README.md) - 챕터 목차
- [CLAUDE.md](../../CLAUDE.md) - Claude Code 가이드
- [.claude/skills/](../../.claude/skills/) - 도메인 Skills
- [.claude/commands/](../../.claude/commands/) - Slash 명령

## 18.8 끝

이 문서는 살아있는 문서다. PR로 갱신한다.
