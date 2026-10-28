package com.keren.sakusimple;

import android.view.Surface;

interface IVirtualDisplayService {
    void destroy() = 16777114;
    int createVirtualDisplay(int width, int height, int densityDpi, in Surface surface);
    boolean injectTap(int displayId, float x, float y);
    boolean injectSwipe(int displayId, float x1, float y1, float x2, float y2, long durationMs);
    void releaseVirtualDisplay();
    String getLastError();
}