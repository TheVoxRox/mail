package org.voxrox.mailbackend.feature.mail.service;

/**
 * What started an account sync pass. The distinction exists for one reason: a
 * user who pressed Synchronise is waiting for an answer and gets one on every
 * outcome — including "nothing new" — while the five-minute scheduled pass
 * stays silent. Announcing every scheduled pass would put a screen-reader
 * interruption on a timer.
 */
public enum SyncTrigger {

    /** The user pressed Synchronise. Completion is reported to the client. */
    MANUAL,

    /** The periodic scheduler. Completion is silent. */
    SCHEDULED
}
