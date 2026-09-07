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

/* Strict ancestor of g_from, excluding "/" and "/data" (those are public). */
static int is_from_ancestor(const char *path, size_t path_len) {
    if (path_len < 6 || path_len >= g_from_len)
        return 0;
    if (strncmp(g_from, path, path_len) != 0)
        return 0;
    if (g_from[path_len] != '/')
        return 0;
    return 1;
}

static const char *remap_abs(const char *path, char *buf, size_t buf_size) {
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

static int fd_dir_path(int dirfd, char *out, size_t outsz) {
    if (dirfd == AT_FDCWD) {
        long n = syscall(SYS_getcwd, out, outsz);
        return n > 0 ? 0 : -1;
    }
    char proc[64];
    int pn = snprintf(proc, sizeof(proc), "/proc/self/fd/%d", dirfd);
    if (pn < 0 || pn >= (int) sizeof(proc))
        return -1;
    ssize_t n = syscall(SYS_readlinkat, AT_FDCWD, proc, out, outsz - 1);
    if (n < 0)
        return -1;
    out[n] = '\0';
    return 0;
}

static int join_abs(const char *dir, const char *rel, char *out, size_t outsz) {
    size_t dl = strlen(dir);
    size_t rl = strlen(rel);
    int need_slash = (dl > 0 && dir[dl - 1] != '/');
    if (dl + (size_t) need_slash + rl + 1 > outsz)
        return -1;
    memcpy(out, dir, dl);
    size_t i = dl;
    if (need_slash)
        out[i++] = '/';
    memcpy(out + i, rel, rl + 1);
    return 0;
}

/* Absolute or dirfd-relative path, remapped. Result always lives in buf when non-NULL. */
static const char *resolve_and_remap(int dirfd, const char *pathname, char *buf, size_t bufsz) {
    if (pathname == NULL)
        return NULL;
    const char *abs = pathname;
    char full[PATH_MAX];
    if (g_enabled && pathname[0] != '/') {
        char dir[PATH_MAX];
        if (fd_dir_path(dirfd, dir, sizeof(dir)) == 0 &&
            join_abs(dir, pathname, full, sizeof(full)) == 0)
            abs = full;
    }
    const char *mapped = remap_abs(abs, buf, bufsz);
    if (mapped == buf)
        return buf;
    size_t n = strlen(mapped);
    if (n >= bufsz)
        return pathname;
    memcpy(buf, mapped, n + 1);
    return buf;
}

static int dirfd_for_mapped(int dirfd, const char *mapped) {
    if (mapped != NULL && mapped[0] == '/')
        return AT_FDCWD;
    return dirfd;
}

static int mapped_is_ancestor(const char *mapped) {
    if (!g_enabled || mapped == NULL || mapped[0] != '/')
        return 0;
    return is_from_ancestor(mapped, strlen(mapped));
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
    const char *mapped = resolve_and_remap(dirfd, pathname, buf, sizeof(buf));
    int d = dirfd_for_mapped(dirfd, mapped);
    if (flags & O_CREAT)
        return (int) syscall(SYS_openat, d, mapped, flags, mode);
    return (int) syscall(SYS_openat, d, mapped, flags);
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
    const char *mapped = resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf));
    return (int) syscall(SYS_faccessat, AT_FDCWD, mapped, mode, 0);
}

int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    char buf[PATH_MAX];
    const char *mapped = resolve_and_remap(dirfd, pathname, buf, sizeof(buf));
    return (int) syscall(SYS_faccessat, dirfd_for_mapped(dirfd, mapped), mapped, mode, flags);
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
    const char *mapped = resolve_and_remap(dirfd, pathname, buf, sizeof(buf));
    return (int) syscall(STATAT_NR, dirfd_for_mapped(dirfd, mapped), mapped, statbuf, flags);
}

#ifdef SYS_statx
int statx(int dirfd, const char *pathname, int flags, unsigned int mask, void *statxbuf) {
    char buf[PATH_MAX];
    const char *mapped = resolve_and_remap(dirfd, pathname, buf, sizeof(buf));
    return (int) syscall(SYS_statx, dirfd_for_mapped(dirfd, mapped), mapped, flags, mask, statxbuf);
}
#endif

