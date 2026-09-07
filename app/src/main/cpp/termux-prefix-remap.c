#define _GNU_SOURCE
#include <dlfcn.h>
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

typedef long (*sys6_t)(long, long, long, long, long, long, long);
static sys6_t g_sys;

static long raw_sys(long n, long a1, long a2, long a3, long a4, long a5, long a6) {
    if (g_sys)
        return g_sys(n, a1, a2, a3, a4, a5, a6);
    return -1;
}

static void init_remap(void) __attribute__((constructor));
static void init_remap(void) {
    g_sys = (sys6_t) dlsym(RTLD_NEXT, "syscall");

    const char *from = getenv("TERMUX_PREFIX_REMAP_FROM");
    const char *to = getenv("TERMUX_PREFIX_REMAP_TO");
    char tobuf[PATH_MAX];
    if (from == NULL || from[0] == '\0')
        from = "/data/data/com.termux";
    if (to == NULL || to[0] == '\0') {
        int user = (int) (getuid() / 100000);
        if (user == 0)
            snprintf(tobuf, sizeof(tobuf), "/data/data/tx.work");
        else
            snprintf(tobuf, sizeof(tobuf), "/data/user/%d/tx.work", user);
        to = tobuf;
    }
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
        long n = raw_sys(SYS_getcwd, (long) out, (long) outsz, 0, 0, 0, 0);
        return n > 0 ? 0 : -1;
    }
    char proc[64];
    int pn = snprintf(proc, sizeof(proc), "/proc/self/fd/%d", dirfd);
    if (pn < 0 || pn >= (int) sizeof(proc))
        return -1;
    ssize_t n = raw_sys(SYS_readlinkat, AT_FDCWD, (long) proc, (long) out, (long) (outsz - 1), 0, 0);
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

static void remap_at_path(int *dirfd, const char **pathname, char *buf, size_t bufsz) {
    const char *mapped = resolve_and_remap(*dirfd, *pathname, buf, bufsz);
    *dirfd = dirfd_for_mapped(*dirfd, mapped);
    *pathname = mapped;
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
    remap_at_path(&dirfd, &pathname, buf, sizeof(buf));
    if (flags & O_CREAT)
        return (int) raw_sys(SYS_openat, dirfd, (long) pathname, flags, mode, 0, 0);
    return (int) raw_sys(SYS_openat, dirfd, (long) pathname, flags, 0, 0, 0);
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
    return (int) raw_sys(SYS_faccessat, AT_FDCWD, (long) mapped, mode, 0, 0, 0);
}

int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    char buf[PATH_MAX];
    remap_at_path(&dirfd, &pathname, buf, sizeof(buf));
    return (int) raw_sys(SYS_faccessat, dirfd, (long) pathname, mode, flags, 0, 0);
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
    remap_at_path(&dirfd, &pathname, buf, sizeof(buf));
    return (int) raw_sys(STATAT_NR, dirfd, (long) pathname, (long) statbuf, flags, 0, 0);
}

#ifdef SYS_statx
int statx(int dirfd, const char *pathname, int flags, unsigned int mask, void *statxbuf) {
    char buf[PATH_MAX];
    remap_at_path(&dirfd, &pathname, buf, sizeof(buf));
    return (int) raw_sys(SYS_statx, dirfd, (long) pathname, flags, mask, (long) statxbuf, 0);
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
    remap_at_path(&dirfd, &pathname, buf, sizeof(buf));
    int r = (int) raw_sys(SYS_mkdirat, dirfd, (long) pathname, mode, 0, 0, 0);
    if (r != 0 && errno == EEXIST)
        return 0;
    return r;
}

int chdir(const char *pathname) {
    char buf[PATH_MAX];
    return (int) raw_sys(SYS_chdir, (long) resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf)), 0, 0, 0, 0, 0);
}

int unlink(const char *pathname) {
    char buf[PATH_MAX];
    const char *mapped = resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf));
    return (int) raw_sys(SYS_unlinkat, AT_FDCWD, (long) mapped, 0, 0, 0, 0);
}

int unlinkat(int dirfd, const char *pathname, int flags) {
    char buf[PATH_MAX];
    remap_at_path(&dirfd, &pathname, buf, sizeof(buf));
    return (int) raw_sys(SYS_unlinkat, dirfd, (long) pathname, flags, 0, 0, 0);
}

