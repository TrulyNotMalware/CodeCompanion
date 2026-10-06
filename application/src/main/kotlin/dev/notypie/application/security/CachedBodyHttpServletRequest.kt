package dev.notypie.application.security

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class CachedBodyHttpServletRequest private constructor(
    request: HttpServletRequest,
    val body: ByteArray,
) : HttpServletRequestWrapper(request) {
    private val cachedParameterMap: Map<String, Array<String>> by lazy { buildParameterMap() }

    override fun getInputStream(): ServletInputStream =
        CachedBodyServletInputStream(inputStream = ByteArrayInputStream(body))

    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, requestCharset()))

    override fun getContentLength(): Int = body.size

    override fun getContentLengthLong(): Long = body.size.toLong()

    override fun getParameter(name: String): String? = cachedParameterMap[name]?.firstOrNull()

    override fun getParameterValues(name: String): Array<String>? = cachedParameterMap[name]

    override fun getParameterMap(): Map<String, Array<String>> = cachedParameterMap

    override fun getParameterNames(): java.util.Enumeration<String> =
        java.util.Collections.enumeration(cachedParameterMap.keys)

    override fun getQueryString(): String? = null

    private fun buildParameterMap(): Map<String, Array<String>> {
        val values = linkedMapOf<String, MutableList<String>>()
        if (contentType?.startsWith(prefix = FORM_URLENCODED_CONTENT_TYPE, ignoreCase = true) == true) {
            appendFormParameters(values = values, encoded = String(bytes = body, charset = requestCharset()))
        }

        return values.mapValues { (_, value) -> value.toTypedArray() }
    }

    private fun appendFormParameters(values: MutableMap<String, MutableList<String>>, encoded: String) {
        if (encoded.isBlank()) return

        encoded
            .split("&")
            .filter { it.isNotEmpty() }
            .forEach { pair ->
                val separatorIndex = pair.indexOf("=")
                val rawName =
                    if (separatorIndex < 0) {
                        pair
                    } else {
                        pair.substring(startIndex = 0, endIndex = separatorIndex)
                    }
                val rawValue = if (separatorIndex < 0) "" else pair.substring(startIndex = separatorIndex + 1)
                val name = URLDecoder.decode(rawName, requestCharset())
                val value = URLDecoder.decode(rawValue, requestCharset())

                values.getOrPut(name) { mutableListOf() }.add(value)
            }
    }

    private fun requestCharset(): Charset =
        characterEncoding
            ?.let { Charset.forName(it) }
            ?: StandardCharsets.UTF_8

    companion object {
        const val MAX_BODY_BYTES = 1024 * 1024
        private const val FORM_URLENCODED_CONTENT_TYPE = "application/x-www-form-urlencoded"

        fun cacheWithinLimit(request: HttpServletRequest): CachedBodyHttpServletRequest? {
            if (request.contentLengthLong > MAX_BODY_BYTES) return null
            val body = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
            if (body.size > MAX_BODY_BYTES) return null
            return CachedBodyHttpServletRequest(request = request, body = body)
        }
    }
}

private class CachedBodyServletInputStream(
    private val inputStream: ByteArrayInputStream,
) : ServletInputStream() {
    override fun isFinished(): Boolean = inputStream.available() == 0

    override fun isReady(): Boolean = true

    override fun setReadListener(readListener: ReadListener?) = Unit

    override fun read(): Int = inputStream.read()
}
