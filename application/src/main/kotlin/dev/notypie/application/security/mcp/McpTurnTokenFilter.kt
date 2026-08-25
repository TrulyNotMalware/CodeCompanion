package dev.notypie.application.security.mcp

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import java.net.InetAddress

private const val BEARER_PREFIX = "Bearer "

/**
 * Gate in front of the MCP endpoint: rejects before ANY protocol handling (`initialize` and
 * `tools/list` included), so no anonymous request reaches the MCP server. Registered via a
 * FilterRegistrationBean scoped to the endpoint path — only when MCP is enabled.
 */
class McpTurnTokenFilter(
    private val scopedTurnTokenCodec: ScopedTurnTokenCodec,
    private val allowRemote: Boolean,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!allowRemote && !isLoopback(remoteAddr = request.remoteAddr)) {
            return reject(response = response, reason = "loopback-only")
        }
        val header = request.getHeader("Authorization") ?: return reject(response = response, reason = "missing token")
        if (!header.startsWith(BEARER_PREFIX)) return reject(response = response, reason = "missing token")
        scopedTurnTokenCodec.verify(token = header.removePrefix(BEARER_PREFIX))
            ?: return reject(response = response, reason = "invalid token")
        filterChain.doFilter(request, response)
    }

    private fun isLoopback(remoteAddr: String): Boolean =
        runCatching { InetAddress.getByName(remoteAddr).isLoopbackAddress }.getOrDefault(false)

    private fun reject(response: HttpServletResponse, reason: String) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"error":"unauthorized","reason":"$reason"}""")
    }
}
