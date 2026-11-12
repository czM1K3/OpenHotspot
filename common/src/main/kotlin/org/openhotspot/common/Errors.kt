package org.openhotspot.common

/** Malformed or invalid protocol data; carries the code to report to the peer. */
class ProtocolException(val code: ErrorCode, message: String = code.name) : Exception(message)

/** AEAD authentication failed, or a key-confirmation check did not match. */
class AuthenticationException(message: String = "authentication failed") : Exception(message)

class PairingException(message: String) : Exception(message)

/** The peer answered with an ERROR frame. [authenticated] is false for forgeable plaintext errors. */
class PhoneException(
    val code: ErrorCode?,
    val rawCode: Int,
    val retryAfterSeconds: Int,
    val authenticated: Boolean,
) : Exception("phone reported ${code?.name ?: "0x" + rawCode.toString(16)}" +
    (if (retryAfterSeconds > 0) " (retry in ${retryAfterSeconds}s)" else ""))
