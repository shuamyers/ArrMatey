package com.dnfapps.arrmatey.seerr.usecase

import com.dnfapps.arrmatey.seerr.api.model.ApprovalStatus
import com.dnfapps.arrmatey.seerr.api.model.MediaRequest
import com.dnfapps.arrmatey.seerr.api.model.RequestStatus
import com.dnfapps.networking.ErrorType
import com.dnfapps.networking.NetworkResult

/**
 * Safe approve/decline for Seerr requests (Arrmada M3).
 *
 * - A decision is sent once. It is never resent automatically (the HTTP client retries reads only).
 * - When the outcome is unclear (no answer, a 5xx) or Seerr refuses with 409 because the request is no longer
 *   pending, the request is fetched by id and its real state decides the result:
 *   - already in the wanted state → success (the decision landed, or someone made the same one);
 *   - still pending → the original error, so the parent can simply try again;
 *   - anything else → an error naming the actual state ("Already declined in Seerr").
 */
internal suspend fun decideSeerrRequest(
    requestId: Long,
    status: ApprovalStatus,
    send: suspend () -> NetworkResult<MediaRequest>,
    fetch: suspend (Long) -> NetworkResult<MediaRequest>,
): NetworkResult<MediaRequest> {
    val sent = send()
    if (sent !is NetworkResult.Error || !sent.isUncertainOrConflict()) return sent

    return when (val current = fetch(requestId)) {
        is NetworkResult.Success -> {
            val actual = RequestStatus.fromValue(current.data.status)
            when {
                actual in status.reachedStates() -> current
                actual == RequestStatus.Pending -> sent
                else -> NetworkResult.Error(
                    code = 409,
                    message = "Already ${actual.name.lowercase()} in Seerr",
                    errorType = ErrorType.Http,
                )
            }
        }
        else -> NetworkResult.Error(
            code = sent.code,
            message = "Couldn't confirm the decision. Refresh to see the current state.",
            cause = sent.cause,
            errorType = sent.errorType,
        )
    }
}

/** No answer at all (network/timeout), a server error, or Seerr's "only pending requests" conflict. */
private fun NetworkResult.Error.isUncertainOrConflict(): Boolean = code == null || code == 409 || code in 500..599

/** Request states that mean this decision is already in effect. */
private fun ApprovalStatus.reachedStates(): Set<RequestStatus> = when (this) {
    // After approval Seerr may already have moved on: sent (failed to add) or completed.
    ApprovalStatus.Approve -> setOf(RequestStatus.Approved, RequestStatus.Failed, RequestStatus.Available)
    ApprovalStatus.Decline -> setOf(RequestStatus.Declined)
}
