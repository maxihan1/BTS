# 15. 마이그레이션 전략 (Jira → Atlas)

## 15.1 마이그레이션 단계

### Phase 1: 파일럿 (1~2 프로젝트)
- 활성 프로젝트 1~2개 선정
- Jira REST API로 export
- Atlas에 import + 검증
- 2주 병행 운영
- 사내 피드백 수집

### Phase 2: 단계적 확장
- 부서별 / 프로젝트별 단계 이전
- 각 그룹에 트레이닝 (1시간)
- Jira는 읽기 전용으로 유지

### Phase 3: 전사 전환
- 모든 신규 이슈 Atlas
- Jira는 아카이브 (보관)

## 15.2 데이터 매핑

| Jira | Atlas |
|---|---|
| Project | Project |
| Issue | Issue |
| Issue Type | IssueType |
| Status | WorkflowState |
| Workflow | Workflow YAML |
| Comment | Comment |
| Attachment | Attachment (MinIO 이동) |
| Worklog | Worklog |
| Component | Component |
| Version | Version |
| Sprint | Sprint |
| Board | Board |
| Filter / JQL | SavedFilter / AQL |
| User | User (LDAP 매핑) |
| Group | Group |
| Webhook | Webhook |
| Automation Rule | AutomationRule (YAML 변환) |

## 15.3 이슈 키 보존 (FR-IM)

Jira 이슈 키 `PROJ-123`을 Atlas에서도 유지:

```sql
CREATE TABLE issue_key_redirect (
    old_key VARCHAR(20) PRIMARY KEY,
    new_key VARCHAR(20) NOT NULL,
    moved_at TIMESTAMPTZ NOT NULL
);
```

이전된 이슈 URL 접근 시 자동 리다이렉트.

## 15.4 사용자 매핑

- Jira email ↔ Atlas LDAP email로 자동 매핑
- 매핑 실패 시 import UI에서 수동 매핑
- 매핑 실패한 댓글/이슈는 "이전된 사용자"로 표시

## 15.5 워크플로우 변환

Jira 워크플로우 → Atlas YAML 변환기:

- 상태/전이 자동 추출
- Validator/Post-function은 일부만 자동 (복잡한 것은 수동)
- 변환 결과 검토 UI 제공

## 15.6 JQL → AQL 변환

99% 호환되므로 대부분 자동:

- `currentUser()`, `now()` 등 함수 동일
- 필드명: 99% 동일 (`status`, `priority`, `assignee`)
- 일부 다른 함수만 변환 (`linkedIssues()` 등)

JQL 변환기 제공: `POST /api/v1/migration/jql-to-aql`

## 15.7 첨부 파일 이동

```
Jira → REST API로 파일 다운로드
↓
MinIO 업로드 (storage_key 생성)
↓
Attachment 레코드 생성 (issue_id 연결)
```

대용량 (~수백 GB)은 청크 단위 + 재시도.

## 15.8 자동화 규칙 변환

Jira Automation:
- Trigger: 직접 매핑 (issue created, updated 등)
- Condition: JQL → AQL 변환
- Action: 대부분 매핑, 일부는 수동 검토

## 15.9 검증

이전 후 자동 검증:
- 이슈 건수 일치
- 첨부 파일 수 + 총 용량 일치
- 댓글 수 일치
- Worklog 시간 합계 일치
- 샘플 이슈 100건 필드별 비교

## 15.10 롤백 계획

- 1주일간 Jira 데이터 보관 (읽기 전용)
- 문제 발생 시 Atlas 이슈 → Jira 재이전 (역방향) 도구
- 단, 1주 후 Atlas만 truth

## 15.11 다음 챕터

- Import 디테일 → [10. 검색/Export/Import](10-search-export-import.md)
- 인프라 → [16. 인프라/배포/운영](16-infrastructure.md)
