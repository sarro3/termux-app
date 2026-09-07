#define _GNU_SOURCE
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <limits.h>
#include <errno.h>

#ifndef AT_FDCWD
#define AT_FDCWD -100
#endif

static char g_from[PATH_MAX];
static char g_to[PATH_MAX];
static size_t g_from_len;
static size_t g_to_len;
static int g_enabled;

static void init_remap(void) __attribute__((constructor));
static void init_remap(void) {
    const char *from = getenv("TERMUX_PREFIX_REMAP_FROM");
    const char *to = getenv("TERMUX_PREFIX_REMAP_TO");
    if (from == NULL || to == NULL || from[0] == '\0' || to[0] == '\0')
        return;
    if (strcmp(from, to) == 0)
        return;
    g_from_len = strlen(from);
    g_to_len = strlen(to);
    if (g_from_len >= PATH_MAX || g_to_len >= PATH_MAX)
        return;
    memcpy(g_from, from, g_from_len + 1);
    memcpy(g_to, to, g_to_len + 1);
    g_enabled = 1;
}

/* True if path is a strict ancestor of g_from under /data/data (not / or /data).
 * dpkg stats/mkdirs /data/data before /data/data/com.termux; work profiles
 * cannot access /data/data. */
static int is_from_ancestor(const char *path, size_t path_len) {
    if (path_len < 6 || path_len >= g_from_len)
        return 0;
    if (strncmp(g_from, path, path_len) != 0)
        return 0;
    if (g_from[path_len] != '/')
        return 0;
    return 1;
}

static const char *remap_path(const char *path, char *buf, size_t buf_size) {
    if (!g_enabled || path == NULL || path[0] != '/')
        return path;
    size_t path_len = strlen(path);
    if (is_from_ancestor(path, path_len)) {
        if (g_to_len + 1 > buf_size)
            return path;
        memcpy(buf, g_to, g_to_len + 1);
        return buf;
    }
    if (path_len < g_from_len)
        return path;
    if (strncmp(path, g_from, g_from_len) != 0)
        return path;
    if (path[g_from_len] != '\0' && path[g_from_len] != '/')
        return path;
    size_t rest_len = path_len - g_from_len;
    if (g_to_len + rest_len + 1 > buf_size)
        return path;
    memcpy(buf, g_to, g_to_len);
    memcpy(buf + g_to_len, path + g_from_len, rest_len + 1);
    return buf;
}

int openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t) va_arg(ap, int);
        va_end(ap);
    }
    char buf[PATH_MAX];
    const char *mapped = remap_path(pathname, buf, sizeof(buf));
    if (flags & O_CREAT)
        return (int) syscall(SYS_openat, dirfd, mapped, flags, mode);
    return (int) syscall(SYS_openat, dirfd, mapped, flags);
}

int open(const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t) va_arg(ap, int);
        va_end(ap);
    }
    if (flags & O_CREAT)
        return openat(AT_FDCWD, pathname, flags, mode);
    return openat(AT_FDCWD, pathname, flags);
}

int access(const char *pathname, int mode) {
    char buf[PATH_MAX];
    return (int) syscall(SYS_faccessat, AT_FDCWD, remap_path(pathname, buf, sizeof(buf)), mode, 0);
}

int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    char buf[PATH_MAX];
    return (int) syscall(SYS_faccessat, dirfd, remap_path(pathname, buf, sizeof(buf)), mode, flags);
}

#if defined(SYS_newfstatat)
#define STATAT_NR SYS_newfstatat
#elif defined(__NR_newfstatat)
#define STATAT_NR __NR_newfstatat
#elif defined(SYS_fstatat64)
#define STATAT_NR SYS_fstatat64
#else
#define STATAT_NR SYS_fstatat
#endif

int fstatat(int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    char buf[PATH_MAX];
    return (int) syscall(STATAT_NR, dirfd, remap_path(pathname, buf, sizeof(buf)), statbuf, flags);
}

#ifdef SYS_statx
int statx(int dirfd, const char *pathname, int flags, unsigned int mask, void *statxbuf) {
    char buf[PATH_MAX];
    return (int) syscall(SYS_statx, dirfd, remap_path(pathname, buf, sizeof(buf)), flags, mask, statxbuf);
}
#endif

int stat(const char *pathname, struct stat *statbuf) {
    return fstatat(AT_FDCWD, pathname, statbuf, 0);
}

int lstat(const char *pathname, struct stat *statbuf) {
    return fstatat(AT_FDCWD, pathname, statbuf, AT_SYMLINK_NOFOLLOW);
}

