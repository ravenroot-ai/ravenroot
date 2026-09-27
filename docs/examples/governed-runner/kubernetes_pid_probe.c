/* Bounded, low-memory fork probe. No shell, paths, network, credentials or privileges. */
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    char *end = NULL;
    long limit = strtol(argv[1], &end, 10);
    if (!end || *end || limit < 4 || limit > 4096) return 2;
    pid_t *children = calloc((size_t)limit + 1, sizeof(pid_t));
    if (!children) return 3;
    int descriptors[2];
    if (pipe(descriptors)) { free(children); return 3; }
    long count = 0;
    int refused = 0;
    for (; count <= limit; count++) {
        pid_t child = fork();
        if (child < 0) { refused = errno == EAGAIN; break; }
        if (!child) {
            close(descriptors[1]);
            char byte;
            while (read(descriptors[0], &byte, 1) < 0 && errno == EINTR) {}
            _exit(0);
        }
        children[count] = child;
    }
    close(descriptors[0]); close(descriptors[1]);
    for (long i = 0; i < count; i++) {
        int status;
        if (kill(children[i], SIGKILL) && errno != ESRCH) refused = 0;
        while (waitpid(children[i], &status, 0) < 0) {
            if (errno != EINTR) { refused = 0; break; }
        }
    }
    free(children);
    if (!refused || count == 0 || count > limit) return 4;
    printf("{\"processLimit\":%ld,\"processBoundaryRejected\":true}\n", limit);
    return 0;
}
