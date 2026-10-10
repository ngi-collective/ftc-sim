#!/usr/bin/env bash
#
# Fails if a built native library asks for more than the oldest system ftc-sim supports.
#   tools/natives-smoke/check-compat.sh <directory of built libraries>
#
# A library built on a new system can quietly refuse to load on an older one, and the smoke test
# cannot see it, because it runs on the machine that did the building. 12.0.1 shipped macOS dylibs
# that required macOS 26 and Linux libraries that required glibc 2.38. The floors:
#
#   macOS    minimum OS no newer than 11.0 (MIN_MACOS); the first release for Apple Silicon
#   Linux    no glibc symbol newer than 2.35 (MAX_GLIBC), and no libstdc++ or libgcc_s at all
#   Windows  imports only the Windows C runtime (api-ms-win-*) and KERNEL32: no MinGW DLLs
set -euo pipefail

dir=$1
min_macos=${MIN_MACOS:-11.0}
max_glibc=${MAX_GLIBC:-2.35}
failed=0

# True when version $1 is no newer than version $2.
not_newer() { [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n1)" == "$2" ]]; }

fail() { echo "check-compat: $1" >&2; failed=1; }

shopt -s nullglob
case "$(uname -s)" in
  Darwin)
    for lib in "$dir"/*.dylib; do
      minos=$(otool -l "$lib" | awk '/LC_BUILD_VERSION/ {b=1} b && /minos/ {print $2; exit}')
      if [[ -z $minos ]]; then
        fail "$(basename "$lib"): no LC_BUILD_VERSION, so its minimum macOS is unknown"
      elif not_newer "$minos" "$min_macos"; then
        echo "check-compat: $(basename "$lib"): minimum macOS $minos"
      else
        fail "$(basename "$lib"): needs macOS $minos, newer than $min_macos"
      fi
    done ;;
  Linux)
    for lib in "$dir"/*.so; do
      symbols=$(objdump -T "$lib")
      newest=$(grep -oE 'GLIBC_[0-9]+(\.[0-9]+)+' <<<"$symbols" | sed 's/GLIBC_//' | sort -V | tail -n1 || true)
      if [[ -n $newest ]] && ! not_newer "$newest" "$max_glibc"; then
        fail "$(basename "$lib"): needs glibc $newest, newer than $max_glibc"
      fi
      if grep -qE 'GLIBCXX_|CXXABI_|GCC_[0-9]' <<<"$symbols"; then
        fail "$(basename "$lib"): links the C++ runtime dynamically; build it with -static-libstdc++ -static-libgcc"
      fi
      echo "check-compat: $(basename "$lib"): newest glibc ${newest:-none}"
    done ;;
  MINGW*|MSYS*)
    for lib in "$dir"/*.dll; do
      while read -r import; do
        case "$import" in
          api-ms-win-*|KERNEL32.dll|kernel32.dll) ;;
          *) fail "$(basename "$lib"): imports $import, which a machine without MSYS2 may not have" ;;
        esac
      done < <(objdump -p "$lib" | sed -n 's/^[[:space:]]*DLL Name: //p')
      echo "check-compat: $(basename "$lib"): imports checked"
    done ;;
  *) echo "check-compat: no check for $(uname -s)" >&2; exit 2 ;;
esac

if (( failed )); then
  exit 1
fi
