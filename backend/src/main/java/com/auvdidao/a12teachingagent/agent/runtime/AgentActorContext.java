package com.auvdidao.a12teachingagent.agent.runtime;

/** Trusted server-side actor identity passed into runtime lifecycle operations. */
public record AgentActorContext(String actorId, String actorRole) {
    public AgentActorContext {
        if (actorId == null || actorId.isBlank() || actorId.length() > 128) {
            throw new IllegalArgumentException("actorId must be nonblank and at most 128 characters");
        }
        if (actorRole == null || actorRole.isBlank() || actorRole.length() > 64) {
            throw new IllegalArgumentException("actorRole must be nonblank and at most 64 characters");
        }
    }
}
