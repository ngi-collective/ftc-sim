#!/usr/bin/env bash
#
# Builds the native libraries a plain JVM needs before it can run a vision OpMode, into
# build/vision-natives. The Gradle task `visionNatives` runs this, and the simulated unit test
# task depends on that, so `mise run test` needs no separate step.
#
# Three of the four natives the FTC SDK's vision stack loads are dealt with here. The fourth,
# OpenCV, is not: org.openpnp:opencv carries its own builds and extracts them itself, which is
# what VisionNatives.ensureLoaded falls back to off-device.
#
#   libapriltag     built from OpenFTC's JNI sources, which are portable C with one Android
#                   dependency: android/log.h, shimmed in tools/vision-natives/shim.
#   libRobotCore    an empty library. Its ninety-seven native methods are libuvc, serial port and
#   libEasyOpenCV   BMP work, and EasyOpenCV's three copy a Mat into an Android Surface. Nothing
#                   on the simulated camera's path calls any of them, but both libraries are
#                   loaded in a static initializer that a plain JVM reaches, so both have to
#                   exist. A stub is honest here in a way it would not be on a robot: a desktop
#                   JVM has no USB camera to drive and no Surface to draw on.
#
# Re-running is cheap: sources are cloned once and the build is skipped when the outputs are
# newer than this script.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
out="$root/build/vision-natives"
# Beside the output rather than inside it: Gradle treats the output directory as this task's
# result, and a pair of git checkouts in there would be hashed on every up-to-date check.
src="$root/build/vision-natives-src"

# Gradle passes its own environment through, and Android Studio's does not always carry
# JAVA_HOME. jni.h has to come from somewhere, so work it out if it is missing.
if [[ -z ${JAVA_HOME:-} ]]; then
  if [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME=$(/usr/libexec/java_home)
  else
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
  fi
fi

# Pinned, both of them. The JNI sources and the detector they wrap have to agree, and a floating
# master would change what a test measures without changing a line of this repo.
plugin_repo=https://github.com/OpenFTC/EOCV-AprilTag-Plugin
plugin_commit=a18aa640b3056d21be89c55776c87c2f1e9cc262
apriltag_repo=https://github.com/AprilRobotics/apriltag
apriltag_commit=1c21707aa7304121a2529a71f8e512f6542f35d0

case "$(uname -s)" in
  Darwin) suffix=dylib ;;
  Linux)  suffix=so ;;
  *) echo "build-vision-natives: unsupported host $(uname -s); vision tests need macOS or Linux" >&2
     exit 2 ;;
esac

stamp="$out/.built"
if [[ -f $stamp && $stamp -nt "${BASH_SOURCE[0]}" ]]; then
  exit 0
fi

mkdir -p "$out"

clone_at() {
  local repo=$1 commit=$2 dir=$3
  if [[ -d $dir/.git ]]; then
    if [[ $(git -C "$dir" rev-parse HEAD) == "$commit" ]]; then
      return
    fi
  else
    mkdir -p "$dir"
    git -C "$dir" init -q
    git -C "$dir" remote add origin "$repo"
  fi
  git -C "$dir" fetch -q --depth 1 origin "$commit"
  git -C "$dir" checkout -q FETCH_HEAD
}

clone_at "$plugin_repo" "$plugin_commit" "$src/plugin"
clone_at "$apriltag_repo" "$apriltag_commit" "$src/plugin/apriltag/apriltag"

work="$out/obj"
rm -rf "$work"
mkdir -p "$work"

jni_includes=(-I"$JAVA_HOME/include")
case "$(uname -s)" in
  Darwin) jni_includes+=(-I"$JAVA_HOME/include/darwin") ;;
  Linux)  jni_includes+=(-I"$JAVA_HOME/include/linux") ;;
esac

detector="$src/plugin/apriltag"
includes=(-I"$detector/apriltag" -I"$detector/apriltag/common" -I"$root/tools/vision-natives/shim")

# Exactly the sources OpenFTC's own CMakeLists names, and no more: the upstream library also
# ships a Python wrapper and tag families the JNI does not wrap, and apriltag_pywrap.c wants
# Python.h.
sources=(
  apriltag/common/g2d.c apriltag/common/getopt.c apriltag/common/homography.c
  apriltag/common/image_u8.c apriltag/common/image_u8_parallel.c apriltag/common/image_u8x3.c
  apriltag/common/image_u8x4.c apriltag/common/matd.c apriltag/common/pam.c
  apriltag/common/pjpeg.c apriltag/common/pjpeg-idct.c apriltag/common/pnm.c
  apriltag/common/string_util.c apriltag/common/svd22.c apriltag/common/time_util.c
  apriltag/common/unionfind.c apriltag/common/workerpool.c apriltag/common/zarray.c
  apriltag/common/zhash.c apriltag/common/zmaxheap.c
  apriltag/apriltag.c apriltag/apriltag_pose.c apriltag/apriltag_quad_thresh.c
  apriltag/tag16h5.c apriltag/tag25h9.c apriltag/tag36h11.c apriltag/tagCircle21h7.c
  apriltag/tagStandard41h12.c
)
for source in "${sources[@]}"; do
  # -w: these are vendor sources, unmodified, and their warnings are not ours to fix.
  # gnu11 rather than c11: glibc hides M_PI and friends under a strict ISO standard, and
  # common/math_util.h uses them.
  cc -O3 -w -fPIC -std=gnu11 "${includes[@]}" -c "$detector/$source" \
     -o "$work/$(basename "$source").o"
done

# libstdc++ does not pull <cmath> in transitively the way libc++ does, and the JNI sources call
# hypot and sqrt without including it. The NDK build gets away with it too.
for source in "$detector"/src/main/cpp/*.cpp; do
  c++ -O3 -w -fPIC -std=gnu++11 -include cmath "${includes[@]}" "${jni_includes[@]}" \
      -c "$source" -o "$work/$(basename "$source").o"
done

c++ -shared -o "$out/libapriltag.$suffix" "$work"/*.o
rm -rf "$work"

placeholder="$out/placeholder.c"
printf 'void ngi_vision_natives_placeholder(void) {}\n' > "$placeholder"
for stub in RobotCore EasyOpenCV; do
  cc -shared -fPIC -o "$out/lib$stub.$suffix" "$placeholder"
done
rm -f "$placeholder"

touch "$stamp"
echo "[vision-natives] $(ls "$out" | grep -c "\.$suffix\$") libraries in $out"
