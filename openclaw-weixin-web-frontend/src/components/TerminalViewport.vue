<template>
  <div class="h-full w-full" ref="hostRef"></div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Terminal } from 'xterm'
import { FitAddon } from '@xterm/addon-fit'

const props = defineProps<{
  wsPath: string | null
}>()

const emit = defineEmits<{
  (e: 'connected', value: boolean): void
  (e: 'output', text: string): void
  (e: 'error', message: string): void
}>()

const hostRef = ref<HTMLElement | null>(null)
const TERMINAL_FONT_STACK = [
  '"JetBrains Mono"',
  '"BlexMono Nerd Font"',
  '"Cascadia Mono"',
  '"SFMono-Regular"',
  '"Menlo"',
  '"Consolas"',
  '"PingFang SC"',
  '"Microsoft YaHei UI"',
  'monospace'
].join(', ')

const terminal = new Terminal({
  cursorBlink: true,
  cursorStyle: 'bar',
  fontFamily: TERMINAL_FONT_STACK,
  fontSize: 14,
  lineHeight: 1.26,
  letterSpacing: 0.15,
  fontWeight: '400',
  convertEol: false,
  allowProposedApi: false,
  theme: {
    background: '#171717',
    foreground: '#d9dee7',
    cursor: '#94a3b8',
    selectionBackground: '#334155',
    black: '#171717',
    red: '#d16d6d',
    green: '#72c399',
    yellow: '#7fa8d9',
    blue: '#6aa9ff',
    magenta: '#b18ad9',
    cyan: '#58c0c8',
    white: '#d9dee7',
    brightBlack: '#5d6675',
    brightRed: '#df8a8a',
    brightGreen: '#8fd7b5',
    brightYellow: '#9cbce6',
    brightBlue: '#8cbfff',
    brightMagenta: '#c6ace6',
    brightCyan: '#7bd1d7',
    brightWhite: '#edf2f8'
  }
})
const fitAddon = new FitAddon()
terminal.loadAddon(fitAddon)

let ws: WebSocket | null = null
let resizeObserver: ResizeObserver | null = null
let onDataDisposable: { dispose: () => void } | null = null

function wrapLine(text: string, max: number): string[] {
  if (max <= 0) {
    return [text]
  }
  const words = text.split(' ')
  const lines: string[] = []
  let current = ''

  for (const word of words) {
    if (word.length > max) {
      if (current) {
        lines.push(current)
        current = ''
      }
      let start = 0
      while (start < word.length) {
        lines.push(word.slice(start, start + max))
        start += max
      }
      continue
    }
    const candidate = current ? `${current} ${word}` : word
    if (candidate.length <= max) {
      current = candidate
      continue
    }
    if (current) {
      lines.push(current)
      current = word
      continue
    }
    lines.push(word)
  }

  if (current) {
    lines.push(current)
  }
  return lines.length ? lines : ['']
}

function padRight(text: string, width: number): string {
  if (text.length >= width) {
    return text.slice(0, width)
  }
  return text + ' '.repeat(width - text.length)
}

