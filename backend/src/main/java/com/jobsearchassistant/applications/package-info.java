/** Application pipeline state, notes, follow-ups, and history. */
@org.springframework.modulith.ApplicationModule(
        displayName = "Applications",
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED,
        allowedDependencies = {"identity::actor", "jobs::application"})
package com.jobsearchassistant.applications;
