#!/bin/sh
# 冒烟测试：在 BusyBox ash 里把常用路径都走一遍。
#
# 由 run-tests.sh 调起（它负责准备环境变量）。任何一步不符预期就记一笔，
# 最后以非零退出。x86_64 上整个脚本跑在模拟的安卓 seccomp 过滤器里，
# 所以「没有被 SIGSYS 杀掉」本身就是被验证的内容之一。
fails=0
t() { # t <名字> <期望输出> <命令…>
	name=$1; want=$2; shift 2
	got=$("$@" 2>&1); rc=$?
	if [ "$got" = "$want" ] && [ $rc -eq 0 ]; then
		echo "ok   $name"
	else
		echo "FAIL $name (rc=$rc)"; echo "  want: $want"; echo "  got:  $got" | head -5
		fails=$((fails + 1))
	fi
}
tc() { name=$1; want=$2; shift 2; t "$name" "$want" sh -c "$*"; }

W=$TMPDIR/smoke.$$; rm -rf "$W"; mkdir -p "$W/a/b" || exit 2; cd "$W" || exit 2

# ── shell 本身 ──
tc "管道与子 shell"      "3"          'printf "x\ny\nz\n" | wc -l'
tc "命令替换"            "ab"         'echo "$(echo a)$(echo b)"'
tc "here-doc"            "hi there"   'cat <<X
hi there
X'
tc "后台任务与 wait"     "done"       'sleep 0.1 & wait; echo done'
tc "bash 名字可用"       "5"          'bash -c "echo \$((2+3))"'
tc "[[ ]] 基本可用"       "yes"        '[[ -d . && abc == abc && abc =~ ^a.c\$ ]] && echo yes'
tc "trap 与 kill"        "caught"     'trap "echo caught" USR1; kill -USR1 $$; :'
tc "exec 重定向"         "x"          'exec 3>f3; echo x >&3; exec 3>&-; cat f3'

# ── 文件操作 ──
tc "写读文件"            "hello"      'echo hello > f1 && cat f1'
tc "cp mv rm"            "hello"      'cp f1 f2 && mv f2 a/b/f3 && cat a/b/f3 && rm a/b/f3'
tc "ln -s readlink"      "f1"         'ln -s f1 l1 && readlink l1'
tc "chmod stat"          "640"        'chmod 640 f1 && stat -c %a f1'
tc "touch 与 test"       "newer"      'touch -d "2020-01-01 00:00:00" old; [ f1 -nt old ] && echo newer'
tc "find"                "./a/b"      'find . -type d -name b'
tc "ls -la 能列目录"     "ok"         'ls -la . >/dev/null && echo ok'
tc "du df"               "ok"         'du -s . >/dev/null && df . >/dev/null && echo ok'
tc "mkdir -p rmdir"      "ok"         'mkdir -p x/y/z && rmdir x/y/z x/y x && echo ok'
tc "mktemp"              "ok"         'f=$(mktemp) && [ -f "$f" ] && rm "$f" && echo ok'
tc "realpath"            "$W/f1"      'realpath f1'

# ── 文本工具 ──
tc "grep sed"            "b2"         'printf "a1\nb2\n" | grep b | sed "s/x/y/"'
tc "awk"                 "6"          'echo "1 2 3" | awk "{print \$1+\$2+\$3}"'
tc "sort uniq"           "a b"        'printf "b\na\nb\n" | sort | uniq | tr "\n" " " | sed "s/ $//"'
tc "cut head tail"       "2"          'printf "1,a\n2,b\n3,c\n" | head -2 | tail -1 | cut -d, -f1'
tc "diff patch"          "new"        'echo old > d1; echo new > d2; diff -u d1 d2 > d.patch; patch d1 < d.patch >/dev/null; cat d1'
tc "xargs"               "3"          'printf "a\nb\nc\n" | xargs -n1 echo | wc -l'
tc "printf seq"          "1-2-3-"     'seq 3 | while read n; do printf "%s-" "$n"; done'
tc "base64 往返"         "payload"    'echo payload | base64 | base64 -d'
tc "sha256sum"           "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" 'printf abc | sha256sum | cut -d" " -f1'
tc "md5sum"              "900150983cd24fb0d6963f7d28e17f72" 'printf abc | md5sum | cut -d" " -f1'
tc "bc"                  "42"         'echo "6*7" | bc'

# ── 压缩归档 ──
tc "tar gzip 往返"       "hello"      'tar czf t.tgz f1 && mkdir ex && tar xzf t.tgz -C ex && cat ex/f1'
tc "gzip gunzip"         "hello"      'cp f1 g1 && gzip g1 && gunzip g1.gz && cat g1'
tc "bzip2 xz"            "hello"      'cp f1 h1 && bzip2 h1 && bunzip2 h1.bz2 && xz h1 2>/dev/null; unxz h1.xz 2>/dev/null; cat h1'
tc "unzip 可用"          "ok"         'unzip 2>&1 | grep -q Usage && echo ok'

