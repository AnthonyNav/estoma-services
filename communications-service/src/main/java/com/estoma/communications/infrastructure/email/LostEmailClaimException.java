package com.estoma.communications.infrastructure.email;

/** A fenced worker transition no longer owns the claimed delivery. */
final class LostEmailClaimException extends RuntimeException {
}
