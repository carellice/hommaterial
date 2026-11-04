import { createHash, randomBytes } from 'node:crypto'
import { connect as connectTcp, type Socket } from 'node:net'
import { connect as connectTls, type TLSSocket } from 'node:tls'

// A small WebSocket client, enough for the remote control of webOS. The TV has a certificate of
// its own making: it is trusted as first seen and then by the fingerprint kept with the TV, which
// the WebSocket of Node cannot do.

/** The fingerprint of a certificate, SHA-256 of its DER bytes in hex, as LgTv.kt computes it. */
export const certificatePin = (der: Buffer) => createHash('sha256').update(der).digest('hex')

export class SocketError extends Error {
  /** The TV showed a certificate other than the one it was paired with. */
  changed: boolean
  constructor(message: string, changed = false) {
    super(message)
    this.changed = changed
  }
}

export type WebSocketOptions = {
  /** The fingerprint the certificate must have; null trusts any certificate. */
  pin: string | null
  timeoutMs: number
}

export class WebSocketClient {
  socket: Socket | TLSSocket
  /** The fingerprint of the certificate the server showed; null on a plain connection. */
  seenPin: string | null = null
  open = true
  onText: (text: string) => void = () => {}
  onClose: () => void = () => {}
  private buffer = Buffer.alloc(0)
  private fragments: Buffer[] = []

  constructor(socket: Socket | TLSSocket) {
    this.socket = socket
  }

  /** Opens [url], a ws:// or wss:// address. */
  static connect(url: string, options: WebSocketOptions): Promise<WebSocketClient> {
    const target = new URL(url)
    const secure = target.protocol === 'wss:'
    const port = Number(target.port || (secure ? 443 : 80))
    return new Promise((resolve, reject) => {
      let settled = false
      const fail = (error: Error) => {
        if (settled) return
        settled = true
        socket.destroy()
        reject(error)
      }
      const socket: Socket | TLSSocket = secure
        // The certificate is not made out to the address of the TV: the pin stands for the name.
        ? connectTls({ host: target.hostname, port, rejectUnauthorized: false, servername: '' })
        : connectTcp({ host: target.hostname, port })
      const timer = setTimeout(() => fail(new SocketError('timeout')), options.timeoutMs)
      const client = new WebSocketClient(socket)
      socket.once('error', (e) => fail(new SocketError(e.message)))
      socket.once(secure ? 'secureConnect' : 'connect', () => {
        if (secure) {
          const der = (socket as TLSSocket).getPeerCertificate().raw
          if (!der) return fail(new SocketError('No certificate'))
          client.seenPin = certificatePin(der)
          if (options.pin != null && client.seenPin !== options.pin) return fail(new SocketError('The certificate of the TV changed', true))
        }
        const key = randomBytes(16).toString('base64')
        socket.write(`GET ${target.pathname || '/'}${target.search} HTTP/1.1\r\nHost: ${target.host}\r\n` +
          'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
          `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`)
        let head = Buffer.alloc(0)
        const onHandshake = (chunk: Buffer) => {
          head = Buffer.concat([head, chunk])
          const end = head.indexOf('\r\n\r\n')
          if (end < 0) return
          socket.off('data', onHandshake)
          const status = head.subarray(0, head.indexOf('\r\n')).toString()
          if (!/^HTTP\/1\.1 101/.test(status)) return fail(new SocketError(status))
          clearTimeout(timer)
          settled = true
          socket.removeAllListeners('error')
          socket.on('error', () => client.closed())
          socket.on('close', () => client.closed())
          socket.on('data', (data: Buffer) => client.receive(data))
          resolve(client)
          const rest = head.subarray(end + 4)
          if (rest.length) client.receive(rest)
        }
        socket.on('data', onHandshake)
      })
    })
  }

  send(text: string) {
    this.frame(0x1, Buffer.from(text, 'utf8'))
  }

  close() {
    if (!this.open) return
    try {
      this.frame(0x8, Buffer.from([0x03, 0xe8]))
    } catch {
      // Already gone.
    }
    this.socket.end()
    this.closed()
  }

  private closed() {
    if (!this.open) return
    this.open = false
    this.socket.destroy()
    this.onClose()
  }

  /** Writes a frame; what a client sends is always masked. */
  private frame(opcode: number, payload: Buffer) {
    if (!this.open) throw new SocketError('closed')
    const mask = randomBytes(4)
    const length = payload.length
    const header = length < 126 ? Buffer.alloc(2) : length < 65536 ? Buffer.alloc(4) : Buffer.alloc(10)
    header[0] = 0x80 | opcode
    if (length < 126) header[1] = 0x80 | length
    else if (length < 65536) {
      header[1] = 0x80 | 126
      header.writeUInt16BE(length, 2)
    } else {
      header[1] = 0x80 | 127
      header.writeBigUInt64BE(BigInt(length), 2)
    }
    const masked = Buffer.alloc(length)
    for (let i = 0; i < length; i++) masked[i] = payload[i] ^ mask[i % 4]
    this.socket.write(Buffer.concat([header, mask, masked]))
  }

  private receive(data: Buffer) {
    this.buffer = Buffer.concat([this.buffer, data])
    while (this.buffer.length >= 2) {
      const first = this.buffer[0]
      const second = this.buffer[1]
      let length = second & 0x7f
      let offset = 2
      if (length === 126) {
        if (this.buffer.length < 4) return
        length = this.buffer.readUInt16BE(2)
        offset = 4
      } else if (length === 127) {
        if (this.buffer.length < 10) return
        length = Number(this.buffer.readBigUInt64BE(2))
        offset = 10
      }
      const masked = (second & 0x80) !== 0
      const maskAt = offset
      if (masked) offset += 4
      if (this.buffer.length < offset + length) return
      let payload = this.buffer.subarray(offset, offset + length)
      if (masked) {
        const mask = this.buffer.subarray(maskAt, maskAt + 4)
        payload = Buffer.from(payload.map((b, i) => b ^ mask[i % 4]))
      }
      this.buffer = this.buffer.subarray(offset + length)
      const opcode = first & 0x0f
      const fin = (first & 0x80) !== 0
      if (opcode === 0x8) return this.closed()
      if (opcode === 0x9) {
        this.frame(0xa, payload)
        continue
      }
      if (opcode === 0x1 || opcode === 0x0) {
        this.fragments.push(Buffer.from(payload))
        if (fin) {
          const text = Buffer.concat(this.fragments).toString('utf8')
          this.fragments = []
          this.onText(text)
        }
      }
    }
  }
}
