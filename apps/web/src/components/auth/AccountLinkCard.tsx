// 계정 연결 단건 카드 컴포넌트 — provider 정보·타입 배지·해제 확인 다이얼로그
import type { JSX } from 'react'
import { useState } from 'react'
import { AlertDialog } from 'radix-ui'
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useDateFormat } from '@/hooks/use-date-format'
import { accountLinkLabels } from '@/i18n/account-link-labels'
import type { AccountLinkResponse } from '@/api/account-links'

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 타입 배지
// ─────────────────────────────────────────────────────────────────────────────

interface TypeBadgeProps {
  /** 공급자 유형 — null이면 렌더 안 함 */
  readonly providerType: AccountLinkResponse['providerType']
}

/**
 * 공급자 유형(LDAP/SAML/OIDC)을 나타내는 배지.
 *
 * providerType이 null이면 아무것도 렌더하지 않는다.
 * 공급자 삭제 등으로 유형 정보를 알 수 없는 경우 대응.
 */
function TypeBadge({ providerType }: TypeBadgeProps): JSX.Element | null {
  if (providerType === null) return null

  const label =
    providerType === 'LDAP'
      ? accountLinkLabels.card.typeBadge.LDAP
      : providerType === 'SAML'
        ? accountLinkLabels.card.typeBadge.SAML
        : accountLinkLabels.card.typeBadge.OIDC

  return (
    <span className="inline-flex items-center rounded-full bg-secondary px-2.5 py-0.5 text-xs font-medium text-secondary-foreground">
      {label}
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 비활성 배지
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 공급자가 비활성화(providerEnabled=false)된 경우 표시하는 배지.
 * 비활성 공급자로의 재로그인이 불가함을 시각적으로 안내한다.
 */
function InactiveBadge(): JSX.Element {
  return (
    <span className="inline-flex items-center rounded-full bg-muted px-2.5 py-0.5 text-xs font-medium text-muted-foreground">
      {accountLinkLabels.card.inactiveBadge}
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// AccountLinkCard Props
// ─────────────────────────────────────────────────────────────────────────────

/** AccountLinkCard 컴포넌트 props */
export interface AccountLinkCardProps {
  /** 렌더할 계정 연결 데이터 */
  readonly link: AccountLinkResponse
  /** 해제 요청 콜백 — 연결 id를 인자로 받는다 */
  readonly onUnlink: (id: string) => void
  /** 현재 이 카드의 해제가 진행 중인지 여부 */
  readonly isUnlinking: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// AccountLinkCard 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 계정 연결 단건을 카드 형태로 렌더하는 컴포넌트.
 *
 * - providerName null 시 unknownProvider 텍스트 fallback
 * - providerEnabled=false 시 비활성 배지 표시
 * - 해제 버튼 → AlertDialog 확인 → onUnlink(id) 호출
 * - isUnlinking=true 시 해제 버튼 disabled
 */
export function AccountLinkCard({ link, onUnlink, isUnlinking }: AccountLinkCardProps): JSX.Element {
  const [dialogOpen, setDialogOpen] = useState(false)
  const { formatDateTime } = useDateFormat()

  const providerName = link.providerName ?? accountLinkLabels.card.unknownProvider
  const lastLogin =
    link.lastLoginAt !== null
      ? formatDateTime(link.lastLoginAt)
      : accountLinkLabels.card.noLoginHistory

  function handleConfirmUnlink(): void {
    setDialogOpen(false)
    onUnlink(link.id)
  }

  return (
    <Card data-testid={`account-link-card-${link.id}`}>
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle className="flex-1 text-sm leading-snug break-all">
            {providerName}
          </CardTitle>
          <TypeBadge providerType={link.providerType} />
          {!link.providerEnabled && <InactiveBadge />}
        </div>
      </CardHeader>

      <CardContent>
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-muted-foreground">식별자</dt>
          <dd>{link.externalSubjectMasked}</dd>
          <dt className="text-muted-foreground">{accountLinkLabels.card.linkedAtLabel}</dt>
          <dd>{formatDateTime(link.linkedAt)}</dd>
          <dt className="text-muted-foreground">{accountLinkLabels.card.lastLoginLabel}</dt>
          <dd>{lastLogin}</dd>
        </dl>
      </CardContent>

      <CardFooter className={cn('justify-end')}>
        <AlertDialog.Root open={dialogOpen} onOpenChange={setDialogOpen}>
          <AlertDialog.Trigger asChild>
            <Button
              variant="destructive"
              size="sm"
              disabled={isUnlinking}
              aria-label={accountLinkLabels.unlink.unlinkButton}
            >
              {accountLinkLabels.unlink.unlinkButton}
            </Button>
          </AlertDialog.Trigger>

          <AlertDialog.Portal>
            <AlertDialog.Overlay
              className={cn(
                'fixed inset-0 z-50 bg-black/50',
                'data-[state=open]:animate-in data-[state=closed]:animate-out',
                'data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
              )}
            />
            <AlertDialog.Content
              className={cn(
                'fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2',
                'rounded-xl border border-border bg-background p-6 shadow-lg',
                'data-[state=open]:animate-in data-[state=closed]:animate-out',
                'data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
                'data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95',
              )}
            >
              <AlertDialog.Title className="text-base font-semibold text-foreground">
                {accountLinkLabels.unlink.dialogTitle}
              </AlertDialog.Title>

              <AlertDialog.Description className="mt-2 text-sm text-muted-foreground">
                {accountLinkLabels.unlink.dialogBody}
              </AlertDialog.Description>

              <div className="mt-5 flex justify-end gap-2">
                <AlertDialog.Cancel asChild>
                  <Button variant="outline" size="sm">
                    {accountLinkLabels.unlink.cancelButton}
                  </Button>
                </AlertDialog.Cancel>
                <AlertDialog.Action asChild>
                  <Button variant="destructive" size="sm" onClick={handleConfirmUnlink}>
                    {accountLinkLabels.unlink.confirmButton}
                  </Button>
                </AlertDialog.Action>
              </div>
            </AlertDialog.Content>
          </AlertDialog.Portal>
        </AlertDialog.Root>
      </CardFooter>
    </Card>
  )
}
