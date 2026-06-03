# 전역 시스템 관리자 역할 + 전역 권한 인프라

> slug: system-admin-role
> type: auth
> agent: security-engineer
> 생성: 2026-06-04

## Brief

SDD 12.6 OrgAdmin/시스템 권한(12.3 ADMIN_SYSTEM 등)의 실제 구현. 현재 BTS는 프로젝트 단위 권한
(PROJECT_ADMIN/MEMBER)만 있고 전역(시스템/조직) 관리자 역할이 데이터·JWT·판정 어디에도 부재.

**범위 (Maxi 2026-06-04 — 인프라만)**:
1. 사용자 전역 역할 저장 (users 전역역할 컬럼 or 시스템역할 테이블)
2. JWT 토큰에 전역 역할/권한 클레임 추가
3. 시스템 권한코드(ADMIN_SYSTEM 등) 전역 판정 인프라
4. 최초 시스템 관리자 부트스트랩

**범위 제외**: 회원가입(FR-AU-05 소관, 후속) · 전역 워크플로우 스킴 관리(FR-PM-04, 후속).
이 인프라가 두 작업의 공통 선행을 해소한다.

**선행 해소 대상**: FR-PM-04(전역 MANAGE_WORKFLOW 판정) · FR-AU-05 회원가입(관리자 계정 생성).
**Jira Cloud 모델**: 워크플로우 스킴 등 전역 자원은 사이트/전역 관리자가 관리.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
