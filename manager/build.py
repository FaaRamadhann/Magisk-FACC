"""
build.py — Build FACC Manager APK TANPA Android Studio/Gradle (versi Python).
Meniru Example-Build (ex-build.py): javac -> d8 -> aapt -> keystore ->
zipalign -> apksigner. Error lebih jelas dibanding versi .bat.

Cara pakai:
    python build.py [path-project]      # default: folder file ini

Struktur project yang diharapkan:
    AndroidManifest.xml
    src/**/*.java        (kode sumber)
    res/...              (icon + resource)

Hasil:
    build/manager.apk   (signed, hasil build mentah)
    manager.apk         (copy siap include ke module zip)
"""

import os
import subprocess
import sys

# ---------------- KONFIG (ubah sesuai environment) ----------------
APP_NAME = "manager"
MIN_SDK = "24"
BUILD_TOOLS = r"C:\AndroidSDK\build-tools\35.0.0"
ANDROID_JAR = r"C:\AndroidSDK\platforms\android-34\android.jar"
JAVA_HOME = r"C:\Program Files\Java\jdk-21.0.10"
KEYSTORE = "debug.keystore"   # relatif ke root project
KEY_ALIAS = "faacc"
STOREPASS = "android"
KEYPASS = "android"
# -------------- akhir KONFIG ------------------------------------


def autodetect():
    """Best-effort: cari SDK/JDK bila path KONFIG tidak ada."""
    global BUILD_TOOLS, ANDROID_JAR, JAVA_HOME
    if not os.path.isdir(BUILD_TOOLS):
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get(
            "ANDROID_SDK_ROOT") or r"C:\AndroidSDK"
        bt_root = os.path.join(sdk, "build-tools")
        if os.path.isdir(bt_root):
            vers = sorted(os.listdir(bt_root))
            if vers:
                BUILD_TOOLS = os.path.join(bt_root, vers[-1])
    if not os.path.isfile(ANDROID_JAR):
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get(
            "ANDROID_SDK_ROOT") or r"C:\AndroidSDK"
        for plat in ("android-34", "android-36", "android-31"):
            cand = os.path.join(sdk, "platforms", plat, "android.jar")
            if os.path.isfile(cand):
                ANDROID_JAR = cand
                break
    jh = os.environ.get("JAVA_HOME")
    if jh and os.path.isfile(os.path.join(jh, "bin", "javac.exe")):
        JAVA_HOME = jh
    elif not os.path.isfile(os.path.join(JAVA_HOME, "bin", "javac.exe")):
        for cand in (r"C:\Program Files\Java\jdk-21.0.10",
                     r"C:\Program Files\Java\latest"):
            if os.path.isfile(os.path.join(cand, "bin", "javac.exe")):
                JAVA_HOME = cand
                break


def run(cmd, cwd):
    """Jalankan command, raise SystemExit bila gagal (dengan output)."""
    print("  $", " ".join(cmd))
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout[-2000:] if r.stdout else "")
        print(r.stderr[-2000:] if r.stderr else "")
        sys.exit("GAGAL (rc=%s): %s" % (r.returncode, cmd[0]))
    return r


