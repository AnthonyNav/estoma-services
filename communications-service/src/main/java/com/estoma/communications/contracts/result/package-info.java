/**
 * Result payload shapes for durable commands. Physical result routing
 * (platform.results / results.&lt;requester&gt;) is not wired until Phase 3;
 * these shapes are what the transactional command dispatcher already
 * returns internally in Phase 2.
 */
package com.estoma.communications.contracts.result;