int mkdir(const char *pathname, mode_t mode) {
    if (g_enabled && pathname != NULL && pathname[0] == '/') {
        size_t n = strlen(pathname);
        if (is_from_ancestor(pathname, n))
            return 0;
    }
    char buf[PATH_MAX];
    return (int) syscall(SYS_mkdirat, AT_FDCWD, remap_path(pathname, buf, sizeof(buf)), mode);
}

int mkdirat(int dirfd, const char *pathname, mode_t mode) {
    if (g_enabled && pathname != NULL && pathname[0] == '/') {
        size_t n = strlen(pathname);
        if (is_from_ancestor(pathname, n))
            return 0;
    }
    char buf[PATH_MAX];
    return (int) syscall(SYS_mkdirat, dirfd, remap_path(pathname, buf, sizeof(buf)), mode);
}

int chdir(const char *pathname) {
    char buf[PATH_MAX];
    return (int) syscall(SYS_chdir, remap_path(pathname, buf, sizeof(buf)));
}

int unlink(const char *pathname) {
    char buf[PATH_MAX];
    return (int) syscall(SYS_unlinkat, AT_FDCWD, remap_path(pathname, buf, sizeof(buf)), 0);
}

int rmdir(const char *pathname) {
    char buf[PATH_MAX];
    return (int) syscall(SYS_unlinkat, AT_FDCWD, remap_path(pathname, buf, sizeof(buf)), AT_REMOVEDIR);
}

int chmod(const char *pathname, mode_t mode) {
    char buf[PATH_MAX];
    return (int) syscall(SYS_fchmodat, AT_FDCWD, remap_path(pathname, buf, sizeof(buf)), mode);
}

int execve(const char *pathname, char *const argv[], char *const envp[]) {
    char buf[PATH_MAX];
    return (int) syscall(SYS_execve, remap_path(pathname, buf, sizeof(buf)), argv, envp);
}

FILE *fopen(const char *pathname, const char *mode) {
    int flags = O_RDONLY;
    if (mode != NULL) {
        if (strchr(mode, 'w')) flags = O_WRONLY | O_CREAT | O_TRUNC;
        else if (strchr(mode, 'a')) flags = O_WRONLY | O_CREAT | O_APPEND;
        if (strchr(mode, '+')) flags = (flags & ~O_WRONLY & ~O_RDONLY) | O_RDWR;
    }
    int fd = open(pathname, flags, 0666);
    if (fd < 0)
        return NULL;
    return fdopen(fd, mode != NULL ? mode : "r");
}

DIR *opendir(const char *pathname) {
    int fd = open(pathname, O_RDONLY | O_DIRECTORY);
    if (fd < 0)
        return NULL;
    return fdopendir(fd);
}

ssize_t readlink(const char *pathname, char *buf_out, size_t bufsiz) {
    char buf[PATH_MAX];
    return syscall(SYS_readlinkat, AT_FDCWD, remap_path(pathname, buf, sizeof(buf)), buf_out, bufsiz);
}

int symlink(const char *target, const char *linkpath) {
    char tbuf[PATH_MAX];
    char lbuf[PATH_MAX];
    return (int) syscall(SYS_symlinkat, remap_path(target, tbuf, sizeof(tbuf)), AT_FDCWD,
                         remap_path(linkpath, lbuf, sizeof(lbuf)));
}

int rename(const char *oldpath, const char *newpath) {
    char obuf[PATH_MAX];
    char nbuf[PATH_MAX];
    return (int) syscall(SYS_renameat, AT_FDCWD, remap_path(oldpath, obuf, sizeof(obuf)),
                         AT_FDCWD, remap_path(newpath, nbuf, sizeof(nbuf)));
}

char *realpath(const char *pathname, char *resolved) {
    char buf[PATH_MAX];
    const char *mapped = remap_path(pathname, buf, sizeof(buf));
    char tmp[PATH_MAX];
    if (mapped == NULL)
        return NULL;
    /* Minimal realpath: return mapped absolute path. */
    size_t n = strlen(mapped);
    if (n >= PATH_MAX) {
        errno = ENAMETOOLONG;
        return NULL;
    }
    if (resolved == NULL) {
        char *out = (char *) malloc(n + 1);
        if (out == NULL)
            return NULL;
        memcpy(out, mapped, n + 1);
        return out;
    }
    memcpy(tmp, mapped, n + 1);
    memcpy(resolved, tmp, n + 1);
    return resolved;
}
