package com.actl.mvp.startup.directadb

open class DirectAdbException : Exception {
    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
}

class DirectAdbInvalidPairingCodeException : DirectAdbException("Invalid pairing code")
class DirectAdbKeyException(cause: Throwable) : DirectAdbException(cause)
