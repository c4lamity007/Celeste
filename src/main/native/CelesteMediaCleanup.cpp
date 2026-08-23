#include <jni.h>
#include <windows.h>

// The supplied MediaPlayerInfo bridge contains a shutdown entry point, but it
// was compiled with a C++-mangled export. JNI cannot discover that export by
// itself, so this tiny bridge publishes the standard JNI name and forwards the
// call to the already-loaded media DLL.
using Cleanup = void(__cdecl *)(JNIEnv *, jclass);

extern "C" JNIEXPORT void JNICALL
Java_dev_redstones_mediaplayerinfo_impl_win_WindowsMediaPlayerInfo_cleanup(JNIEnv *env, jclass type) {
    HMODULE media = GetModuleHandleW(L"MediaPlayerInfo.dll");
    if (media == nullptr) return;

    constexpr char cleanupName[] =
        "?Java_dev_redstones_mediaplayerinfo_impl_win_WindowsMediaPlayerInfo_cleanup@@YAXPEAUJNIEnv_@@PEAV_jclass@@@Z";
    auto cleanup = reinterpret_cast<Cleanup>(GetProcAddress(media, cleanupName));
    if (cleanup != nullptr) cleanup(env, type);
}
