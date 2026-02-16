package com.actl.mvp.api.framework.worker

data class WorkerCallResult<T>(
    val success: Boolean,
    val data: T? = null,
    val detail: String = ""
)

