/* FACC WebUI app.js v1.0.2
 * Arsitektur: WebUI -> Interface (exec bridge) -> FACC Core (CLI `facc --* --json`)
 * WebUI TIDAK punya logika cleanup sendiri. Semua aksi = panggil CLI.
 *
 * Bridge yang didukung (urutan deteksi):
 *  1. ksu.exec(cmd)                 -> WebUI-Next / KernelSU Next / SukiSU /
 *                                      WebUI X (SINKRON, return string stdout)
 *  2. window.koush.exec(cmd)        -> WebUI Next modern (Promise)
 *  3. window.kernelsu.exec(cmd)     -> KernelSU official WebUI (Promise/value)
 *  4. window.mmrl.exec(cmd)         -> MMRL (Promise/value)
 * Jika tidak ada bridge (dibuka di browser biasa), pakai MOCK + tampilkan
 * petunjuk cara membuka yang benar. BUKAN bug cleanup — melainkan WebView
 * tidak menyuntik API (atau izin API belum diberikan di MMRL >= 5.30).
 */

(function () {
  "use strict";
  var $ = function (id) { return document.getElementById(id); };
  var alertBox = $("alert");

  function showAlert(msg, ok) {
    alertBox.textContent = msg;
    alertBox.className = "alert " + (ok ? "ok" : "err");
    alertBox.classList.remove("hidden");
    setTimeout(function () { alertBox.classList.add("hidden"); }, 4000);
  }

  // ---------- Exec bridge ----------
  // PENTING: `ksu` adalah global milik WebView (bukan window.ksu), jadi
  // deteksi wajib pakai typeof agar tidak ReferenceError di browser biasa.
  function detectBridge() {
    try {
      if (typeof ksu !== "undefined" && ksu && typeof ksu.exec === "function") return "ksu";
    } catch (e) { /* abaikan */ }
    if (window.koush && typeof window.koush.exec === "function") return "koush";
    if (window.kernelsu && typeof window.kernelsu.exec === "function") return "kernelsu";
    if (window.mmrl && typeof window.mmrl.exec === "function") return "mmrl";
    return null;
  }

  function execCmd(cmd) {
    var bridge = detectBridge();
    updateBridgeHint(bridge);
    if (!bridge) return Promise.reject(new Error("NO_BRIDGE"));
    try {
      var r;
      if (bridge === "ksu") {
        // WebUI-Next: sinkron, langsung string stdout. Bungkus ke Promise
        // agar konsisten; kalau implementasi async (thenable) tetap didukung.
        r = ksu.exec(cmd);
      } else if (bridge === "koush") {
        r = window.koush.exec(cmd);
      } else if (bridge === "kernelsu") {
        r = window.kernelsu.exec(cmd);
      } else {
        r = window.mmrl.exec(cmd);
      }
      if (r && typeof r.then === "function") return r.then(normalizeResult);
      return Promise.resolve(normalizeResult(r));
    } catch (e) {
      return Promise.reject(e);
    }
  }

  function normalizeResult(res) {
    if (typeof res === "string") return res;
    if (res && typeof res === "object") {
      if (typeof res.stdout === "string") return res.stdout;
      if (typeof res.result === "string") return res.result;
      if (typeof res.data === "string") return res.data;
    }
    return String(res == null ? "" : res);
  }

  function setConn(text, color) {
    $("connText").textContent = text;
    $("connDot").className = "dot " + (color || "gray");
  }

  // Tampilkan status bridge + petunjuk bila tidak ada (bukan silent mock).
  function updateBridgeHint(bridge) {
    var el = $("bridgeHint");
    if (bridge) {
      setConn("bridge: " + bridge, "green");
      if (el) el.textContent = "";
      return;
    }
    setConn("mock — tanpa bridge", "red");
    if (el) {
      el.textContent = "TIDAK TERHUBUNG ke root. Cara betulkan: (1) buka WebUI ini dari aplikasi manager " +
        "(MMRL / WebUI Next / KernelSU), JANGAN dari browser Chrome; (2) di MMRL v5.30+ izinkan " +
        "\"JavaScript KernelSU API\" untuk module FACC; " +
        "(3) Magisk official manager TIDAK mendukung WebUI — pakai MMRL/WebUI Next. " +
        "Data di bawah ini contoh (mock), tombol tidak benar-benar membersihkan.";
    }
  }

  // ---------- Mock data (Fase 6: UI statis dulu) ----------
  var MOCK_STATUS = { apps: 47, total_bytes: 603 * 1048576, total_human: "603.0 MB", last_cleanup: "2026-09-05 07:30", auto_clean: "1", interval_minutes: "30", version: "1.0.5" };
  var MOCK_SCAN = { apps: 47, apps_with_cache: 2, total_bytes: 603 * 1048576, total_human: "603.0 MB", items: [
    { package: "com.android.chrome", bytes: 190840832, human: "182.0 MB" },
    { package: "com.instagram.android", bytes: 441458688, human: "421.0 MB" }
  ]};

  function safeJson(str, fallback) {
    try { return JSON.parse(str); } catch (e) { return fallback; }
  }

  // ---------- Render ----------
  function renderStatus(s) {
    $("statCache").textContent = s.total_human || "~";
    $("statApps").textContent = s.apps != null ? s.apps : "~";
    $("statLast").textContent = s.last_cleanup || "-";
    $("statNext").textContent = (s.auto_clean === "1" || s.auto_clean === 1)
      ? ("tiap " + (s.interval_minutes || "30") + " mnt") : "off";
    if ($("cfgAuto")) $("cfgAuto").checked = (String(s.auto_clean) === "1");
    if ($("cfgInterval")) $("cfgInterval").value = s.interval_minutes || "30";
  }

  function renderItems(items) {
    var tb = $("appRows");
    tb.innerHTML = "";
    if (!items || !items.length) {
      tb.innerHTML = '<tr><td colspan="3" class="muted">Bersih — tidak ada cache.</td></tr>';
      return;
    }
    items.filter(function (it) { return it.bytes > 0; }).forEach(function (it) {
      var tr = document.createElement("tr");
      var tdP = document.createElement("td"); tdP.textContent = it.package;
      var tdC = document.createElement("td"); tdC.textContent = it.human || it.bytes;
      var tdA = document.createElement("td");
      var btn = document.createElement("button");
      btn.className = "btn small"; btn.textContent = "Clean";
      btn.onclick = function () { doClean(it.package, btn); };
      tdA.appendChild(btn);
      tr.appendChild(tdP); tr.appendChild(tdC); tr.appendChild(tdA);
      tb.appendChild(tr);
    });
    if (!tb.children.length) tb.innerHTML = '<tr><td colspan="3" class="muted">Bersih — tidak ada cache.</td></tr>';
  }

  // ---------- Actions (Fase 7: interface ke core) ----------
  // GET /status  -> facc --status --json
  function doStatus() {
    return execCmd("su -c 'facc --status --json'").then(function (out) {
      renderStatus(safeJson(out, MOCK_STATUS));
    }).catch(function () {
      renderStatus(MOCK_STATUS); // fallback mock saat di browser
    });
  }

  // POST /scan -> facc --scan --json
  function doScan(btn) {
    if (btn) btn.disabled = true;
    return execCmd("su -c 'facc --scan --json'").then(function (out) {
      var data = safeJson(out, MOCK_SCAN);
      renderItems(data.items);
      renderStatus({ apps: data.apps, total_bytes: data.total_bytes, total_human: data.total_human,
        last_cleanup: $("statLast").textContent, auto_clean: $("cfgAuto").checked ? "1" : "0",
        interval_minutes: $("cfgInterval").value });
      showAlert("Scan selesai: " + data.total_human + " dari " + data.apps + " app.", true);
    }).catch(function () {
      renderItems(MOCK_SCAN.items);
      showAlert("TIDAK TERHUBUNG — ini cuma contoh (mock). Buka WebUI dari MMRL/WebUI Next, bukan browser.", false);
    }).finally(function () { if (btn) btn.disabled = false; });
  }

  // POST /clean -> facc --clean [--pkg] --json
  function doClean(pkg, btn) {
    var label = pkg || "semua app";
    if (!pkg && !confirm("Bersihkan cache SEMUA aplikasi? (aman: tanpa hapus data)")) return Promise.resolve();
    if (btn) btn.disabled = true;
    var cmd = pkg ? ("su -c 'facc --clean " + pkg + " --json'") : "su -c 'facc --clean --json'";
    return execCmd(cmd).then(function (out) {
      var data = safeJson(out, { ok: true, freed_human: "?" });
      if (data.ok === false) showAlert("Gagal: " + (data.error || "unknown"), false);
      else showAlert("Dibersihkan (" + label + "): " + (data.freed_human || data.freed_bytes || "?"), true);
      return doStatus().then(function () { return doScan(null); });
    }).catch(function () {
      showAlert("TIDAK TERHUBUNG — clean " + label + " TIDAK dijalankan. Buka WebUI dari MMRL/WebUI Next.", false);
    }).finally(function () { if (btn) btn.disabled = false; });
  }

  // GET /logs -> facc --logs --lines 100
  function doLogs() {
    return execCmd("su -c 'facc --logs --lines 100'").then(function (out) {
      $("logView").textContent = out || "(kosong)";
    }).catch(function () {
      $("logView").textContent = "[07:30:01] [INFO] FACC started\n[07:30:02] [SCAN] 47 packages detected\n[07:30:03] [CLEAN] com.android.chrome - 182 MB\n[07:30:04] [CLEAN] com.instagram.android - 421 MB\n[07:30:04] [INFO] Freed 603 MB\n\n(mode mock)";
    });
  }

  // GET /config + POST /config -> cat & echo ke /data/adb/facc/facc.conf
  function doLoadConfig() {
    return execCmd("su -c 'facc --config'").then(function (out) {
      $("cfgView").textContent = out;
    }).catch(function () {
      $("cfgView").textContent = "AUTO_CLEAN=1\nINTERVAL_MINUTES=30\n(mode mock)";
    });
  }

  function doSaveConfig() {
    var auto = $("cfgAuto").checked ? "1" : "0";
    var interval = parseInt($("cfgInterval").value, 10) || 30;
    if (interval < 5) interval = 5;
    if (interval > 1440) interval = 1440;
    var cmd = "su -c 'printf \"AUTO_CLEAN=" + auto + "\\nINTERVAL_MINUTES=" + interval + "\\n\" > /data/adb/facc/facc.conf && cat /data/adb/facc/facc.conf'";
    return execCmd(cmd).then(function (out) {
      $("cfgView").textContent = out;
      showAlert("Config disimpan. Scheduler baca ulang tiap menit.", true);
      doStatus();
    }).catch(function () {
      showAlert("Mode mock: config tidak benar-benar disimpan.", false);
    });
  }

  // ---------- Wire ----------
  $("btnScan").onclick = function () { doScan($("btnScan")); };
  $("btnClean").onclick = function () { doClean(null, $("btnClean")); };
  $("btnStatus").onclick = function () { doStatus(); };
  $("btnLogs").onclick = function () { doLogs(); };
  $("btnSaveCfg").onclick = function () { doSaveConfig(); };

  // Init
  updateBridgeHint(detectBridge());
  doStatus();
  doLoadConfig();
})();
