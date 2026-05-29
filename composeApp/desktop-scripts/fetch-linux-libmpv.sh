#!/usr/bin/env bash
# Assembles a self-contained, relocatable libmpv bundle for Linux x86_64 and writes it to $1.
#
# Unlike Windows (single self-contained libmpv-2.dll) and macOS (media-kit dylib bundle), there is
# no canonical prebuilt relocatable libmpv for Linux. We build one from the conda-forge `mpv`
# package, whose libraries already carry an $ORIGIN rpath, then copy only libmpv's actual dependency
# closure (libmpv + ffmpeg + libass + ...). The result loads standalone, needing just glibc and a
# modern libstdc++ from the host.
#
# NOTE: the conda libraries are built with a recent GCC, so they require a libstdc++ from roughly
# Ubuntu 22.04+ / equivalent. On older hosts (e.g. Ubuntu 20.04) the JVM preloads the older system
# libstdc++ and the bundle fails to load; the app then falls back to a system-installed libmpv.
# Requires bash and curl; runs only on Linux build hosts.
set -euo pipefail

OUT="${1:?usage: fetch-linux-libmpv.sh <output-dir>}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

echo "[libmpv] downloading micromamba"
curl -Ls https://micro.mamba.pm/api/micromamba/linux-64/latest | tar -xj bin/micromamba

echo "[libmpv] resolving conda-forge 'mpv' (downloads ffmpeg, libass, ...)"
./bin/micromamba create -y -p "$WORK/prefix" -c conda-forge mpv >/dev/null
PREFIX="$WORK/prefix"

if [ ! -e "$PREFIX/lib/libmpv.so.2" ]; then
  echo "[libmpv] ERROR: libmpv.so.2 not present in conda prefix" >&2
  exit 1
fi

rm -rf "$OUT"; mkdir -p "$OUT"

# Recursively copy libmpv's dependency closure. Each SONAME entry is dereferenced into a flat file
# named after the SONAME so the $ORIGIN rpath resolves it. Only libs living inside the conda prefix
# are bundled; glibc/libstdc++/core libs are intentionally left to resolve from the host.
copy_closure() {
  local lib="$1" dep base
  LD_LIBRARY_PATH="$PREFIX/lib" ldd "$lib" 2>/dev/null | awk '/=>/{print $3}' | while read -r dep; do
    case "$dep" in "$PREFIX/lib/"*) ;; *) continue ;; esac
    base="$(basename "$dep")"
    [ -e "$OUT/$base" ] && continue
    cp -L "$dep" "$OUT/$base"
    copy_closure "$dep"
  done
}

cp -L "$PREFIX/lib/libmpv.so.2" "$OUT/libmpv.so.2"
copy_closure "$PREFIX/lib/libmpv.so.2"

echo "[libmpv] bundled $(ls -1 "$OUT" | wc -l) files ($(du -sh "$OUT" | cut -f1)) into $OUT"
