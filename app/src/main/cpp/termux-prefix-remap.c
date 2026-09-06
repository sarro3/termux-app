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

static const char *remap_path(const char *path, char *buf, size_t buf_size) {
    if (!g_enabled || path == NULL || path[0] != '/')
        return path;
    size_t path_len = strlen(path);
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

typedef int (*open_t)(const char *, int, ...);
typedef int (*openat_t)(int, const char *, int, ...);
typedef int (*access_t)(const char *, int);
typedef int (*faccessat_t)(int, const char *, int, int);
typedef int (*stat_t)(const char *, struct stat *);
typedef int (*lstat_t)(const char *, struct stat *);
typedef int (*mkdir_t)(const char *, mode_t);
typedef int (*chdir_t)(const char *);
typedef int (*unlink_t)(const char *);
typedef int (*rmdir_t)(const char *);
typedef int (*chmod_t)(const char *, mode_t);
typedef int (*execve_t)(const char *, char *const[], char *const[]);
typedef FILE *(*fopen_t)(const char *, const char *);
typedef DIR *(*opendir_t)(const char *);
typedef ssize_t (*readlink_t)(const char *, char *, size_t);
typedef int (*symlink_t)(const char *, const char *);
typedef int (*rename_t)(const char *, const char *);
typedef char *(*realpath_t)(const char *, char *);

static void *load_sym(const char *name) {
    return dlsym(RTLD_NEXT, name);
}

int open(const char *pathname, int flags, ...) {
    static open_t real_open = NULL;
    if (real_open == NULL)
        real_open = (open_t) load_sym("open");
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
        return real_open(mapped, flags, mode);
    return real_open(mapped, flags);
}

int openat(int dirfd, const char *pathname, int flags, ...) {
    static openat_t real_openat = NULL;
    if (real_openat == NULL)
        real_openat = (openat_t) load_sym("openat");
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
        return real_openat(dirfd, mapped, flags, mode);
    return real_openat(dirfd, mapped, flags);
}

int access(const char *pathname, int mode) {
    static access_t real_access = NULL;
    if (real_access == NULL)
        real_access = (access_t) load_sym("access");
    char buf[PATH_MAX];
    return real_access(remap_path(pathname, buf, sizeof(buf)), mode);
}

int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    static faccessat_t real_faccessat = NULL;
    if (real_faccessat == NULL)
        real_faccessat = (faccessat_t) load_sym("faccessat");
    char buf[PATH_MAX];
    return real_faccessat(dirfd, remap_path(pathname, buf, sizeof(buf)), mode, flags);
}

int stat(const char *pathname, struct stat *statbuf) {
    static stat_t real_stat = NULL;
    if (real_stat == NULL)
        real_stat = (stat_t) load_sym("stat");
    char buf[PATH_MAX];
    return real_stat(remap_path(pathname, buf, sizeof(buf)), statbuf);
}

int lstat(const char *pathname, struct stat *statbuf) {
    static lstat_t real_lstat = NULL;
    if (real_lstat == NULL)
        real_lstat = (lstat_t) load_sym("lstat");
    char buf[PATH_MAX];
    return real_lstat(remap_path(pathname, buf, sizeof(buf)), statbuf);
}

int mkdir(const char *pathname, mode_t mode) {
    static mkdir_t real_mkdir = NULL;
    if (real_mkdir == NULL)
        real_mkdir = (mkdir_t) load_sym("mkdir");
    char buf[PATH_MAX];
    return real_mkdir(remap_path(pathname, buf, sizeof(buf)), mode);
}

int chdir(const char *pathname) {
    static chdir_t real_chdir = NULL;
    if (real_chdir == NULL)
        real_chdir = (chdir_t) load_sym("chdir");
    char buf[PATH_MAX];
    return real_chdir(remap_path(pathname, buf, sizeof(buf)));
}

int unlink(const char *pathname) {
    static unlink_t real_unlink = NULL;
    if (real_unlink == NULL)
        real_unlink = (unlink_t) load_sym("unlink");
    char buf[PATH_MAX];
    return real_unlink(remap_path(pathname, buf, sizeof(buf)));
}

int rmdir(const char *pathname) {
    static rmdir_t real_rmdir = NULL;
    if (real_rmdir == NULL)
        real_rmdir = (rmdir_t) load_sym("rmdir");
    char buf[PATH_MAX];
    return real_rmdir(remap_path(pathname, buf, sizeof(buf)));
}

int chmod(const char *pathname, mode_t mode) {
    static chmod_t real_chmod = NULL;
    if (real_chmod == NULL)
        real_chmod = (chmod_t) load_sym("chmod");
    char buf[PATH_MAX];
    return real_chmod(remap_path(pathname, buf, sizeof(buf)), mode);
}

int execve(const char *pathname, char *const argv[], char *const envp[]) {
    static execve_t real_execve = NULL;
    if (real_execve == NULL)
        real_execve = (execve_t) load_sym("execve");
    char buf[PATH_MAX];
    return real_execve(remap_path(pathname, buf, sizeof(buf)), argv, envp);
}

FILE *fopen(const char *pathname, const char *mode) {
    static fopen_t real_fopen = NULL;
    if (real_fopen == NULL)
        real_fopen = (fopen_t) load_sym("fopen");
    char buf[PATH_MAX];
    return real_fopen(remap_path(pathname, buf, sizeof(buf)), mode);
}

DIR *opendir(const char *pathname) {
    static opendir_t real_opendir = NULL;
    if (real_opendir == NULL)
        real_opendir = (opendir_t) load_sym("opendir");
    char buf[PATH_MAX];
    return real_opendir(remap_path(pathname, buf, sizeof(buf)));
}

ssize_t readlink(const char *pathname, char *buf_out, size_t bufsiz) {
    static readlink_t real_readlink = NULL;
    if (real_readlink == NULL)
        real_readlink = (readlink_t) load_sym("readlink");
    char buf[PATH_MAX];
    return real_readlink(remap_path(pathname, buf, sizeof(buf)), buf_out, bufsiz);
}

int symlink(const char *target, const char *linkpath) {
    static symlink_t real_symlink = NULL;
    if (real_symlink == NULL)
        real_symlink = (symlink_t) load_sym("symlink");
    char tbuf[PATH_MAX];
    char lbuf[PATH_MAX];
    return real_symlink(remap_path(target, tbuf, sizeof(tbuf)),
                        remap_path(linkpath, lbuf, sizeof(lbuf)));
}

int rename(const char *oldpath, const char *newpath) {
    static rename_t real_rename = NULL;
    if (real_rename == NULL)
        real_rename = (rename_t) load_sym("rename");
    char obuf[PATH_MAX];
    char nbuf[PATH_MAX];
    return real_rename(remap_path(oldpath, obuf, sizeof(obuf)),
                       remap_path(newpath, nbuf, sizeof(nbuf)));
}

char *realpath(const char *pathname, char *resolved) {
    static realpath_t real_realpath = NULL;
    if (real_realpath == NULL)
        real_realpath = (realpath_t) load_sym("realpath");
    char buf[PATH_MAX];
    return real_realpath(remap_path(pathname, buf, sizeof(buf)), resolved);
}
