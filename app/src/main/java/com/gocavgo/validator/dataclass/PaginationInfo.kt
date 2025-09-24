package com.gocavgo.validator.dataclass

import kotlinx.serialization.Serializable

@Serializable
data class PaginationInfo(
    val page: Int,
    val limit: Int,
    val total: Int,
    val total_pages: Int,
    val has_next: Boolean,
    val has_prev: Boolean
)
