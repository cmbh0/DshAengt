#!/bin/sh
#
# 重新构建随 APK 分发的 musl 运行时（app/src/main/jniLibs/<abi>/ 下那三个文件）：
#
#   libbusybox.so   静态链接的 BusyBox：shell（ash）加两百来个命令
#   libmusl.so      musl 的动态加载器 / libc，用来启动按需下载的 CPython
#   libdshaexec.so  python3 / pip 的入口，把调用转给 libmusl.so
#
# 三个都是普通的 ELF 可执行文件，只是名字必须长成 lib*.so —— 安卓只把这种名字
# 的文件解到 nativeLibraryDir，而那是应用唯一能 execve 的目录。
#
# 仓库里已经带着编好的产物，平时不需要跑这个脚本；改了补丁 / 配置 / 版本才重编。
#
# 环境：x86_64 的 Debian / Ubuntu，需要这些包：
#   build-essential gcc-aarch64-linux-gnu linux-libc-dev linux-libc-dev-arm64-cross
#   bzip2 patch curl
#
# 用法：tools/musl/build.sh            # 两个 ABI 都编，产物写进 jniLibs
#       WORK=/tmp/musl-build tools/musl/build.sh
#       tools/musl/test/run-tests.sh   # 编完之后验证
#
set -eu

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
work=${WORK:-$repo/build/musl}
jobs=${JOBS:-$(nproc)}

MUSL_VERSION=1.2.5
MUSL_SHA256=a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4
BUSYBOX_VERSION=1.37.0
BUSYBOX_SHA256=3311dff32e746499f4df0d5df04d7eb396382d7e108bb9250e7b519b837043a4

# 让产物不带构建时间：同一套工具链编两次，结果逐字节相同
export SOURCE_DATE_EPOCH=1727395200 KCONFIG_NOTIMESTAMP=1 LC_ALL=C TZ=UTC

say() { printf '\n== %s\n' "$*"; }
die() { printf 'build.sh: %s\n' "$*" >&2; exit 1; }

# fetch <文件名> <sha256> <url>...   —— 依次试，校验不过就换下一个
fetch() {
	name=$1; sha=$2; shift 2
	out=$work/dl/$name
	mkdir -p "$work/dl"
	if [ -f "$out" ] && echo "$sha  $out" | sha256sum -c - >/dev/null 2>&1; then return 0; fi
	for url in "$@"; do
		printf 'download %s\n' "$url"
		if curl -fsSL --connect-timeout 20 --max-time 600 -o "$out.part" "$url" \
			&& echo "$sha  $out.part" | sha256sum -c - >/dev/null 2>&1; then
			mv "$out.part" "$out"
			return 0
		fi
		rm -f "$out.part"
	done
	die "下载失败或校验不符：$name"
}

[ "$(uname -m)" = x86_64 ] || die "只支持在 x86_64 主机上构建"
for tool in gcc aarch64-linux-gnu-gcc make patch bzip2 curl sha256sum; do
	command -v "$tool" >/dev/null 2>&1 || die "缺少 $tool（见脚本开头的包列表）"
done

say "准备源码"
fetch "musl-$MUSL_VERSION.tar.gz" "$MUSL_SHA256" \
	"https://musl.libc.org/releases/musl-$MUSL_VERSION.tar.gz" \
	"https://deb.debian.org/debian/pool/main/m/musl/musl_$MUSL_VERSION.orig.tar.gz"
fetch "busybox-$BUSYBOX_VERSION.tar.bz2" "$BUSYBOX_SHA256" \
	"https://busybox.net/downloads/busybox-$BUSYBOX_VERSION.tar.bz2"

rm -rf "$work/src" "$work/obj" "$work/sysroot"
mkdir -p "$work/src" "$work/obj" "$work/sysroot"
tar -xzf "$work/dl/musl-$MUSL_VERSION.tar.gz" -C "$work/src"
tar -xjf "$work/dl/busybox-$BUSYBOX_VERSION.tar.bz2" -C "$work/src"
musl_src=$work/src/musl-$MUSL_VERSION
bb_src=$work/src/busybox-$BUSYBOX_VERSION
(cd "$musl_src" && patch -s -p1 < "$here/patches/musl-$MUSL_VERSION-android.patch")
(cd "$bb_src" && patch -s -p1 < "$here/patches/busybox-$BUSYBOX_VERSION-android.patch")