# ── 进程与系统信息 ──
tc "ps 看得到自己"       "ok"         'ps | grep -q " sh\| ash\|busybox" && echo ok'
tc "id whoami 不崩"      "ok"         'id >/dev/null && whoami >/dev/null && echo ok'
tc "env printenv"        "$HOME"      'printenv HOME'
tc "date"                "ok"         'date +%s | grep -q "^[0-9]*$" && echo ok'
tc "uname nproc"         "ok"         'uname -a >/dev/null && nproc >/dev/null && echo ok'
tc "timeout 能杀掉超时任务" "killed"   'timeout 1 sleep 5 >/dev/null 2>&1 || echo killed'
tc "nohup nice"          "x"          'nice -n 5 echo x'
tc "setsid 起新进程组"   "x"          'setsid echo x'
tc "free uptime"         "ok"         'free >/dev/null; uptime >/dev/null; echo ok'

# ── 沙箱里做不了的事：必须是普通的失败，而不是被杀 ──
tc "date -s 被拒而非被杀"   "1"       'date -s "2020-01-01 00:00:00" 2>&1 | grep -c "not permitted"'
tc "hostname 设置被拒"      "1"       'hostname zz >/dev/null 2>&1; echo $?'
tc "mknod 被拒"             "1"       'mknod n c 1 3 >/dev/null 2>&1; echo $?'

# ── 脚本免 chmod ──
printf '#!/bin/sh\necho script "$@"\n' > s1.sh
printf 'echo plain\n' > s2
printf '#!/usr/bin/awk -f\nBEGIN { print "awk" }\n' > s3.awk
printf '#!/usr/bin/env sh\necho env-sh\n' > s4
tc "./脚本.sh 不需要 +x"   "script A B" './s1.sh A B'
tc "无 shebang 的脚本"     "plain"      './s2'
tc "#!awk -f"              "awk"        './s3.awk'
tc "#!/usr/bin/env sh"     "env-sh"     './s4'
tc "二进制仍然报权限"      "126"        'head -c 64 /proc/self/exe > bin1; ./bin1 2>/dev/null; echo $?'

# ── 网络（回环）──
mkdir -p www && echo served > www/index.html
httpd -f -p 127.0.0.1:18765 -h www & hp=$!
sleep 0.5
tc "httpd + wget 回环"     "served"     'wget -q -O - http://127.0.0.1:18765/index.html'
tc "nc 回环"               "ok"         'echo | nc -w 2 127.0.0.1 18765 >/dev/null; echo ok'
tc "localhost 走 hosts"    "served"     'wget -q -O - http://localhost:18765/index.html'
kill $hp 2>/dev/null

# ── Python（装了才测）──
if [ -n "${DSHA_PYTHON_HOME:-}" ] && [ -r "$DSHA_PYTHON_HOME/bin/python3" ]; then
	tc "python 起得来"         "42"        'python3 -c "print(6*7)"'
	tc "python 常用扩展"       "ok"        'python3 -c "import ssl, sqlite3, ctypes, zlib, bz2, lzma, hashlib, json, decimal, socket, select, multiprocessing; print(\"ok\")"'
	tc "sys.executable 可再执行" "7"       'python3 -c "import subprocess, sys; print(subprocess.check_output([sys.executable, \"-c\", \"print(3+4)\"], text=True).strip())"'
	tc "os.system 走内置 shell" "sys sh"   'python3 -c "import os; os.system(\"echo sys \$0\")"'
	tc "subprocess 调 applet"  "x"         'python3 -c "import subprocess; print(subprocess.check_output([\"sh\", \"-c\", \"echo x\"], text=True).strip())"'
	tc "pip 起得来"            "ok"        'pip --version >/dev/null && echo ok'
	tc "python 脚本免 chmod"   "py a"      'printf "#!/usr/bin/env python3\nimport sys\nprint(\"py\", sys.argv[1])\n" > p1.py; ./p1.py a'
	tc "sqlite 读写"           "1"         'python3 -c "import sqlite3; c=sqlite3.connect(\"t.db\"); c.execute(\"create table t(x)\"); c.execute(\"insert into t values(1)\"); c.commit(); print(c.execute(\"select count(*) from t\").fetchone()[0])"'
	tc "线程与多进程"          "[1, 4, 9]" 'python3 -c "
from concurrent.futures import ThreadPoolExecutor
print(list(ThreadPoolExecutor(2).map(lambda x: x*x, [1,2,3])))"'
	tc "getpwuid 有名字"       "ok"        'python3 -c "import pwd, os; pwd.getpwuid(os.getuid()); print(\"ok\")"'
	tc "时间与本地化"          "ok"        'python3 -c "import time, locale, datetime; datetime.datetime.now(); time.localtime(); print(\"ok\")"'
fi

cd /; rm -rf "$W"
echo "---"
if [ $fails -eq 0 ]; then echo "ALL PASSED"; else echo "$fails FAILED"; fi
exit $fails
