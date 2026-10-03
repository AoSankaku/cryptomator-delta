package org.deltavault.android;

import android.os.ParcelFileDescriptor;
import org.deltavault.android.IPreviewCallback;

oneway interface IPreviewRenderer {
    void open(long token, in ParcelFileDescriptor source, String kind, IPreviewCallback callback);
    void renderPage(long token, int pageIndex);
    void close(long token);
}
