/*
 * libdshaexec.so —— python3 / pip 的入口。
 *
 * 安卓 10 起，应用不能 execve 自己私有目录里的文件，只有 APK 自带的
 * nativeLibraryDir 里的东西可以。按需下载的 CPython 落在私有目录里，
 * 所以不能直接执行；但把它交给 musl 的动态加载器去 mmap 是允许的：
 *
 *     libmusl.so --library-path <python>/lib --argv0 <入口> -- <python>/bin/python3 ...
 *
 * 这个小程序就是做这一次转接。它本身随 APK 分发（可执行），
 * 在 PATH 里以 python3 / python / pip3 / pip 四条软链的形式出现。
 *
 * 环境变量（由 App 在起进程时设置）：
 *   DSHA_PYTHON_HOME  解压出来的 CPython 根目录（里面有 bin/ 和 lib/）
 *   DSHA_BIN          放那四条软链的目录；作为 sys.executable 报给 Python，
 *                     这样 subprocess 再起 Python 时走的仍是这条可执行的路
 *   DSHA_MUSL_LOADER  加载器路径；不设则取本程序同目录下的 libmusl.so
 */
#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void die(const char *what, const char *detail)
{
	dprintf(2, "dsha-exec: %s%s%s\n", what, detail ? ": " : "", detail ? detail : "");
	_exit(127);
}

static const char *base_name(const char *path)
{
	const char *slash = strrchr(path, '/');
	return slash ? slash + 1 : path;
}

int main(int argc, char **argv)
{
	static char loader[PATH_MAX], python[PATH_MAX], libdir[PATH_MAX], argv0[PATH_MAX];
	const char *home = getenv("DSHA_PYTHON_HOME");
	const char *bin = getenv("DSHA_BIN");
	const char *ld = getenv("DSHA_MUSL_LOADER");
	const char *name = base_name(argc > 0 && argv[0] ? argv[0] : "python3");
	int is_pip = strncmp(name, "pip", 3) == 0;
	char **out;
	int i, n = 0;

	if (!home || home[0] != '/')
		die("Python is not installed (open Settings > Runtime to install it)", 0);

	if (ld && ld[0] == '/') {
		if (strlen(ld) >= sizeof loader) die("path too long", ld);
		strcpy(loader, ld);
	} else {
		ssize_t len = readlink("/proc/self/exe", loader, sizeof loader - 1);
		char *slash;
		if (len <= 0) die("cannot resolve /proc/self/exe", strerror(errno));
		loader[len] = 0;
		slash = strrchr(loader, '/');
		if (!slash || (size_t)(slash - loader) + sizeof "/libmusl.so" > sizeof loader)
			die("unexpected executable path", loader);
		strcpy(slash, "/libmusl.so");
	}

	if (snprintf(python, sizeof python, "%s/bin/python3", home) >= (int)sizeof python
	 || snprintf(libdir, sizeof libdir, "%s/lib", home) >= (int)sizeof libdir)
		die("path too long", home);
	if (access(python, R_OK))
		die("Python is not installed (open Settings > Runtime to install it)", python);

	/*
	 * 报给 Python 的 argv[0]：
	 * - 调用时带了路径（venv/bin/python 之类）就原样给，Python 靠它找 pyvenv.cfg；
	 * - 否则给 PATH 里那条软链，保证 sys.executable 是一个真能 exec 的东西。
	 */
	if (!is_pip && argc > 0 && strchr(argv[0], '/')) {
		if (strlen(argv[0]) >= sizeof argv0) die("path too long", argv[0]);
		strcpy(argv0, argv[0]);
	} else if (bin && bin[0] == '/') {
		if (snprintf(argv0, sizeof argv0, "%s/python3", bin) >= (int)sizeof argv0)
			die("path too long", bin);
	} else {
		strcpy(argv0, python);
	}

	setenv("PYTHONHOME", home, 1);

	out = calloc((size_t)argc + 10, sizeof *out);
	if (!out) die("out of memory", 0);
	out[n++] = loader;
	out[n++] = "--library-path";
	out[n++] = libdir;
	out[n++] = "--argv0";
	out[n++] = argv0;
	out[n++] = "--";
	out[n++] = python;
	if (is_pip) {
		out[n++] = "-m";
		out[n++] = "pip";
	}
	for (i = 1; i < argc; i++) out[n++] = argv[i];
	out[n] = 0;

	execv(loader, out);
	die("cannot start the musl loader", strerror(errno));
	return 127;
}
