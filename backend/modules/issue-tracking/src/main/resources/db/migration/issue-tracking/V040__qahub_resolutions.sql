-- QA Hub 닫기 사유 해결 상태 넷 seed — 화면이 맞음 · 테스트 문제 · 흔들림 · 환경 문제 (FR-IS-07 보강 · QA Hub BTS-F2-02)
--
-- QA Hub 가 BTS 이슈를 이 해결 상태로 닫으면 판정으로 받는다(test_platform 명세 BTS.md 결정 9). 「중복」은 표준 duplicate 를 쓴다.
-- 운영 DB 에는 2026-11-01 같은 key 로 손으로 넣은 행이 있다 — ON CONFLICT (key) DO NOTHING 이라 그 행(id · 이름 · 순서)은
-- 그대로 두고, 새 설치에만 같은 넷이 생긴다. QA Hub 는 이름으로 짝짓는다(bts_link.resolution_map) — 이름을 바꾸지 않는다.
-- id 는 V011 과 같은 Zod v4 형식 고정값. is_standard=false — 시스템 표준이 아니라 QA Hub 연동용이다.
INSERT INTO resolutions (id, key, name, description, display_order, is_standard) VALUES
    ('00000000-0000-4000-8000-000000000006', 'screen_right', '화면이 맞음', 'QA Hub — 테스트 기대값이 틀렸고 화면이 맞음', 6, false),
    ('00000000-0000-4000-8000-000000000007', 'test_problem', '테스트 문제', 'QA Hub — 테스트 코드 문제',                 7, false),
    ('00000000-0000-4000-8000-000000000008', 'flaky',        '흔들림',      'QA Hub — 통과 · 실패를 오감',               8, false),
    ('00000000-0000-4000-8000-000000000009', 'env_problem',  '환경 문제',   'QA Hub — 대상 서버 · 망 · 데이터 탓',       9, false)
ON CONFLICT (key) DO NOTHING;
