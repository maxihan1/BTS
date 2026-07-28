-- 댓글 삭제 통지 기본 정책 시드 (FR-CO-02 모더레이션 마무리) — event_type=issue.comment_deleted

-- ## 왜 이 정책이 필요한가
-- FR-CO-02 는 댓글 삭제를 「작성자 OR SOFT_DELETE 보유자(모더레이터)」로 열었으나,
-- **내 댓글이 모더레이터에게 지워져도 아무 신호가 없었다.** 감사 이력(issue_change_group)에는
-- 남지만 그건 조회해야 보이는 기록이지 밀어주는 신호가 아니다.
-- 즉 이 시드는 새 기능이 아니라 이미 배포된 모더레이션 기능의 빠진 절반이다.
--
-- ## 수신자가 COMMENT_AUTHOR 하나뿐인 이유
-- 다른 이벤트(issue.commented 등)는 REPORTER/ASSIGNEE/WATCHER 에게도 알린다. 삭제는 다르다 —
-- 「누군가의 댓글이 지워졌다」는 이슈 참여자 전체가 알 일이 아니고, 알리면 삭제된 내용이 있었다는
-- 사실 자체가 확산된다. 모더레이션 목적에 반한다. 당사자에게만 알린다.
--
-- ## 자기 삭제는 어떻게 걸러지나
-- 이 정책은 「작성자에게 보낸다」까지만 정한다. actor == author 인 자기 삭제 제외는
-- 수신자 해석 이후의 **자기제외** 단계가 담당한다(정책 레이어의 책임이 아니다).
--
-- project_key=NULL: 전역 기본 정책. created_by=NULL: 시스템 시드.
-- ON CONFLICT DO NOTHING: 재실행 시 멱등 보장 (V401 과 동일 관례).

INSERT INTO notification_policies (event_type, recipient_role, channel, enabled, project_key, created_by)
VALUES
    ('issue.comment_deleted', 'COMMENT_AUTHOR', 'IN_APP', TRUE, NULL, NULL)
ON CONFLICT DO NOTHING;
