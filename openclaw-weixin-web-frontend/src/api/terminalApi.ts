import type { CreateSessionRequest, CreateSessionResponse, TerminalSessionView } from '../types/terminal'

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
export async function listTerminalSessions(): Promise<TerminalSessionView[]> {
  const resp = await fetch('/api/terminal/sessions')
  if (!resp.ok) {
    throw new Error(`list sessions failed: ${resp.status}`)
  }
  return (await resp.json()) as TerminalSessionView[]
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
export async function createTerminalSession(request?: CreateSessionRequest): Promise<CreateSessionResponse> {
  const resp = await fetch('/api/terminal/sessions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request ?? {})
  })
  if (!resp.ok) {
    throw new Error(`create session failed: ${resp.status}`)
  }
  return (await resp.json()) as CreateSessionResponse
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
export async function closeTerminalSession(sessionId: string): Promise<void> {
  if (!sessionId) {
    return
  }
  const resp = await fetch(`/api/terminal/sessions/${sessionId}`, { method: 'DELETE' })
  if (!resp.ok && resp.status !== 404) {
    throw new Error(`close session failed: ${resp.status}`)
  }
}
