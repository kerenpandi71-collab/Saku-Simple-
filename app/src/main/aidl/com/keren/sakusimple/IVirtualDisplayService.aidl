package com.keren.sakusimple;

import android.view.Surface;

interface IVirtualDisplayService {
    void destroy() = 16777114;
    int createVirtualDisplay(int width, int height, int densityDpi, in Surface surface) = 1;
    boolean injectTap(int displayId, float x, float y) = 2;
    boolean injectSwipe(int displayId, float x1, float y1, float x2, float y2, long durationMs) = 3;
    void releaseVirtualDisplay() = 4;
    String getLastError() = 5;
    boolean injectHover(int displayId, float x, float y) = 7;
    String getDisplayDiagnostics(int displayId) = 8;
}
