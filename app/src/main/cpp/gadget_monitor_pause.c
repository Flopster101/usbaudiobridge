#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static pid_t target_tid = -1;

static void detach(int signal) {
    (void)signal;
    if (target_tid > 0) ptrace(PTRACE_DETACH, target_tid, NULL, NULL);
    _exit(0);
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "resume") == 0) {
        pid_t tracer = atoi(argv[2]);
        return tracer > 0 && kill(tracer, SIGTERM) == 0 ? 0 : 1;
    }
    if (argc != 4 || strcmp(argv[1], "pause") != 0) return 2;

    (void)argv[2];
    pid_t tid = atoi(argv[3]);
    if (tid <= 0 || ptrace(PTRACE_ATTACH, tid, NULL, NULL) != 0) {
        fprintf(stderr, "attach: %s\n", strerror(errno));
        return 1;
    }
    int status;
    if (waitpid(tid, &status, __WALL) != tid || !WIFSTOPPED(status)) {
        ptrace(PTRACE_DETACH, tid, NULL, NULL);
        return 1;
    }

    target_tid = tid;
    signal(SIGTERM, detach);
    signal(SIGINT, detach);
    signal(SIGHUP, detach);
    printf("%d\n", getpid());
    fflush(stdout);
    while (1) pause();
}
