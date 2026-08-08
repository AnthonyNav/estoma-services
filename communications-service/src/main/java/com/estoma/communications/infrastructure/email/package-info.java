/**
 * Email provider port implementation(s) and the asynchronous delivery
 * worker that calls the provider outside any open database transaction
 * (system_contract.md "Email Delivery Operational Policy"). No equivalent
 * package exists in authentication-service.
 */
package com.estoma.communications.infrastructure.email;
