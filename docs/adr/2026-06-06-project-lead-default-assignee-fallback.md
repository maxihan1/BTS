<!-- FR-CM-04 프로젝트 리드 모델 선택 및 기본 담당자 폴백 체인 결정 -->

# 프로젝트 리드 기본 담당자 폴백 — projects.lead_user_id 모델 채택

- 상태: 채택 (Accepted)
- 날짜: 2026-06-06
- 관련 FR: FR-CM-04 (컴포넌트 리드 부재 시 프로젝트 리드 폴백)
- 선행: FR-CM-03 (컴포넌트별 기본 담당자 자동 할당, PR #84), ADR 2026-06-05-component-default-assignee-auto-assignment

## 배경

FR-CM-03은 이슈 생성·컴포넌트 변경 시 컴포넌트 리드(`components.lead_user_id`)를 기본 담당자로
자동 배정한다. 단 리드가 없으면 미할당으로 남는다. FR-CM-04는 Jira식으로 컴포넌트 리드가 없을 때
**프로젝트 리드**를 2순위 폴백으로 사용한다.

BTS에는 "프로젝트 리드"(단일 업무 책임자) 개념이 없었다. `project_memberships.role`의
`PROJECT_ADMIN`은 **다수·권한** 개념이라 자동배정 대상(단일·업무 책임)과 다르다 (admin ≠ lead).
따라서 프로젝트 단위 단일 리드를 표현할 모델을 신설해야 한다.

## 결정

**`projects` 테이블에 `lead_user_id UUID NULL` 컬럼을 추가한다 (옵션 B).**

- `projects`는 issue-tracking BC 소유 → **in-BC**. cross-BC 포트 불필요.
- `components.lead_user_id`(FR-CM-01/03)와 **완전 동형** — FK 미적용(BC 격리), ApplicationService가 존재 guard.
- 단일 리드는 컬럼 1개로 자동 보장(별도 UNIQUE 제약 불필요).

폴백 체인: **컴포넌트 리드(1순위) → 프로젝트 리드(2순위) → 미할당**.
`DefaultAssigneeResolver.resolve(current, candidates)`는 순수 함수를 유지하되,
프로젝트 리드를 미리 조회해 폴백 파라미터로 넘긴다(cross-BC 조회를 함수 밖으로). `current != null`이면
기존대로 덮어쓰지 않는다.

## 고려한 대안

### 옵션 A — project_memberships에 PROJECT_LEAD 역할 추가 (기각)

identity-access 소유 `project_memberships.role` CHECK에 `PROJECT_LEAD`를 추가하고
부분 UNIQUE 인덱스로 프로젝트당 단일 리드를 보장하는 방식. "리드는 반드시 멤버" 불변식이
같은 테이블 row로 자연 표현되는 장점이 있으나,

- issue-tracking → identity-access **진짜 cross-BC 포트 신설** 필요(IssueTypeLookupPort 역방향).
- `PROJECT_LEAD` 단일 보장용 부분 UNIQUE 인덱스 + role CHECK 마이그레이션.
- 자동배정(단일·업무)과 멤버십 역할(권한) 모델 결합으로 복잡도 증가.

컴포넌트 리드가 이미 `components.lead_user_id` 컬럼 패턴이고 멤버 검증을 하지 않으므로,
프로젝트 리드만 멤버십 모델로 다르게 가져갈 정합성 이득이 비용을 넘지 않는다.

## 결과

- `projects.lead_user_id` 컬럼 추가 마이그레이션 + `init_codegen.sql` 미러(jOOQ 상수 생성).
- 프로젝트 리드 지정/해제 API 신설(이번 PR 범위). 지정 UI는 후속 FR로 분리.
- `DefaultAssigneeResolver` 폴백 확장 + IssueApplicationService 오케스트레이션(프로젝트 리드 조회 후 주입).
- 리드 실존 검증은 ApplicationService(컴포넌트 리드와 동일 guard 패턴).

## 명세 동기화 (deviation)

product `docs/plan/product/issue-tracking.md` §3.1.4 D2/D4가 "project_memberships 단일 PROJECT_LEAD"와
"cross-BC 포트(issue-tracking → identity-access/project)"를 가정했으나, 옵션 B 채택으로 **in-BC**가 되어
cross-BC 포트가 빠진다. 같은 PR에서 product 명세를 옵션 B 기준으로 갱신한다(CLAUDE.md 전수 동기화 규칙).
