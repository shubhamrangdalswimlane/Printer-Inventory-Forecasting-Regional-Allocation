/**
 * localhost-proxy.mjs - forwards http://localhost:<port> to the app container (E2E_PROXY_TARGET, e.g. web:4173).
 *
 * Why: the OIDC login uses PKCE, which needs crypto.subtle, and browsers only offer that on secure origins
 * (HTTPS or localhost). In the container setup the app is plain http://web:4173, so the browser is pointed at
 * localhost instead and this plain TCP proxy carries the traffic. http://localhost:4173 is a registered redirect
 * URI and web origin of the dev realm's pifa-web client. Test tooling only.
 *
 * Usage: E2E_PROXY_TARGET=web:4173 node scripts/localhost-proxy.mjs   (listens on 127.0.0.1:4173 or E2E_PROXY_PORT)
 */
import net from 'node:net'

const [host, port] = (process.env.E2E_PROXY_TARGET ?? 'web:4173').split(':')
const listen = Number(process.env.E2E_PROXY_PORT ?? 4173)

net.createServer((client) => {
  const upstream = net.connect(Number(port), host)
  client.pipe(upstream)
  upstream.pipe(client)
  // A browser closing a connection mid-response is normal; never let it crash the proxy.
  client.on('error', () => upstream.destroy())
  upstream.on('error', () => client.destroy())
}).listen(listen, '127.0.0.1', () => console.log(`proxy 127.0.0.1:${listen} -> ${host}:${port}`))
