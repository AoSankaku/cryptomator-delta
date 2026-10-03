package org.deltavault.android;

/** Owned by the Activity; backgrounding pauses and every lock releases it. */
interface Playback extends AutoCloseable {
    void pause();
    @Override void close();
}
