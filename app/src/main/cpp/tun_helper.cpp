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
        jstring jBinary, jint tunFd, jint socksPort,
        jstring jProxyUser, jstring jProxyPass, jstring jOwnerSocket)
{
    const char *binary = env->GetStringUTFChars(jBinary, nullptr);
    if (!binary) { LOGE("null binary string"); return -1; }

    const char *proxyUser = jProxyUser ? env->GetStringUTFChars(jProxyUser, nullptr) : nullptr;
    const char *proxyPass = jProxyPass ? env->GetStringUTFChars(jProxyPass, nullptr) : nullptr;

    // Build args before fork (JNI is not safe after fork)
    char fd_arg[64];
    snprintf(fd_arg, sizeof(fd_arg), "fd://%d", (int)tunFd);

    char proxy_arg[256];
    if (proxyUser && proxyPass && strlen(proxyUser) > 0) {
        snprintf(proxy_arg, sizeof(proxy_arg), "socks5://%s:%s@127.0.0.1:%d",
                 proxyUser, proxyPass, (int)socksPort);
    } else {
        snprintf(proxy_arg, sizeof(proxy_arg), "socks5://127.0.0.1:%d", (int)socksPort);
    }

    // Optional socket-owner check (tun2socks-owner): abstract socket served by OwnerUidServer.
    char owner_arg[128];
    owner_arg[0] = '\0';
    if (jOwnerSocket) {
        const char *ownerSocket = env->GetStringUTFChars(jOwnerSocket, nullptr);
        if (ownerSocket) {
            snprintf(owner_arg, sizeof(owner_arg), "%s", ownerSocket);
            env->ReleaseStringUTFChars(jOwnerSocket, ownerSocket);
        }
    }

    // Release JNI strings before fork
    if (proxyUser) env->ReleaseStringUTFChars(jProxyUser, proxyUser);
    if (proxyPass) env->ReleaseStringUTFChars(jProxyPass, proxyPass);

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

        // binary pointer is still valid (COW after fork, used before parent releases)
        char *argv[10];
        int argc = 0;
        argv[argc++] = const_cast<char*>(binary);
        argv[argc++] = const_cast<char*>("-device");   argv[argc++] = fd_arg;
        argv[argc++] = const_cast<char*>("-proxy");    argv[argc++] = proxy_arg;
        argv[argc++] = const_cast<char*>("-loglevel"); argv[argc++] = const_cast<char*>("error");
        if (owner_arg[0] != '\0') {
            argv[argc++] = const_cast<char*>("-owner-socket"); argv[argc++] = owner_arg;
        }
        argv[argc] = nullptr;

        execv(binary, argv);
        // execv only returns on failure
        _exit(127);
    }

    // ── Parent process ──────────────────────────────────────────────────────
    // Re-set FD_CLOEXEC in parent so future child processes don't inherit it
    if (flags != -1) {
        fcntl((int)tunFd, F_SETFD, flags | FD_CLOEXEC);
    }

    LOGI("process started: pid=%d owner-check=%s", pid, owner_arg[0] ? "on" : "off");
    env->ReleaseStringUTFChars(jBinary, binary);
    return (jint)pid;
}

/**
 * Stop a process and reap it (no zombies left behind):
 * SIGTERM → poll waitpid(WNOHANG) for up to ~1s (20 × 50ms) → SIGKILL + blocking waitpid.
 * Handles ECHILD (not our child / already reaped elsewhere) by falling back to kill(pid, 0).
 */
JNIEXPORT void JNICALL
Java_com_myappvpn_util_NativeHelper_killProcess(
        JNIEnv */*env*/, jclass /*cls*/, jint pid)
{
    if (pid <= 0) return;
    const pid_t p = (pid_t)pid;
    int status = 0;

    if (kill(p, SIGTERM) == -1 && errno == ESRCH) {
        // Fully gone already (no zombie either); nothing to reap.
        LOGI("pid=%d already exited", pid);
        return;
    }
    LOGI("Sent SIGTERM to pid=%d", pid);

    for (int i = 0; i < 20; i++) {
        pid_t r = waitpid(p, &status, WNOHANG);
        if (r == p) {
            LOGI("pid=%d exited after SIGTERM", pid);
            return;
        }
        if (r == -1) {
            if (errno == EINTR) continue;
            // ECHILD (or other): we can't reap it — just check whether it still exists.
            if (kill(p, 0) == -1) return;
        }
        usleep(50 * 1000);
    }

    LOGE("pid=%d ignored SIGTERM for ~1s — sending SIGKILL", pid);
    kill(p, SIGKILL);
    while (waitpid(p, &status, 0) == -1 && errno == EINTR) { /* retry */ }
}

/**
 * Returns true if the process is still running. Zombies count as NOT running:
 * waitpid(WNOHANG) reaps an exited child (→ false); 0 means still running (→ true).
 * If it isn't our child (ECHILD), fall back to kill(pid, 0).
 * NOTE: once this returns false for our child, the pid has been reaped and may be reused —
 * the caller must forget it.
 */
JNIEXPORT jboolean JNICALL
Java_com_myappvpn_util_NativeHelper_isProcessAlive(
        JNIEnv */*env*/, jclass /*cls*/, jint pid)
{
    if (pid <= 0) return JNI_FALSE;
    const pid_t p = (pid_t)pid;
    int status = 0;
    pid_t r;
    do {
        r = waitpid(p, &status, WNOHANG);
    } while (r == -1 && errno == EINTR);
    if (r == p) return JNI_FALSE;   // exited and reaped just now
    if (r == 0) return JNI_TRUE;    // our child, still running
    // r == -1 (ECHILD etc.): not our child — best effort existence check.
    return (kill(p, 0) == 0) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