int stat(const char *pathname, struct stat *statbuf) {
    return fstatat(AT_FDCWD, pathname, statbuf, 0);
}

int lstat(const char *pathname, struct stat *statbuf) {
    return fstatat(AT_FDCWD, pathname, statbuf, AT_SYMLINK_NOFOLLOW);
}

int mkdir(const char *pathname, mode_t mode) {
    return mkdirat(AT_FDCWD, pathname, mode);
}

int mkdirat(int dirfd, const char *pathname, mode_t mode) {
    char buf[PATH_MAX];
    const char *mapped = resolve_and_remap(dirfd, pathname, buf, sizeof(buf));
    if (mapped_is_ancestor(mapped))
        return 0;
    /* After remap, ancestor /data/data becomes g_to; mkdir of existing prefix is fine. */
    int r = (int) syscall(SYS_mkdirat, dirfd_for_mapped(dirfd, mapped), mapped, mode);
    if (r != 0 && errno == EEXIST)
        return 0;
    return r;
}

int chdir(const char *pathname) {
    char buf[PATH_MAX];
    return (int) syscall(SYS_chdir, resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf)));
}

int unlink(const char *pathname) {
    char buf[PATH_MAX];
    const char *mapped = resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf));
    return (int) syscall(SYS_unlinkat, AT_FDCWD, mapped, 0);
}

int unlinkat(int dirfd, const char *pathname, int flags) {
    char buf[PATH_MAX];
    const char *mapped = resolve_and_remap(dirfd, pathname, buf, sizeof(buf));
    return (int) syscall(SYS_unlinkat, dirfd_for_mapped(dirfd, mapped), mapped, flags);
}

int rmdir(const char *pathname) {
    char buf[PATH_MAX];
    const char *mapped = resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf));
    return (int) syscall(SYS_unlinkat, AT_FDCWD, mapped, AT_REMOVEDIR);
}

int chmod(const char *pathname, mode_t mode) {
    char buf[PATH_MAX];
    const char *mapped = resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf));
    return (int) syscall(SYS_fchmodat, AT_FDCWD, mapped, mode);
}

int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags) {
    char buf[PATH_MAX];
    const char *mapped = resolve_and_remap(dirfd, pathname, buf, sizeof(buf));
    return (int) syscall(SYS_fchmodat, dirfd_for_mapped(dirfd, mapped), mapped, mode, flags);
}

int execve(const char *pathname, char *const argv[], char *const envp[]) {
    char buf[PATH_MAX];
    return (int) syscall(SYS_execve, resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf)), argv, envp);
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
    const char *mapped = resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf));
    return syscall(SYS_readlinkat, AT_FDCWD, mapped, buf_out, bufsiz);
}

int symlink(const char *target, const char *linkpath) {
    char tbuf[PATH_MAX];
    char lbuf[PATH_MAX];
    const char *tm = resolve_and_remap(AT_FDCWD, target, tbuf, sizeof(tbuf));
    const char *lm = resolve_and_remap(AT_FDCWD, linkpath, lbuf, sizeof(lbuf));
    return (int) syscall(SYS_symlinkat, tm, AT_FDCWD, lm);
}

int rename(const char *oldpath, const char *newpath) {
    char obuf[PATH_MAX];
    char nbuf[PATH_MAX];
    const char *om = resolve_and_remap(AT_FDCWD, oldpath, obuf, sizeof(obuf));
    const char *nm = resolve_and_remap(AT_FDCWD, newpath, nbuf, sizeof(nbuf));
    return (int) syscall(SYS_renameat, AT_FDCWD, om, AT_FDCWD, nm);
}

int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    char obuf[PATH_MAX];
    char nbuf[PATH_MAX];
    const char *om = resolve_and_remap(olddirfd, oldpath, obuf, sizeof(obuf));
    const char *nm = resolve_and_remap(newdirfd, newpath, nbuf, sizeof(nbuf));
    return (int) syscall(SYS_renameat, dirfd_for_mapped(olddirfd, om), om,
                         dirfd_for_mapped(newdirfd, nm), nm);
}

char *realpath(const char *pathname, char *resolved) {
    char buf[PATH_MAX];
    const char *mapped = resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf));
    if (mapped == NULL)
        return NULL;
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
    memcpy(resolved, mapped, n + 1);
    return resolved;
}
