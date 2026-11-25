package com.dnfapps.networking

import kotlin.time.Instant

class HttpRateLimitException(
    retryAt: Instant,
) : Exception("Server rate limit. Try again after $retryAt.")
