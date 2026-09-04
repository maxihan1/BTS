// 에디터 길이 카운터 — 임계에 닿을 때만 나타나 상한 초과를 저장 전에 알린다 (Jira 패리티 J4·J5)
import type { JSX } from 'react'
import { cn } from '@/lib/utils'
import { editorLabels } from '@/i18n/editor-labels'
import { shouldShowLengthCounter } from '@/lib/issue-text-constraints'

interface TextLengthCounterProps {
  /** 현재 길이. **호출부가 재는 문자열의 길이**다 — 본문은 HTML, 댓글은 평문 */
  readonly length: number
  /** 상한 */
  readonly max: number
  /** 테스트·e2e 셀렉터 */
  readonly testId?: string
}

/**
 * 길이 카운터.
 *
 * ## 왜 늘 보이지 않나
 *
 * 상한이 32,767자다. 일상적인 글쓰기는 근처에도 안 간다. 늘 띄우면 모든 에디터 아래에
 * 쓸모없는 숫자가 하나씩 붙고, 정작 필요한 순간의 신호가 배경 소음에 묻힌다.
 * 임계(90%)에 닿을 때만 나타나 **숫자가 보인다는 것 자체가 경고**가 되게 한다.
 *
 * ## 왜 재는 문자열을 스스로 정하지 않나
 *
 * 서버가 재는 문자열이 필드마다 다르다 — 본문은 정화된 HTML(`@Size` on `descriptionHtml`),
 * 댓글은 평문(`CommentApplicationService.validateBody(body)`). 이 컴포넌트가 임의로 하나를
 * 고르면 다른 쪽에서 **카운터가 거짓말을 한다**. 서식이 많은 본문은 보이는 글자가 8,000자여도
 * HTML 이 32,767자를 넘을 수 있다.
 *
 * ## 접근성
 *
 * `role="status"` 로 알린다 — 초과 상태가 되면 스크린리더가 저장이 막힌 이유를 읽는다.
 * 시각적으로는 색만 바뀌므로 색에만 기대면 안 된다(초과 시 안내 문장을 함께 렌더한다).
 */
export function TextLengthCounter({ length, max, testId }: TextLengthCounterProps): JSX.Element | null {
  if (!shouldShowLengthCounter(length, max)) return null

  const isOver = length > max

  return (
    <p
      role="status"
      data-testid={testId}
      className={cn('text-xs', isOver ? 'text-destructive' : 'text-muted-foreground')}
    >
      {editorLabels.lengthCounter(length, max)}
      {isOver ? <span className="ml-2">{editorLabels.lengthOverLimit(max)}</span> : null}
    </p>
  )
}
