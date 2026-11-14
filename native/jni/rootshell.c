/*
 * Runs a shell command as root on the camera's Linux host (outside the Android chroot).
 *
 * The command is passed to the camera's boot process as an OSAL message. The approach and the
 * message layout follow OpenMemories-Tweak (platform/api/shell.c):
 * Copyright (c) 2016 ma1co, MIT License, https://github.com/ma1co/OpenMemories-Tweak
 *
 * libosal_uipc.so belongs to the camera's system and is loaded at run time, so nothing of it is
 * built into the app. The command runs asynchronously and returns no output.
 */
#include <jni.h>
#include <dlfcn.h>
#include <string.h>

#define OSAL_MSG_BOOTIN 0x94020A
#define BOOTIN_EXEC     0x940005
#define MAX_CMD         255

typedef int (*valloc_fn)(int type, void **addr, int len, int flag);
typedef int (*snd_fn)(int type, void *addr);

static valloc_fn p_valloc;
static snd_fn p_snd;

static int load(void)
{
    if (p_valloc && p_snd) return 0;
    void *h = dlopen("libosal_uipc.so", RTLD_NOW);
    if (!h) return -100;
    p_valloc = (valloc_fn) dlsym(h, "osal_valloc_msg_wait");
    p_snd = (snd_fn) dlsym(h, "osal_snd_msg");
    if (!p_valloc || !p_snd) { p_valloc = 0; p_snd = 0; return -101; }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_github_ronyx2_omkamera_RootShell_available(JNIEnv *env, jclass cls)
{
    return load();
}

JNIEXPORT jint JNICALL
Java_com_github_ronyx2_omkamera_RootShell_nativeExec(JNIEnv *env, jclass cls, jstring jcmd)
{
    int err = load();
    if (err) return err;
    const char *cmd = (*env)->GetStringUTFChars(env, jcmd, 0);
    if (!cmd) return -102;
    size_t n = strlen(cmd);
    if (n > MAX_CMD) { (*env)->ReleaseStringUTFChars(env, jcmd, cmd); return -103; }
    char *msg = 0;
    err = p_valloc(OSAL_MSG_BOOTIN, (void **) &msg, (int) (sizeof(int) + n + 1), 1);
    if (!err) {
        ((int *) msg)[0] = BOOTIN_EXEC;
        memcpy(msg + sizeof(int), cmd, n + 1);
        /* asynchronous message: the receiver frees it */
        err = p_snd(OSAL_MSG_BOOTIN, msg);
    }
    (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    return err;
}
