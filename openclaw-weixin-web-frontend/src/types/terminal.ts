/**
 * @since 2026-04-21
 * @author LangChat Team
 */
export interface TerminalSessionView {
  sessionId: string
  commandLine: string
  workspace: string
  createdAt: string
  lastActiveAt: number
  alive: boolean
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
export interface CreateSessionResponse {
  sessionId: string
  wsPath: string
  commandLine: string
  workspace: string
  createdAt: string
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
export interface CreateSessionRequest {
  mode?: 'chat' | 'login'
  accountId?: string
  forceNew?: boolean
}

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
export interface TerminalUiEvent {
  id: string
  level: 'info' | 'success' | 'warn' | 'error'
  message: string
  at: string
}