function writeIntroCard() {
  const cols = Math.max(terminal.cols, 56)
  const cardWidth = Math.max(52, Math.min(cols - 10, 86))
  const innerWidth = Math.max(18, cardWidth - 4)
  const border = '\u001b[38;5;110m'
  const reset = '\u001b[0m'
  const title = '\u001b[1;36m'
  const body = '\u001b[37m'
  const chip = '\u001b[38;5;253m'
  const muted = '\u001b[90m'
  const pad = '    '

  const blockTop = `${pad}${border}╭${'─'.repeat(innerWidth + 2)}╮${reset}`
  const blockBottom = `${pad}${border}╰${'─'.repeat(innerWidth + 2)}╯${reset}`
  const line = (text = '', style = body) =>
    `${pad}${border}│${reset} ${style}${padRight(text, innerWidth)}${reset} ${border}│${reset}`

  const titleLines = wrapLine('LangChat OpenClaw Weixin Web Terminal', innerWidth - 2)
  const descLines = wrapLine('Create or attach a session from the left sidebar.', innerWidth)
  const tips = [
    '[ New Session ]  Start a new terminal session',
    '[ Attach ]       Pick a session from the left list',
    '[ Enter ]        Send input to current session'
  ]

  terminal.writeln('')
  terminal.writeln('')

  terminal.writeln(blockTop)
  terminal.writeln(line())
  titleLines.forEach((item) => terminal.writeln(line(`◆ ${item}`, title)))
  terminal.writeln(line())
  descLines.forEach((item) => terminal.writeln(line(item, body)))
  terminal.writeln(line())
  terminal.writeln(blockBottom)

  terminal.writeln('')
  terminal.writeln(`${pad}${muted}--- Quick Start --------------------------------------------------------${reset}`)
  terminal.writeln('')

  terminal.writeln(blockTop)
  tips.forEach((item) => terminal.writeln(line(item, chip)))
  terminal.writeln(blockBottom)

  terminal.writeln('')
}

function normalizeWsUrl(path: string): string {
  if (path.startsWith('ws://') || path.startsWith('wss://')) {
    return path
  }
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  if (path.startsWith('/')) {
    return `${proto}://${location.host}${path}`
  }
  return `${proto}://${location.host}/${path}`
}

function send(payload: Record<string, unknown>) {
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    return
  }
  ws.send(JSON.stringify(payload))
}

function sendInput(data: string): boolean {
  if (!data) {
    return false
  }
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    emit('error', 'terminal is not connected')
    return false
  }
  send({ type: 'input', data })
  return true
}

function sendResize() {
  send({ type: 'resize', cols: terminal.cols, rows: terminal.rows })
}

function disconnectSocket() {
  if (onDataDisposable) {
    onDataDisposable.dispose()
    onDataDisposable = null
  }

  if (ws) {
    ws.onopen = null
    ws.onerror = null
    ws.onclose = null
    ws.onmessage = null
    ws.close()
    ws = null
  }

  emit('connected', false)
}

function connectSocket(path: string) {
  disconnectSocket()

  const url = normalizeWsUrl(path)
  ws = new WebSocket(url)

  ws.onopen = () => {
    emit('connected', true)
    fitAddon.fit()
    sendResize()

    onDataDisposable = terminal.onData((data) => {
      send({ type: 'input', data })
    })
  }

  ws.onmessage = (event) => {
    const text = String(event.data ?? '')
    if (!text) {
      return
    }
    terminal.write(text)
    emit('output', text)
  }

  ws.onerror = () => {
    emit('connected', false)
    emit('error', 'websocket transport error')
  }

  ws.onclose = () => {
    emit('connected', false)
  }
}

onMounted(() => {
  if (!hostRef.value) {
    return
  }

  terminal.open(hostRef.value)
  fitAddon.fit()
  if (!props.wsPath) {
    terminal.reset()
    writeIntroCard()
  }

  resizeObserver = new ResizeObserver(() => {
    fitAddon.fit()
    sendResize()
  })
  resizeObserver.observe(hostRef.value)

  if (props.wsPath) {
    connectSocket(props.wsPath)
  }
})

watch(
  () => props.wsPath,
  (nextPath) => {
    if (!nextPath) {
      disconnectSocket()
      terminal.reset()
      terminal.writeln('\u001b[90m[session detached]\u001b[0m')
      writeIntroCard()
      return
    }
    connectSocket(nextPath)
  }
)

onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
  }
  disconnectSocket()
  terminal.dispose()
})

defineExpose<{
  sendInput: (data: string) => boolean
  focus: () => void
  clear: () => void
}>({
  sendInput,
  focus: () => terminal.focus(),
  clear: () => terminal.clear()
})
</script>