def main():
    autodetect()
    if len(sys.argv) > 1:
        root = os.path.abspath(sys.argv[1])
    else:
        root = os.path.abspath(os.path.dirname(__file__))
    for must in ("AndroidManifest.xml", "src"):
        if not os.path.exists(os.path.join(root, must)):
            sys.exit("Bukan project Android: %s tidak ada di %s"
                     % (must, root))
    for tool, path in (("build-tools", BUILD_TOOLS),
                       ("android.jar", ANDROID_JAR),
                       ("javac", os.path.join(JAVA_HOME, "bin",
                                              "javac.exe"))):
        if not os.path.exists(path):
            sys.exit("Tool tidak ketemu (%s): %s — sesuaikan blok KONFIG"
                     % (tool, path))
    print("SDK: %s" % BUILD_TOOLS)
    print("JAR: %s" % ANDROID_JAR)
    print("JDK: %s" % JAVA_HOME)

    javac = os.path.join(JAVA_HOME, "bin", "javac.exe")
    keytool = os.path.join(JAVA_HOME, "bin", "keytool.exe")
    d8 = os.path.join(BUILD_TOOLS, "d8.bat")
    aapt = os.path.join(BUILD_TOOLS, "aapt.exe")
    zipalign = os.path.join(BUILD_TOOLS, "zipalign.exe")
    apksigner = os.path.join(BUILD_TOOLS, "apksigner.bat")
    build = os.path.join(root, "build")
    # Bersihkan obj/dex dulu (wajib tiap build: cegah class basi,
    # mis. sisa package lama setelah rename, ikut ke-pack ke dex).
    import shutil
    for sub in ("obj", "dex"):
        p = os.path.join(build, sub)
        if os.path.isdir(p):
            shutil.rmtree(p)
        os.makedirs(p, exist_ok=True)

    print("[1/6] kumpulkan source...")
    sources = []
    for dp, _, fns in os.walk(os.path.join(root, "src")):
        sources += [os.path.join(dp, f) for f in fns if f.endswith(".java")]
    if not sources:
        sys.exit("Tidak ada file .java di src/")
    print("  %d file java" % len(sources))

    print("[2/6] javac...")
    with open(os.path.join(build, "sources.txt"), "w") as fh:
        fh.write("\n".join(sources))
    run([javac, "--release", "8", "-classpath", ANDROID_JAR,
         "-d", os.path.join(build, "obj"),
         "@" + os.path.join(build, "sources.txt")], root)

    print("[3/6] d8 (java -> dex)...")
    classes = []
    for dp, _, fns in os.walk(os.path.join(build, "obj")):
        classes += [os.path.join(dp, f) for f in fns if f.endswith(".class")]
    run([d8, "--min-api", MIN_SDK, "--lib", ANDROID_JAR,
         "--output", os.path.join(build, "dex")] + classes, root)

    print("[4/6] aapt package...")
    cmd = [aapt, "package", "-f", "-M", "AndroidManifest.xml"]
    if os.path.isdir(os.path.join(root, "res")):
        cmd += ["-S", "res"]
    cmd += ["-I", ANDROID_JAR, "-F", os.path.join(build, "unsigned.apk")]
    run(cmd, root)
    run([aapt, "add", os.path.join(build, "unsigned.apk"), "classes.dex"],
        os.path.join(build, "dex"))

    print("[5/6] keystore (sekali saja, lalu dipakai terus)...")
    print("  PENTING: backup debug.keystore — update APK wajib key yang sama!")
    ks = os.path.join(root, KEYSTORE)
    if not os.path.exists(ks):
        run([keytool, "-genkeypair", "-keystore", ks, "-alias", KEY_ALIAS,
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
             "-storepass", STOREPASS, "-keypass", KEYPASS,
              "-dname", "CN=FACC Manager"], root)

    print("[6/6] zipalign + apksigner...")
    run([zipalign, "-f", "4", os.path.join(build, "unsigned.apk"),
         os.path.join(build, "aligned.apk")], root)
    out_apk = os.path.join(build, "%s.apk" % APP_NAME)
    run([apksigner, "sign", "--ks", ks, "--ks-key-alias", KEY_ALIAS,
         "--ks-pass", "pass:%s" % STOREPASS,
         "--key-pass", "pass:%s" % KEYPASS,
         "--out", out_apk, os.path.join(build, "aligned.apk")], root)
    run([apksigner, "verify", out_apk], root)

    # Copy hasil ke root project agar ikut ke-pack module zip.
    import shutil as _shutil
    _shutil.copyfile(out_apk, os.path.join(root, "%s.apk" % APP_NAME))

    print("\nSELESAI: %s + %s.apk (siap include module)"
          % (out_apk, APP_NAME))


if __name__ == "__main__":
    main()
