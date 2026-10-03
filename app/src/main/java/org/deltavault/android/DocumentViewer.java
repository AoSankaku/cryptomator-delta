package org.deltavault.android;

/** UI-owned lifetime of an isolated image or PDF preview. */
interface DocumentViewer extends AutoCloseable {
    void setForeground(boolean foreground);
    @Override void close();
}
