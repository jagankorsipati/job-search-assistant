package com.jobsearchassistant.identity.api;

/** Resolves the current actor exclusively from the validated server security context. */
public interface CurrentActorProvider {
    AuthenticatedActor currentActor();
}
