/*
 * seccomp_run —— 在 x86_64 Linux 上模拟安卓应用进程的 seccomp 过滤器。
 *
 * 用法：seccomp_run <程序> [参数…]
 *
 * 安卓给 zygote 派生的进程装了一份系统调用白名单，名单外的调用不是返回
 * ENOSYS / EPERM，而是直接 SIGSYS 把进程杀掉。我们的 musl / BusyBox 是照着
 * 这份名单改的，这个小工具用来在开发机上验证「确实一个都没踩」：
 * 被拦的调用同样以 SIGSYS 收场，测试脚本看退出码（159）就知道。
 *
 * 名单比真机**更严**：真机在 x86_64 上还放行 open / stat / readlink / getdents
 * 这几个旧调用，这里一并拦掉 —— 我们的二进制本来就不该再用它们。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/syscall.h>

#ifndef __x86_64__
#error "seccomp_run only models the x86_64 policy"
#endif

static const int blocked[] = {
	/* x86_64 的旧式调用：bionic 不用，白名单里没有 */
	2 /*open*/, 4 /*stat*/, 6 /*lstat*/, 7 /*poll*/, 21 /*access*/, 22 /*pipe*/,
	23 /*select*/, 33 /*dup2*/, 34 /*pause*/, 37 /*alarm*/, 57 /*fork*/,
	78 /*getdents*/, 82 /*rename*/, 83 /*mkdir*/, 84 /*rmdir*/, 85 /*creat*/,
	86 /*link*/, 87 /*unlink*/, 88 /*symlink*/, 89 /*readlink*/, 90 /*chmod*/,
	92 /*chown*/, 94 /*lchown*/, 111 /*getpgrp*/, 132 /*utime*/, 133 /*mknod*/,
	134 /*uselib*/, 136 /*ustat*/, 139 /*sysfs*/, 156 /*_sysctl*/, 201 /*time*/,
	213 /*epoll_create*/, 214, 215, 232 /*epoll_wait*/, 235 /*utimes*/,
	253 /*inotify_init*/, 261 /*futimesat*/, 282 /*signalfd*/, 284 /*eventfd*/,
	/* SECCOMP_BLOCKLIST_APP：改身份 */
	105 /*setuid*/, 106 /*setgid*/, 113 /*setreuid*/, 114 /*setregid*/,
	116 /*setgroups*/, 117 /*setresuid*/, 119 /*setresgid*/, 122 /*setfsuid*/,
	123 /*setfsgid*/,
	/* SECCOMP_BLOCKLIST_APP：改时间、挂载、模块、主机名、重启 */
	103 /*syslog*/, 159 /*adjtimex*/, 161 /*chroot*/, 163 /*acct*/,
	164 /*settimeofday*/, 165 /*mount*/, 166 /*umount2*/, 167 /*swapon*/,
	168 /*swapoff*/, 169 /*reboot*/, 170 /*sethostname*/, 171 /*setdomainname*/,
	175 /*init_module*/, 176 /*delete_module*/, 227 /*clock_settime*/,
	305 /*clock_adjtime*/, 313 /*finit_module*/,
	/* bionic 从来不提供的：SysV IPC、POSIX 消息队列、密钥环、端口 IO */
	29, 30, 31, 64, 65, 66, 67, 68, 69, 70, 71, 220,
	240, 241, 242, 243, 244, 245, 248, 249, 250, 172, 173, 246,
};

int main(int argc, char **argv)
{
	enum { N = sizeof blocked / sizeof blocked[0] };
	static struct sock_filter prog[4 + 2 * N + 1];
	struct sock_fprog fprog;
	int i, n = 0;

	if (argc < 2) {
		fprintf(stderr, "usage: %s program [args...]\n", argv[0]);
		return 2;
	}

	prog[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch));
	prog[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_X86_64, 1, 0);
	prog[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS);
	prog[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
	for (i = 0; i < N; i++) {
		prog[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (unsigned)blocked[i], 0, 1);
		prog[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
	}
	prog[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);

	fprog.len = (unsigned short)n;
	fprog.filter = prog;
	if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) {
		perror("PR_SET_NO_NEW_PRIVS");
		return 2;
	}
	if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &fprog)) {
		perror("seccomp");
		return 2;
	}
	execv(argv[1], argv + 1);
	perror(argv[1]);
	return 127;
}
