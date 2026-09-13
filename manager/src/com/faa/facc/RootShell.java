package com.faa.facc;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * RootShell — eksekusi perintah root via {@code su -c}.
 * Dipakai FACC Manager untuk memanggil CLI module:
 * {@code su -c "facc --scan --json"} dkk.
 */
public class RootShell {

    public static class Result {
        public String out = "";
        public String err = "";
        public int code = -1;
        public boolean timedOut = false;
    }

    /** Cek apakah perangkat punya akses root (su tersedia & granted). */
    public static boolean hasRoot() {
        try {
            Result r = exec("echo facc-root-ok", 15000);
            return r != null && r.code == 0 && r.out.contains("facc-root-ok");
        } catch (Exception e) {
            return false;
        }
    }

    /** Cek apakah CLI module FACC terpasang & bisa jalan. */
    public static boolean hasFacc() {
        try {
            Result r = exec("facc --version", 20000);
            return r != null && r.code == 0 && r.out.contains("FACC");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Jalankan perintah shell sebagai root.
     * Stream dibaca paralel (gobbler) selama proses jalan — anti-deadlock
     * walau output besar (JSON scan ratusan app), dan timeout tetap jalan.
     */
    public static Result exec(String cmd, int timeoutMs) {
        Result res = new Result();
        Process p = null;
        Thread tOut = null;
        Thread tErr = null;
        final StringBuilder sbOut = new StringBuilder();
        final StringBuilder sbErr = new StringBuilder();
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            final Process fp = p;
            tOut = drainThread(fp, false, sbOut);
            tErr = drainThread(fp, true, sbErr);
            tOut.start();
            tErr.start();
            long end = System.currentTimeMillis() + Math.max(timeoutMs, 1000);
            boolean done = false;
            while (System.currentTimeMillis() < end) {
                try {
                    fp.exitValue();
                    done = true;
                    break;
                } catch (IllegalThreadStateException itse) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            if (!done) {
                res.timedOut = true;
                try {
                    fp.destroy();
                } catch (Exception ignored) {
                }
            } else {
                res.code = fp.exitValue();
            }
            joinQuiet(tOut, 5000);
            joinQuiet(tErr, 5000);
            res.out = sbOut.toString().trim();
            res.err = sbErr.toString().trim();
        } catch (Exception e) {
            res.err = String.valueOf(e.getMessage());
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
        return res;
    }

    private static Thread drainThread(final Process p, final boolean error,
                                      final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(
                            error ? p.getErrorStream() : p.getInputStream(),
                            "UTF-8"));
                    char[] buf = new char[8192];
                    int n;
                    while ((n = br.read(buf)) != -1) {
                        synchronized (sb) {
                            sb.append(buf, 0, n);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (br != null) {
                        try {
                            br.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        });
        t.setDaemon(true);
        return t;
    }

    private static void joinQuiet(Thread t, long ms) {
        if (t == null) {
            return;
        }
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
