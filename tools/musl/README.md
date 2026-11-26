# tools/musl —— 随 APK 分发的本机运行时

这里存放 2.5.0 起替代 proot/Ubuntu 方案的原生运行时的**构建脚本、补丁与测试**。
编好的产物直接放在 `app/src/main/jniLibs/<abi>/` 里随 APK 分发，仓库已自带，
平时不需要重编；改了补丁、BusyBox 配置或版本号才需要跑 `build.sh`。

## 产物

三个文件都是**普通的 ELF 可执行文件**，只是名字必须长成 `lib*.so` ——
Android 只把这种名字的文件解到 `nativeLibraryDir`，而那是应用唯一能 `execve`
的目录（Android 10 起 W^X 限制，应用私有目录里的文件一律 EACCES）：

| 文件 | 内容 |
| --- | --- |
| `libbusybox.so` | 静态链接的 BusyBox：ash 与两百来个命令 |
| `libmusl.so` | musl 的动态加载器兼 libc，用来启动按需下载的 CPython |
| `libdshaexec.so` | `python3` / `pip` 的入口，把调用转交给 `libmusl.so` |

`libdshaexec.so` 的转接形式：

```
libmusl.so --library-path <python>/lib --argv0 <入口> -- <python>/bin/python3 ...
```

应用侧（`agent/MuslEnv.kt`、`agent/MuslPython.kt`）负责准备环境变量：
`PATH`、`HOME`、`TMPDIR`、`MUSL_ETC`、`MUSL_SHELL`、`DSHA_BIN`、
`DSHA_PYTHON_HOME`、`SSL_CERT_FILE`、`TZ`、`LANG`。

## 版本

- musl **1.2.5**（`patches/musl-1.2.5-android.patch`）
- BusyBox **1.37.0**（`patches/busybox-1.37.0-android.patch` + `busybox-config.sh`）
- CPython **3.12.16**（musl 版，python-build-standalone `20261124`，
  `cpython-3.12.16+20261124-<arch>-unknown-linux-musl-install_only_stripped.tar.gz`）
  —— **不随 APK 分发**，首次用到时按需下载、解压到应用私有目录：
  - `aarch64-unknown-linux-musl`：`58c596296e8d5a60474e04ff2d9ae601d706860cde2e5a92f065c57d2ce90b2b`（28578726 字节）
  - `x86_64-unknown-linux-musl`：`da7afb8381ddd5c4356c9c09cd54fe2f79f868c181f04df94264e7965f472db7`（28220561 字节）

补丁要点：

- musl：`MUSL_ETC`（resolv.conf / hosts / services / passwd / group 走指定目录）、
  `MUSL_SHELL`（`system` / `popen` 用内置 shell）、`set*id` 空操作不进内核、
  root 专属调用非 root 直接 EPERM、`membarrier` 不发系统调用、
  x86_64 去掉 Android seccomp 拦截的 40 个旧式系统调用号
- BusyBox：认 `libbusybox.so` 这个名字；ash 执行 EACCES 的脚本时自己找
  解释器（免 `chmod +x`）；配置走 `busybox-config.sh`（静态链接、关 FEATURE_SUID、
  关 SHA 硬件加速等）

## 构建

`build.sh` 把两个 ABI 都编好并写进 `app/src/main/jniLibs/`：

```sh
tools/musl/build.sh                  # 两个 ABI 都编
WORK=/tmp/musl-build tools/musl/build.sh   # 指定构建目录
```

环境要求（x86_64 的 Debian / Ubuntu）：

```text
build-essential gcc-aarch64-linux-gnu linux-libc-dev linux-libc-dev-arm64-cross
bzip2 patch curl
```

构建目录布局（默认 `<repo>/build/musl`）：`dl/`（源码包）、`src/`（解压+打补丁）、
`obj/`（编译中间产物）、`sysroot/`（装好的 musl 与 `musl-gcc`）。
`SOURCE_DATE_EPOCH` 与 `KCONFIG_NOTIMESTAMP` 固定，同一套工具链重编应得到
逐字节相同的产物。

## 测试

```sh
tools/musl/test/run-tests.sh
PYTHON_X86_64=/path/python PYTHON_AARCH64=/path/python tools/musl/test/run-tests.sh
```

`PYTHON_*` 指向解压好的 python-build-standalone musl 包（内含 `bin/`、`lib/`）；
不给就只测 BusyBox 那部分。`WORK` 要与 `build.sh` 用同一个目录。

- **x86_64**：直接跑，外面套一层模拟 Android seccomp 过滤器的
  `seccomp_run`（`test/seccomp_run.c`，用构建出的 `musl-gcc` 静态编译）。
  踩到任何被 Android 禁止的系统调用，进程会被 SIGSYS 杀掉、用例失败。
- **arm64-v8a**：靠 qemu-user 跑（需要 `qemu-user-static` 并注册 binfmt）。
  qemu 不模拟 seccomp，改为把用到的系统调用全部记录下来，对着黑名单查一遍。

冒烟用例在 `test/smoke.sh`（ash 语法、文件/文本工具、进程、网络回环、
免 `chmod +x` 跑脚本、Python 启动与常用扩展等），最后输出 `ALL PASSED` 或失败数。

注意：这只覆盖主机与 qemu 上的行为，**不是真机验证**。

## ABI

`arm64-v8a` 与 `x86_64`（覆盖绝大多数真机与模拟器；32 位设备自 2.5.0 起不再支持）。

## 校验值

`SHA256SUMS` 是仓库里六份产物的校验值（由 `build.sh` 末尾自动写出）：

```sh
cd app/src/main/jniLibs && sha256sum -c ../../../../tools/musl/SHA256SUMS
```