int rmdir(const char *pathname) {
    char buf[PATH_MAX];
    const char *mapped = resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf));
    return (int) raw_sys(SYS_unlinkat, AT_FDCWD, (long) mapped, AT_REMOVEDIR, 0, 0, 0);
}

int chmod(const char *pathname, mode_t mode) {
    char buf[PATH_MAX];
    const char *mapped = resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf));
    return (int) raw_sys(SYS_fchmodat, AT_FDCWD, (long) mapped, mode, 0, 0, 0);
}

int fchmodat(int dirfd, const char *pathname, mode_t mode, int flags) {
    char buf[PATH_MAX];
    remap_at_path(&dirfd, &pathname, buf, sizeof(buf));
    return (int) raw_sys(SYS_fchmodat, dirfd, (long) pathname, mode, flags, 0, 0);
}

int execve(const char *pathname, char *const argv[], char *const envp[]) {
    char buf[PATH_MAX];
    return (int) raw_sys(SYS_execve, (long) resolve_and_remap(AT_FDCWD, pathname, buf, sizeof(buf)),
                         (long) argv, (long) envp, 0, 0, 0);
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
    return raw_sys(SYS_readlinkat, AT_FDCWD, (long) mapped, (long) buf_out, (long) bufsiz, 0, 0);
}

int symlink(const char *target, const char *linkpath) {
    char tbuf[PATH_MAX];
    char lbuf[PATH_MAX];
    const char *tm = resolve_and_remap(AT_FDCWD, target, tbuf, sizeof(tbuf));
    const char *lm = resolve_and_remap(AT_FDCWD, linkpath, lbuf, sizeof(lbuf));
    return (int) raw_sys(SYS_symlinkat, (long) tm, AT_FDCWD, (long) lm, 0, 0, 0);
}

int rename(const char *oldpath, const char *newpath) {
    char obuf[PATH_MAX];
    char nbuf[PATH_MAX];
    const char *om = resolve_and_remap(AT_FDCWD, oldpath, obuf, sizeof(obuf));
    const char *nm = resolve_and_remap(AT_FDCWD, newpath, nbuf, sizeof(nbuf));
    return (int) raw_sys(SYS_renameat, AT_FDCWD, (long) om, AT_FDCWD, (long) nm, 0, 0);
}

int renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath) {
    char obuf[PATH_MAX];
    char nbuf[PATH_MAX];
    remap_at_path(&olddirfd, &oldpath, obuf, sizeof(obuf));
    remap_at_path(&newdirfd, &newpath, nbuf, sizeof(nbuf));
    return (int) raw_sys(SYS_renameat, olddirfd, (long) oldpath, newdirfd, (long) newpath, 0, 0);
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

/* dpkg/gnulib often call syscall(SYS_statx, ...) and skip libc wrappers. */
long syscall(long n, long a1, long a2, long a3, long a4, long a5, long a6) {
    if (g_enabled && g_sys) {
        char buf[PATH_MAX];
        const char *p;
        int d;
#ifdef SYS_statx
        if (n == SYS_statx) {
            d = (int) a1;
            p = (const char *) a2;
            remap_at_path(&d, &p, buf, sizeof(buf));
            a1 = d;
            a2 = (long) p;
        } else
#endif
        if (n == SYS_openat || n == SYS_faccessat || n == STATAT_NR
            || n == SYS_mkdirat || n == SYS_unlinkat || n == SYS_fchmodat
            || n == SYS_readlinkat) {
            d = (int) a1;
            p = (const char *) a2;
            remap_at_path(&d, &p, buf, sizeof(buf));
            a1 = d;
            a2 = (long) p;
            if (n == SYS_mkdirat) {
                long r = raw_sys(n, a1, a2, a3, a4, a5, a6);
                if (r != 0 && errno == EEXIST)
                    return 0;
                return r;
            }
        } else if (n == SYS_chdir || n == SYS_execve) {
            p = (const char *) a1;
            a1 = (long) resolve_and_remap(AT_FDCWD, p, buf, sizeof(buf));
        }
    }
    return raw_sys(n, a1, a2, a3, a4, a5, a6);
}