# build_abi <安卓 ABI> <musl 架构> <内核 ARCH> <交叉前缀> <内核头文件目录> <asm 头文件目录>
build_abi() {
	abi=$1; arch=$2; karch=$3; cross=$4; khdr=$5; kasm=$6
	sysroot=$work/sysroot/$arch
	cc=$sysroot/bin/musl-gcc
	out=$repo/app/src/main/jniLibs/$abi

	say "[$abi] musl $MUSL_VERSION"
	mkdir -p "$work/obj/musl-$arch"
	# 本机架构不能带 --target：带了 configure 会去找 x86_64-linux-musl-gcc
	if [ -n "$cross" ]; then target="--target=$arch-linux-musl"; else target=""; fi
	(
		cd "$work/obj/musl-$arch"
		# shellcheck disable=SC2086
		CROSS_COMPILE=$cross "$musl_src/configure" $target \
			--prefix="$sysroot" --syslibdir="$sysroot/lib" \
			--enable-wrapper=gcc CFLAGS="-O2" >configure.log 2>&1
		make -j"$jobs" >make.log 2>&1
		make install >>make.log 2>&1
	) || die "[$abi] musl 构建失败，见 $work/obj/musl-$arch/make.log"

	# BusyBox 要内核头文件；musl-gcc 只认自己 sysroot 里的 include
	for d in linux asm-generic mtd; do
		[ -d "$khdr/$d" ] || die "[$abi] 找不到内核头文件 $khdr/$d"
		cp -rL "$khdr/$d" "$sysroot/include/$d"
	done
	[ -d "$kasm" ] || die "[$abi] 找不到 $kasm"
	cp -rL "$kasm" "$sysroot/include/asm"

	say "[$abi] BusyBox $BUSYBOX_VERSION"
	bb_obj=$work/obj/busybox-$arch
	mkdir -p "$bb_obj"
	(
		cd "$bb_src"
		make O="$bb_obj" ARCH="$karch" defconfig >/dev/null 2>&1
		sh "$here/busybox-config.sh" "$bb_obj/.config"
		cd "$bb_obj"
		yes "" | make ARCH="$karch" oldconfig >/dev/null 2>&1 || true
		make ARCH="$karch" CROSS_COMPILE="$cross" CC="$cc" HOSTCC=gcc -j"$jobs" busybox >make.log 2>&1
	) || die "[$abi] BusyBox 构建失败，见 $bb_obj/make.log"
	grep -q '^CONFIG_STATIC=y' "$bb_obj/.config" || die "[$abi] BusyBox 配置没生效（不是静态链接）"
	grep -q '^CONFIG_FEATURE_SUID=y' "$bb_obj/.config" && die "[$abi] BusyBox 配置没生效（FEATURE_SUID 还开着）"

	say "[$abi] 入口程序"
	"$cc" -static -O2 -Wall -Wextra -o "$work/obj/dsha_exec-$arch" "$here/launcher/dsha_exec.c"

	mkdir -p "$out"
	rm -f "$out"/libbusybox.so "$out"/libmusl.so "$out"/libdshaexec.so
	cp "$bb_obj/busybox" "$out/libbusybox.so"
	cp "$sysroot/lib/libc.so" "$out/libmusl.so"
	cp "$work/obj/dsha_exec-$arch" "$out/libdshaexec.so"
	"${cross}strip" "$out/libbusybox.so" "$out/libmusl.so" "$out/libdshaexec.so"
	chmod 755 "$out/libbusybox.so" "$out/libmusl.so" "$out/libdshaexec.so"
}

multiarch=$(gcc -print-multiarch)
build_abi x86_64    x86_64  x86   ""                   /usr/include                    "/usr/include/$multiarch/asm"
build_abi arm64-v8a aarch64 arm64 aarch64-linux-gnu-   /usr/aarch64-linux-gnu/include  /usr/aarch64-linux-gnu/include/asm

say "产物"
(
	cd "$repo/app/src/main/jniLibs"
	sha256sum arm64-v8a/libbusybox.so arm64-v8a/libmusl.so arm64-v8a/libdshaexec.so \
		x86_64/libbusybox.so x86_64/libmusl.so x86_64/libdshaexec.so | tee "$here/SHA256SUMS"
	ls -l arm64-v8a/lib*.so x86_64/lib*.so
)
