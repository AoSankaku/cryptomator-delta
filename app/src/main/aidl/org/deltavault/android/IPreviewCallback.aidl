package org.deltavault.android;

import android.graphics.Bitmap;

interface IPreviewCallback {
    void onLoaded(long token, int pageCount, int pageIndex, in Bitmap bitmap);
    oneway void onFailure(long token);
}
