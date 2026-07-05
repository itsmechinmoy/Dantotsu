package ani.dantotsu.shizuku;

import android.content.res.AssetFileDescriptor;

interface IShellInterface {
    void install(in AssetFileDescriptor apk) = 1;
    void destroy() = 2;
}
