#!/bin/sh
#
# 在开发机上验证 jniLibs 里的 musl 运行时。
#
#   x86_64    直接跑，外面套一层模拟安卓 seccomp 过滤器的 seccomp_run：
#             踩到任何一个被安卓禁止的系统调用，进程会被 SIGSYS 杀掉、用例失败。
#   arm64-v8a 靠 qemu-user 跑（需要 qemu-user-static 并注册了 binfmt）。
#             qemu 不模拟 seccomp，所以改成把用到的系统调用全记下来，
#             对着黑名单查一遍。
#
# 用法：
#   tools/musl/test/run-tests.sh
#   PYTHON_X86_64=/path/python PYTHON_AARCH64=/path/python tools/musl/test/run-tests.sh
#
# PYTHON_* 指向解压好的 python-build-standalone musl 包（里面有 bin/、lib/）。
# 不给就只测 BusyBox 那部分。WORK 要和 build.sh 用的是同一个目录。
#
set -eu

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
work=${WORK:-$repo/build/musl}
libs=$repo/app/src/main/jniLibs
tmp=$work/test
status=0

[ -x "$work/sysroot/x86_64/bin/musl-gcc" ] || { echo "先跑 tools/musl/build.sh（WORK=$work）" >&2; exit 2; }
rm -rf "$tmp"; mkdir -p "$tmp"
"$work/sysroot/x86_64/bin/musl-gcc" -static -O2 -Wall -o "$tmp/seccomp_run" "$here/seccomp_run.c"

# 安卓应用进程调了就会被杀的系统调用（aarch64 上的名字）
blocked='setuid setgid setreuid setregid setresuid setresgid setfsuid setfsgid setgroups
mount umount2 chroot pivot_root reboot swapon swapoff sethostname setdomainname
settimeofday clock_settime adjtimex clock_adjtime syslog acct init_module finit_module
delete_module shmget shmat shmdt shmctl semget semop semctl semtimedop msgget msgsnd
msgrcv msgctl mq_open mq_unlink mq_timedsend mq_timedreceive mq_notify mq_getsetattr
add_key request_key keyctl membarrier kexec_load'

# prepare <abi> <python 目录或空>   —— 搭一份和手机上同构的环境
prepare() {
	abi=$1; py=$2
	env=$tmp/$abi
	mkdir -p "$env/bin" "$env/etc" "$env/home" "$env/tmp"
	"$libs/$abi/libbusybox.so" --install -s "$env/bin"
	for n in python3 python pip3 pip; do ln -sf "$libs/$abi/libdshaexec.so" "$env/bin/$n"; done
	printf '127.0.0.1 localhost\n::1 localhost\n' > "$env/etc/hosts"
	cp /etc/resolv.conf "$env/etc/resolv.conf"
	echo "u0_a1:x:$(id -u):$(id -g)::$env/home:/bin/sh" > "$env/etc/passwd"
	echo "u0_a1:x:$(id -g):" > "$env/etc/group"
	path=$env/bin
	[ -n "$py" ] && path=$path:$py/bin
	# 故意用 env -i：手机上起进程时也只有 App 给的这几个变量
	set -- PATH="$path" HOME="$env/home" TMPDIR="$env/tmp" LANG=C.UTF-8 \
		MUSL_ETC="$env/etc" MUSL_SHELL="$libs/$abi/libbusybox.so" DSHA_BIN="$env/bin"
	[ -n "$py" ] && set -- "$@" DSHA_PYTHON_HOME="$py"
	printf '%s\n' "$@" > "$env/environ"
}

run_env() { # run_env <abi> <额外的 VAR=值…> -- <命令…>
	abi=$1; shift
	# shellcheck disable=SC2046
	env -i $(cat "$tmp/$abi/environ") "$@"
}

echo "== x86_64（模拟 seccomp）"
prepare x86_64 "${PYTHON_X86_64:-}"
if run_env x86_64 "$tmp/seccomp_run" "$libs/x86_64/libbusybox.so" sh "$here/smoke.sh" > "$tmp/x86_64.log" 2>&1; then
	tail -n 1 "$tmp/x86_64.log"
else
	grep -v '^ok ' "$tmp/x86_64.log" || true
	status=1
fi

if "$libs/arm64-v8a/libbusybox.so" true 2>/dev/null; then
	echo "== arm64-v8a（qemu-user）"
	prepare arm64-v8a "${PYTHON_AARCH64:-}"
	mkdir -p "$tmp/strace"
	if run_env arm64-v8a QEMU_STRACE=1 QEMU_LOG_FILENAME="$tmp/strace/%d.log" \
		"$libs/arm64-v8a/libbusybox.so" sh "$here/smoke.sh" > "$tmp/arm64-v8a.log" 2>&1; then
		tail -n 1 "$tmp/arm64-v8a.log"
	else
		grep -v '^ok ' "$tmp/arm64-v8a.log" || true
		status=1
	fi
	used=$(cat "$tmp"/strace/*.log 2>/dev/null | grep -a -oE '^[0-9]+ [a-z_0-9]+\(' | sed -E 's/^[0-9]+ //; s/\(//' | sort -u)
	if [ -z "$used" ]; then
		echo "没拿到系统调用记录（qemu 太旧？），跳过黑名单检查"
	else
		hit=""
		for s in $blocked; do
			if printf '%s\n' "$used" | grep -qx "$s"; then hit="$hit $s"; fi
		done
		if [ -n "$hit" ]; then
			echo "FAIL 用到了安卓禁止的系统调用：$hit"
			status=1
		else
			echo "系统调用 $(printf '%s\n' "$used" | wc -l) 种，无一在黑名单内"
		fi
	fi
else
	echo "== arm64-v8a：本机跑不了 aarch64 程序（没有 qemu-user binfmt），跳过"
fi

exit $status
