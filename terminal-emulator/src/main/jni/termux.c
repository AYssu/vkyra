#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))
#ifdef __APPLE__
# define LACKS_PTSNAME_R
#endif

static int throw_runtime_exception(JNIEnv* env, char const* message)
{
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

/* Linux paths/argv use UTF-8, while GetStringUTFChars uses JNI Modified UTF-8. */
static char* copy_utf8(JNIEnv* env, jstring value) {
    if (!value) { throw_runtime_exception(env, "Null process string"); return NULL; }
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (!string_class) return NULL;
    jmethodID get_bytes = (*env)->GetMethodID(env, string_class, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    jbyteArray bytes = get_bytes && charset ? (jbyteArray)(*env)->CallObjectMethod(env, value, get_bytes, charset) : NULL;
    if (charset) (*env)->DeleteLocalRef(env, charset);
    (*env)->DeleteLocalRef(env, string_class);
    if (!bytes || (*env)->ExceptionCheck(env)) return NULL;
    jsize length = (*env)->GetArrayLength(env, bytes);
    char* result = malloc((size_t)length + 1);
    if (!result) { (*env)->DeleteLocalRef(env, bytes); throw_runtime_exception(env, "Cannot allocate process string"); return NULL; }
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte*)result);
    (*env)->DeleteLocalRef(env, bytes);
    if ((*env)->ExceptionCheck(env)) { free(result); return NULL; }
    if (memchr(result, 0, (size_t)length)) { free(result); throw_runtime_exception(env, "NUL in process string"); return NULL; }
    result[length] = 0;
    return result;
}
static void free_strings(char** values);
static char** copy_strings(JNIEnv* env, jobjectArray values);

static int create_subprocess(JNIEnv* env,
        char const* cmd,
        char const* cwd,
        char* const argv[],
        char** envp,
        int* pProcessId,
        jint rows,
        jint columns)
{
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

#ifdef LACKS_PTSNAME_R
    char* devname;
#else
    char devname[64];
#endif
    if (grantpt(ptm) || unlockpt(ptm) ||
#ifdef LACKS_PTSNAME_R
            (devname = ptsname(ptm)) == NULL
#else
            ptsname_r(ptm, devname, sizeof(devname))
#endif
       ) {
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    // Enable UTF-8 mode and disable flow control to prevent Ctrl+S from locking up the display.
    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);

    /** Set initial winsize. */
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns };
    ioctl(ptm, TIOCSWINSZ, &sz);

    pid_t pid = fork();
    if (pid < 0) {
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        *pProcessId = (int) pid;
        return ptm;
    } else {
        // Clear signals which the Android java process may have blocked:
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, 0);

        close(ptm);
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) exit(-1);

        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

        DIR* self_dir = opendir("/proc/self/fd");
        if (self_dir != NULL) {
            int self_dir_fd = dirfd(self_dir);
            struct dirent* entry;
            while ((entry = readdir(self_dir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != self_dir_fd) close(fd);
            }
            closedir(self_dir);
        }

        clearenv();
        if (envp) for (; *envp; ++envp) putenv(*envp);

        if (chdir(cwd) != 0) {
            char* error_message;
            // No need to free asprintf()-allocated memory since doing execvp() or exit() below.
            if (asprintf(&error_message, "chdir(\"%s\")", cwd) == -1) error_message = "chdir()";
            perror(error_message);
            fflush(stderr);
        }
        execvp(cmd, argv);
        // Show terminal output about failing exec() call:
        char* error_message;
        if (asprintf(&error_message, "exec(\"%s\")", cmd) == -1) error_message = "exec()";
        perror(error_message);
        _exit(1);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processIdArray,
        jint rows,
        jint columns)
{
    if (!processIdArray || (*env)->GetArrayLength(env, processIdArray) < 1)
        return throw_runtime_exception(env, "Missing process ID output");
    char** argv = copy_strings(env, args);
    if (!argv) return -1;
    char** envp = copy_strings(env, envVars);
    if (!envp) { free_strings(argv); return -1; }
    char* cmd_cwd = copy_utf8(env, cwd);
    char* cmd_utf8 = cmd_cwd ? copy_utf8(env, cmd) : NULL;
    if (!cmd_utf8) { free(cmd_cwd); free_strings(argv); free_strings(envp); return -1; }
    int procId = 0;
    int ptm = create_subprocess(env, cmd_utf8, cmd_cwd, argv, envp, &procId, rows, columns);
    free(cmd_utf8); free(cmd_cwd); free_strings(argv); free_strings(envp);
    if (ptm < 0) return ptm;
    jint process_id = procId;
    (*env)->SetIntArrayRegion(env, processIdArray, 0, 1, &process_id);
    if ((*env)->ExceptionCheck(env)) {
        kill(-procId, SIGKILL); kill(procId, SIGKILL); close(ptm);
        while (waitpid(procId, NULL, 0) < 0 && errno == EINTR) {}
        return -1;
    }

    return ptm;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd, jint rows, jint cols)
{
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd)
{
    struct termios tios;
    tcgetattr(fd, &tios);
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &tios);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint pid)
{
    int status;
    pid_t waited;
    do { waited = waitpid(pid, &status, 0); } while (waited < 0 && errno == EINTR);
    if (waited < 0) return -1;
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    } else {
        // Should never happen - waitpid(2) says "One of the first three macros will evaluate to a non-zero (true) value".
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fileDescriptor)
{
    close(fileDescriptor);
}


