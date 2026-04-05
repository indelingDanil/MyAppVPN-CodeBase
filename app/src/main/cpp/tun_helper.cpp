#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <dirent.h>
#include <cstring>
#include <cstdio>
#include <cerrno>
#include <android/log.h>

#define TAG "TunHelper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

/**
 * Fork + exec tun2socks with the TUN fd.
 * Using native fork() bypasses Java's ProcessBuilder which closes non-stdio fds.
 * Returns child PID on success, -1 on error.
 */
JNIEXPORT jint JNICALL
Java_com_myappvpn_util_NativeHelper_startTun2Socks(
        JNIEnv *env, jclass /*cls*/,
        jstring jBinary, jint tunFd, jint socksPort)
{
    const char *binary = env->GetStringUTFChars(jBinary, nullptr);
    if (!binary) { LOGE("null binary string"); return -1; }

    // Clear FD_CLOEXEC so the fd survives execv() in the child process
    int flags = fcntl((int)tunFd, F_GETFD, 0);
    if (flags == -1) {
        LOGE("fcntl F_GETFD failed for fd=%d: %s", (int)tunFd, strerror(errno));
    } else if (fcntl((int)tunFd, F_SETFD, flags & ~FD_CLOEXEC) == -1) {
        LOGE("fcntl F_SETFD failed for fd=%d: %s", (int)tunFd, strerror(errno));
    } else {
        LOGI("Cleared FD_CLOEXEC on fd=%d", (int)tunFd);
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork() failed: %s", strerror(errno));
        env->ReleaseStringUTFChars(jBinary, binary);
        return -1;
    }

    if (pid == 0) {
        // ── Child process ──────────────────────────────────────────────────
        // Close all inherited fds except stdin/stdout/stderr and tunFd.
        // The parent app has 100+ open fds; leaving them in tun2socks causes
        // "too many open files" when tun2socks tries to create new sockets.
        DIR *fdDir = opendir("/proc/self/fd");
        if (fdDir) {
            int dirFd = dirfd(fdDir);
            struct dirent *entry;
            while ((entry = readdir(fdDir)) != nullptr) {
                if (entry->d_name[0] == '.') continue;
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != (int)tunFd && fd != dirFd) {
                    close(fd);
                }
            }
            closedir(fdDir);
        } else {
            // Fallback: iterate up to rlimit
            struct rlimit rl;
            getrlimit(RLIMIT_NOFILE, &rl);
            int maxFd = (rl.rlim_cur != RLIM_INFINITY) ? (int)rl.rlim_cur : 1024;
            for (int i = 3; i < maxFd; i++) {
                if (i != (int)tunFd) close(i);
            }
        }

        char fd_arg[64];
        snprintf(fd_arg, sizeof(fd_arg), "fd://%d", (int)tunFd);
        char proxy_arg[80];
        snprintf(proxy_arg, sizeof(proxy_arg), "socks5://127.0.0.1:%d", (int)socksPort);

        char *const argv[] = {
            const_cast<char*>(binary),
            const_cast<char*>("-device"),   fd_arg,
            const_cast<char*>("-proxy"),    proxy_arg,
            const_cast<char*>("-loglevel"), const_cast<char*>("error"),
            nullptr
        };

        execv(binary, argv);
        // execv only returns on failure
        _exit(127);
    }

    // ── Parent process ──────────────────────────────────────────────────────
    // Re-set FD_CLOEXEC in parent so future child processes don't inherit it
    if (flags != -1) {
        fcntl((int)tunFd, F_SETFD, flags | FD_CLOEXEC);
    }

    LOGI("tun2socks started: pid=%d tunFd=%d socksPort=%d", pid, (int)tunFd, (int)socksPort);
    env->ReleaseStringUTFChars(jBinary, binary);
    return (jint)pid;
}

/**
 * Send SIGTERM to a process and reap the zombie.
 */
JNIEXPORT void JNICALL
Java_com_myappvpn_util_NativeHelper_killProcess(
        JNIEnv */*env*/, jclass /*cls*/, jint pid)
{
    if (pid > 0) {
        kill((pid_t)pid, SIGTERM);
        waitpid((pid_t)pid, nullptr, WNOHANG);
        LOGI("Sent SIGTERM to pid=%d", pid);
    }
}

/**
 * Returns true if the process is still running.
 * Uses kill(pid, 0) which succeeds if the process exists.
 */
JNIEXPORT jboolean JNICALL
Java_com_myappvpn_util_NativeHelper_isProcessAlive(
        JNIEnv */*env*/, jclass /*cls*/, jint pid)
{
    if (pid <= 0) return JNI_FALSE;
    return (kill((pid_t)pid, 0) == 0) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
