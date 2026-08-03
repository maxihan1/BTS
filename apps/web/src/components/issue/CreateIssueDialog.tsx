// 이슈 생성 모달 — URL 을 모르는 제어 컴포넌트 (FR-UX-09 F2)
import type { JSX } from 'react'
import { useState } from 'react'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { IssueCreateForm } from '@/components/issue/IssueCreateForm'
import { issueCreateStrings } from '@/i18n/ko'

/** 폼 요소 id — 스크롤 영역 밖 푸터 버튼이 `form` 속성으로 제출을 건다 (NFR-2). */
const CREATE_ISSUE_FORM_ID = 'create-issue-form'

/** CreateIssueDialog props */
export interface CreateIssueDialogProps {
  /** 모달 열림 여부 (controlled) */
  open: boolean
  /** 열림 상태 변경 콜백 — 취소·Esc·바깥 클릭·생성 성공에서 발화 */
  onOpenChange: (open: boolean) => void
  /**
   * 생성 성공 콜백 — 생성된 이슈 키를 전달한다.
   *
   * ★**이동은 이 컴포넌트가 정하지 않는다** (FR-16, design 리뷰 D8).
   * 딥링크 라우트는 상세로 navigate 하고, 상단바는 제자리에 머물며 토스트를 띄운다.
   * 진입 경로가 다르면 끝난 뒤 기대도 다르기 때문이다.
   */
  onCreated?: (key: string) => void
  /** 접근 가능한 프로젝트가 0개일 때 빈 상태의 「프로젝트 만들기」 콜백 (FR-17) */
  onCreateProject?: () => void
  /** 제목 프리필 — 명령 팔레트 `/issue <제목>`(FR-UX-04 FR7) */
  initialSummary?: string
  /**
   * 프로젝트 프리필 — 진입점이 자기 프로젝트를 명시로 넘긴다 (FR-UX-09 F3 FR-7).
   *
   * 보드·백로그 진입점은 `/projects/$projectKey/*` 안에 있어 프로젝트를 직접 안다.
   * 전역 활성 프로젝트를 경유하면 목록 대조 가드가 아직 통과하지 못한 순간에
   * **직전 프로젝트가 채워진 채로 열린다** (스펙 §8 D-A).
   *
   * ⚠️ 이 prop 은 라우터 훅이 **아니다** — 값을 받을 뿐 URL 을 읽지 않으므로
   * 「라우터 훅 import 0」 설계 조건(FR-1)을 깨지 않는다.
   */
  initialProjectKey?: string
}

/**
 * 이슈 생성 모달.
 *
 * ### 설계 조건 — URL 을 모른다 (FR-1)
 * 이 컴포넌트는 라우터 훅을 **하나도 쓰지 않는다**. 후속 F3 의 진입점 3곳
 * (보드 컬럼 · 백로그 · 스프린트)이 **URL 변경 없이** 제자리에서 열어야 하기 때문이다.
 * 여기서 `useNavigate` 를 쓰면 그 재사용이 막힌다.
 *
 * ### 접근성 (NFR-1)
 * `role="dialog"` 가 e2e 에 164발생이라 이름 없는 다이얼로그는 strict mode 로 충돌한다.
 * [DialogTitle] 이 `aria-labelledby` 로 연결돼 **고유한 접근 가능한 이름**을 만든다.
 *
 * ### 스크롤 경계 (NFR-2/NFR-3)
 * 필드가 10종+N 이라 세로가 길다. 본문만 스크롤하고 푸터는 밖에 둬 **만들기 버튼이 항상 보인다**.
 * 폭·높이는 긴 폼 모달 선례 `ReleaseNotesDialog` 의 `max-w-2xl max-h-[80vh] flex flex-col` 을 따른다.
 *
 * ### 폼 초기화 (E3)
 * Radix `DialogContent` 는 닫히면 언마운트된다 — 다시 열면 폼이 새로 마운트돼 값이 남지 않는다.
 */
export function CreateIssueDialog({
  open,
  onOpenChange,
  onCreated,
  onCreateProject,
  initialSummary,
  initialProjectKey,
}: CreateIssueDialogProps): JSX.Element {
  /**
   * 제출 진행 상태 — 푸터 버튼이 폼 **밖**이라 폼에서 받아와야 한다 (게이트 2 C-2).
   * `setSubmitting` 은 참조가 안정적이라 폼의 알림 effect 가 헛돌지 않는다.
   */
  const [submitting, setSubmitting] = useState(false)

  function handleSuccess(key: string): void {
    onOpenChange(false)
    onCreated?.(key)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="max-w-2xl max-h-[80vh] flex flex-col"
        aria-describedby={undefined}
      >
        <DialogHeader>
          <DialogTitle>{issueCreateStrings.dialogTitle}</DialogTitle>
        </DialogHeader>

        {/* 본문만 스크롤한다 — 푸터는 이 영역 밖이라 버튼이 늘 보인다 */}
        <div data-testid="create-issue-scroll" className="flex-1 overflow-y-auto pr-1">
          <IssueCreateForm
            formId={CREATE_ISSUE_FORM_ID}
            initialSummary={initialSummary}
            initialProjectKey={initialProjectKey}
            onSuccess={handleSuccess}
            onCreateProject={onCreateProject}
            onPendingChange={setSubmitting}
          />
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            {issueCreateStrings.cancelButton}
          </Button>
          {/* 폼 밖에서 제출한다 — `form` 속성이 id 로 폼을 가리킨다 (NFR-2) */}
          <Button type="submit" form={CREATE_ISSUE_FORM_ID} disabled={submitting}>
            {submitting ? issueCreateStrings.submitButtonPending : issueCreateStrings.submitButton}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