static void free_strings(char** values) {
    if (!values) return;
    for (char** value = values; *value; ++value) free(*value);
    free(values);
}

static char** copy_strings(JNIEnv* env, jobjectArray values) {
    jsize size = values ? (*env)->GetArrayLength(env, values) : 0;
    char** result = calloc((size_t)size + 1, sizeof(char*));
    if (!result) { throw_runtime_exception(env, "Cannot allocate process arguments"); return NULL; }
    for (jsize i = 0; i < size; i++) {
        jstring value = (*env)->GetObjectArrayElement(env, values, i);
        if (!value) { free_strings(result); throw_runtime_exception(env, "Null process argument"); return NULL; }
        result[i] = copy_utf8(env, value);
        (*env)->DeleteLocalRef(env, value);
        if (!result[i]) { free_strings(result); return NULL; }
    }
    return result;
}

JNIEXPORT jintArray JNICALL Java_com_termux_terminal_JNI_createPipeProcess(
        JNIEnv* env, jclass TERMUX_UNUSED(clazz), jobjectArray command, jstring cwd,
        jobjectArray environment, jboolean merge_errors) {
    // Allocate the Java result before fork; OOM must never orphan a running child.
    jintArray result = (*env)->NewIntArray(env, 4);
    if (!result) return NULL;
    char** argv = copy_strings(env, command);
    if (!argv) return NULL;
    char** envp = copy_strings(env, environment);
    if (!envp) { free_strings(argv); return NULL; }
    char* cwd_utf = cwd ? copy_utf8(env, cwd) : NULL;
    if (cwd && !cwd_utf) { free_strings(argv); free_strings(envp); return NULL; }
    int pipes[4][2] = {{-1, -1}, {-1, -1}, {-1, -1}, {-1, -1}};
    pid_t pid = -1;
    if (!argv[0]) { throw_runtime_exception(env, "Empty process command"); goto cleanup; }
    for (int i = 0; i < 4; i++) {
        if (pipe2(pipes[i], O_CLOEXEC) != 0) { throw_runtime_exception(env, "Cannot create process pipe"); goto cleanup; }
    }
    pid = fork();
    if (pid < 0) { throw_runtime_exception(env, "Cannot fork pipe process"); goto cleanup; }
    if (pid == 0) {
        sigset_t unblocked;
        sigfillset(&unblocked);
        sigprocmask(SIG_UNBLOCK, &unblocked, NULL);
        if (setsid() < 0) _exit(126);
        char ready = 1;
        if (write(pipes[3][1], &ready, 1) != 1) _exit(126);
        if (dup2(pipes[0][0], STDIN_FILENO) < 0 || dup2(pipes[1][1], STDOUT_FILENO) < 0 ||
                dup2(pipes[merge_errors ? 1 : 2][1], STDERR_FILENO) < 0) _exit(126);
        // A child must not retain the JVM's descriptors (or another task's pipes).
        DIR* directory = opendir("/proc/self/fd");
        if (directory) {
            int own_fd = dirfd(directory);
            struct dirent* entry;
            while ((entry = readdir(directory)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != own_fd) close(fd);
            }
            closedir(directory);
        } else {
            for (int i = 0; i < 3; i++) { close(pipes[i][0]); close(pipes[i][1]); }
        }
        clearenv();
        for (char** value = envp; *value; ++value) putenv(*value);
        if (cwd_utf && chdir(cwd_utf) != 0) { perror("chdir"); _exit(126); }
        execvp(argv[0], argv);
        perror("execvp");
        _exit(127);
    }
    close(pipes[3][1]); pipes[3][1] = -1;
    char ready = 0;
    ssize_t ready_count;
    do { ready_count = read(pipes[3][0], &ready, 1); } while (ready_count < 0 && errno == EINTR);
    close(pipes[3][0]); pipes[3][0] = -1;
    if (ready_count != 1 || ready != 1) {
        kill(pid, SIGKILL);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
        throw_runtime_exception(env, "Cannot isolate process session");
        goto cleanup;
    }
    close(pipes[0][0]); pipes[0][0] = -1;
    close(pipes[1][1]); pipes[1][1] = -1;
    close(pipes[2][1]); pipes[2][1] = -1;
    jint values[] = {pid, pipes[0][1], pipes[1][0], pipes[2][0]};
    (*env)->SetIntArrayRegion(env, result, 0, 4, values);
    if (!result || (*env)->ExceptionCheck(env)) {
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
        goto cleanup;
    }
    pipes[0][1] = pipes[1][0] = pipes[2][0] = -1;
    free(cwd_utf);
    free_strings(argv); free_strings(envp);
    return result;
cleanup:
    for (int i = 0; i < 4; i++) for (int j = 0; j < 2; j++) if (pipes[i][j] >= 0) close(pipes[i][j]);
    free(cwd_utf);
    free_strings(argv); free_strings(envp);
    return NULL;
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_peekPipeProcess(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint pid) {
    siginfo_t info;
    memset(&info, 0, sizeof(info));
    int status;
    do { status = waitid(P_PID, (id_t)pid, &info, WEXITED | WNOHANG | WNOWAIT); } while (status < 0 && errno == EINTR);
    if (status < 0 || !info.si_pid) return (-2147483647 - 1);
    return info.si_code == CLD_EXITED ? info.si_status : -info.si_status;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_terminatePipeProcess(
        JNIEnv* env, jclass TERMUX_UNUSED(clazz), jint pid) {
    // The Java owner never reaps before closing all streams. Its zombie leader pins
    // this session ID, so a recycled PID can never authorize killing another session.
    if (pid <= 1 || getsid(pid) != pid) return;
    int denied = 0;
    // Kill the original group first, including a leader which has already exited.
    if (kill(-pid, SIGKILL) < 0 && errno != ESRCH) denied = 1;
    for (int round = 0; round < 2000; round++) {
        int alive = 0;
        DIR* directory = opendir("/proc");
        if (!directory) { throw_runtime_exception(env, "Cannot enumerate owned process session"); return; }
        struct dirent* entry;
        while ((entry = readdir(directory)) != NULL) {
            char* end;
            long child = strtol(entry->d_name, &end, 10);
            if (*end || child <= 1 || child == pid) continue;
            if (getsid((pid_t)child) == pid) {
                char path[64], stat[4096];
                snprintf(path, sizeof(path), "/proc/%ld/stat", child);
                int fd = open(path, O_RDONLY | O_CLOEXEC);
                ssize_t length = fd < 0 ? -1 : read(fd, stat, sizeof(stat) - 1);
                if (fd >= 0) close(fd);
                if (length > 0) {
                    stat[length] = '\0';
                    char* tail = strrchr(stat, ')');
                    if (tail && tail[1] == ' ' && tail[2] == 'Z') continue;
                }
                alive++;
                if (kill((pid_t)child, SIGKILL) < 0 && errno != ESRCH) denied = 1;
            }
        }
        closedir(directory);
        if (denied || alive == 0) break;
        if (round == 1999) denied = 1;
        usleep(1000);
    }
    if (denied) throw_runtime_exception(env, "Cannot terminate a privileged process in the owned session");
}
