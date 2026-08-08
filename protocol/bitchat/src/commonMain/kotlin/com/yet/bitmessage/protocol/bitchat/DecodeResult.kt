package com.yet.bitmessage.protocol.bitchat

sealed interface DecodeResult<out T> {
    data class Success<T>(val value: T) : DecodeResult<T>

    data class Failure(val error: DecodeError) : DecodeResult<Nothing>
}

enum class DecodeError {
    EMPTY_INPUT,
    TRUNCATED,
    UNSUPPORTED_VERSION,
    INVALID_LENGTH,
    LIMIT_EXCEEDED,
    MALFORMED_FIELD,
    INVALID_PADDING,
    UNSUPPORTED_FEATURE,
    PROFILE_VIOLATION,
}

sealed interface EncodeResult {
    data class Success(val bytes: com.yet.bitmessage.foundation.Bytes) : EncodeResult

    data class Failure(val error: EncodeError) : EncodeResult
}

enum class EncodeError {
    INVALID_LENGTH,
    UNSUPPORTED_FEATURE,
    PROFILE_VIOLATION,
}
