package com.cel.ssdac;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes SSDAC / HASSDAC / DKT Event Logger .dat file format.
 *
 * ─── Actual .dat Format (ASCII text-based) ─────────────────────────────────
 *
 *  Header:  #MM$ Downloading Start.. #MM$<date> | <time>}
 *
 *  Records: {DD:MM:YYYY | HH:MM:SS}%SXXXB:UYYYYYYY<cs><cs>$
 *
 *  Where:
 *   {DD:MM:YYYY | HH:MM:SS} = timestamp block
 *   %S                       = packet marker
 *   XXX                      = Zone/Reader ID (100, 200, 300)
 *   B                        = Status byte:
 *                               '2' (0x32) = ENTRY / Swipe-IN
 *                               ';' (0x3B) = EXIT  / Swipe-OUT
 *                               '?' (0x3F) = INVALID / Access-Denied
 *                               ':' (0x3A) = TIMEOUT / Door-Open
 *                               '3','!',' '= Other/Error states
 *   :U                       = UID field separator + marker
 *   YYYYYYY                  = Card UID (ASCII hex, typically 11 chars)
 *   <cs><cs>                 = 2 raw checksum bytes (may be non-printable)
 *   $                        = end of packet (0x24)
 *
 *  Multiple %S records can follow the same timestamp on the same line.
 *  Null bytes (0x00) = erased/empty flash sections between data blocks.
 *
 * ─── Also handles raw binary format (original SSDAC protocol) ──────────────
 *  Sync: 0x55 0xAA, then packet with CRC-16 CCITT verification.
 */
public class DataParser {

    // ── Text-format patterns ──────────────────────────────────────────────────
    // Timestamp: {31:10:1092 | 21:20:23} or {01:11:1092 |00:05:23}
    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\{(\\d{2}):(\\d{2}):(\\d{4})\\s*\\|\\s*(\\d{2}):(\\d{2}):(\\d{2})\\}"
    );
    // %S record: %S100;:U422FF2D00E0 (captures zone, status-char, uid-hex)
    private static final Pattern S_PATTERN = Pattern.compile(
            "%S(\\d+)(.):U([0-9A-Fa-f]+)"
    );

    // ── Binary format constants ───────────────────────────────────────────────
    private static final int SYNC1 = 0x55;
    private static final int SYNC2 = 0xAA;
    private static final int RECORD_SIZE = 16;

    // ── Public result ─────────────────────────────────────────────────────────
    public static class ParseResult {
        public final List<String> lines;   // formatted output lines
        public final int totalRecords;
        public final int uniqueCards;
        public final String summary;

        ParseResult(List<String> l, int rec, int cards, String sum) {
            lines = l; totalRecords = rec; uniqueCards = cards; summary = sum;
        }
    }

    /** Main entry — auto-detects text vs binary format */
    public static ParseResult parse(byte[] raw) {
        if (raw == null || raw.length == 0)
            return new ParseResult(list("(No data received)"), 0, 0, "Empty");

        // Detect format: if starts with printable ASCII / #MM$ → text format
        boolean isText = (raw[0] >= 0x20 && raw[0] < 0x7F);
        if (isText) return parseTextFormat(raw);
        else        return parseBinaryFormat(raw);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEXT FORMAT (actual SSDAC/DKT .dat)
    // ══════════════════════════════════════════════════════════════════════════

    private static ParseResult parseTextFormat(byte[] raw) {
        String text = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
        List<String> out = new ArrayList<>();
        java.util.Set<String> uniqueUids = new java.util.LinkedHashSet<>();
        int totalRec = 0;

        // Header
        out.add("SSDAC / HASSDAC / DKT Event Logger Data");
        out.add(pad("─", 80));

        // Column header
        out.add(String.format("%-4s | %-12s %-8s | %-6s | %-9s | %-15s",
                "No.", "Date", "Time", "Zone", "Status", "Card UID"));
        out.add(pad("─", 80));

        // Find all timestamp blocks and their %S records
        Matcher ts = TS_PATTERN.matcher(text);
        int srNo = 1;

        while (ts.find()) {
            String dd   = ts.group(1);
            String mo   = ts.group(2);
            String yyyy = ts.group(3);
            String hh   = ts.group(4);
            String mi   = ts.group(5);
            String ss   = ts.group(6);

            // The %S records immediately follow the } up to the next { or \n
            int end = text.indexOf('{', ts.end());
            String block = (end == -1) ? text.substring(ts.end()) : text.substring(ts.end(), end);

            Matcher sm = S_PATTERN.matcher(block);
            while (sm.find()) {
                String zone   = sm.group(1);
                String stChar = sm.group(2);
                String uid    = sm.group(3);

                uniqueUids.add(uid);
                totalRec++;

                out.add(String.format("%-4d | %s/%s/%s %s:%s:%s | %-6s | %-9s | %s",
                        srNo++,
                        dd, mo, yyyy, hh, mi, ss,
                        "Zn" + zone,
                        decodeStatus(stChar),
                        formatUid(uid)));
            }
        }

        if (totalRec == 0) {
            out.add("(No structured records found in data)");
            // Fallback: show raw text printable lines
            for (String line : text.split("[\\r\\n]+")) {
                String clean = line.replaceAll("[^\\x20-\\x7E]", "·").trim();
                if (!clean.isEmpty()) out.add(clean);
            }
        }

        out.add(pad("─", 80));
        out.add("Total Records : " + totalRec);
        out.add("Unique Cards  : " + uniqueUids.size());

        String sum = totalRec + " event records, " + uniqueUids.size() + " unique cards";
        return new ParseResult(out, totalRec, uniqueUids.size(), sum);
    }

    /** Decode the status character byte into a human-readable label */
    private static String decodeStatus(String stChar) {
        if (stChar == null || stChar.isEmpty()) return "UNKNOWN";
        switch (stChar.charAt(0)) {
            case '2': return "ENTRY";      // 0x32 — swipe-in
            case ';': return "EXIT";       // 0x3B — swipe-out
            case '?': return "INVALID";    // 0x3F — denied / bad card
            case ':': return "TIMEOUT";    // 0x3A — door timeout / forced
            case '3': return "RE-ENTRY";   // 0x33
            case '!': return "ALARM";      // 0x21
            default:  return String.format("ST_%02X", (int) stChar.charAt(0));
        }
    }

    /** Format UID for display — group into readable hex pairs */
    private static String formatUid(String uid) {
        if (uid == null || uid.isEmpty()) return "(none)";
        // Pad to even length
        if (uid.length() % 2 != 0) uid = "0" + uid;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < uid.length(); i += 2) {
            if (sb.length() > 0) sb.append(':');
            sb.append(uid.substring(i, Math.min(i + 2, uid.length())).toUpperCase());
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BINARY FORMAT (original SSDAC serial download protocol)
    // ══════════════════════════════════════════════════════════════════════════

    private static ParseResult parseBinaryFormat(byte[] raw) {
        List<String> out = new ArrayList<>();
        List<BinRecord> recs = extractPacketRecords(raw);
        if (recs.isEmpty()) recs = scanForRecords(raw);

        if (!recs.isEmpty()) {
            out.add(String.format("%-5s | %-10s | %-8s | %-14s | %-6s | %s",
                    "Sr.", "Date", "Time", "Event Status", "Ch", "HEX Data"));
            out.add(pad("─", 70));
            int n = 1;
            for (BinRecord r : recs) out.add(r.toRow(n++));
        } else {
            out.add("--- RAW HEX DUMP (" + raw.length + " bytes) ---");
            out.add("(No structured records — showing hex dump)");
            out.add(pad("─", 60));
            out.addAll(hexDump(raw));
        }

        String sum = recs.isEmpty()
                ? "Raw dump: " + raw.length + " bytes"
                : recs.size() + " binary event records";
        return new ParseResult(out, recs.size(), 0, sum);
    }

    private static List<BinRecord> extractPacketRecords(byte[] data) {
        List<BinRecord> recs = new ArrayList<>();
        int i = 0;
        while (i < data.length - 4) {
            if ((data[i] & 0xFF) == SYNC1 && (data[i + 1] & 0xFF) == SYNC2) {
                int len = data[i + 2] & 0xFF;
                if (len > 0 && i + 3 + len + 2 <= data.length) {
                    byte[] pkt = new byte[len];
                    System.arraycopy(data, i + 3, pkt, 0, len);
                    int crcCalc = crc16(pkt, 0, len);
                    int crcRecv = ((data[i + 3 + len] & 0xFF) << 8) | (data[i + 4 + len] & 0xFF);
                    if (crcCalc == crcRecv) {
                        for (int j = 0; j + RECORD_SIZE <= len; j += RECORD_SIZE) {
                            BinRecord r = tryParseBin(pkt, j);
                            if (r != null) recs.add(r);
                        }
                        i += 3 + len + 2; continue;
                    }
                }
            }
            i++;
        }
        return recs;
    }

    private static List<BinRecord> scanForRecords(byte[] data) {
        List<BinRecord> recs = new ArrayList<>();
        int i = 0;
        while (i < data.length - RECORD_SIZE) {
            BinRecord r = tryParseBin(data, i);
            if (r != null) { recs.add(r); i += RECORD_SIZE; }
            else i++;
        }
        return recs;
    }

    private static BinRecord tryParseBin(byte[] d, int off) {
        if (off + RECORD_SIZE > d.length) return null;
        try {
            int day = d[off + 1] & 0xFF, mo = d[off + 2] & 0xFF;
            int yr = ((d[off + 3] & 0xFF) << 8) | (d[off + 4] & 0xFF);
            int hh = d[off + 5] & 0xFF, mi = d[off + 6] & 0xFF, ss = d[off + 7] & 0xFF;
            int evSt = d[off + 8] & 0xFF, ch = d[off + 9] & 0xFF;
            // BCD decode
            if (isBcd(day) && isBcd(mo)) {
                day = bcdToDec(day); mo = bcdToDec(mo);
                hh = bcdToDec(hh);  mi = bcdToDec(mi); ss = bcdToDec(ss);
            }
            if (day < 1 || day > 31 || mo < 1 || mo > 12) return null;
            if (yr < 2000 || yr > 2099) return null;
            if (hh > 23 || mi > 59 || ss > 59) return null;
            if ((d[off] & 0xFF) == 0xFF) return null;
            byte[] ex = new byte[6];
            System.arraycopy(d, off + 10, ex, 0, 6);
            return new BinRecord(day, mo, yr, hh, mi, ss, evSt, ch, ex);
        } catch (Exception e) { return null; }
    }

    private static class BinRecord {
        final int day, mo, yr, hh, mi, ss, evSt, ch;
        final byte[] ex;
        BinRecord(int day, int mo, int yr, int hh, int mi, int ss, int evSt, int ch, byte[] ex) {
            this.day=day; this.mo=mo; this.yr=yr; this.hh=hh; this.mi=mi;
            this.ss=ss; this.evSt=evSt; this.ch=ch; this.ex=ex;
        }
        String toRow(int n) {
            return String.format(Locale.US, "%-5d | %02d/%02d/%04d | %02d:%02d:%02d | %-14s | Ch%-4d| %s",
                    n, day, mo, yr, hh, mi, ss, binStatus(evSt), ch, toHex(ex));
        }
        private static String binStatus(int s) {
            switch (s & 0xF0) {
                case 0x00: return "INIT";
                case 0x10: return "INPUT_ON";
                case 0x20: return "INPUT_OFF";
                case 0x30: return "OUTPUT_ON";
                case 0x40: return "OUTPUT_OFF";
                case 0x50: return "ALARM";
                case 0x60: return "POWER_ON";
                case 0x70: return "POWER_OFF";
                case 0x80: return "RESET";
                case 0xF0: return (s == 0xFF) ? "EMPTY" : "FAULT";
                default:   return String.format("ST_%02X", s);
            }
        }
        private static String toHex(byte[] b) {
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02X", x));
            return sb.toString();
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static int crc16(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) crc = ((crc & 0x8000) != 0) ? (crc << 1) ^ 0x1021 : crc << 1;
        }
        return crc & 0xFFFF;
    }

    private static boolean isBcd(int b) { return ((b >> 4) <= 9) && ((b & 0xF) <= 9); }
    private static int bcdToDec(int b)   { return ((b >> 4) * 10) + (b & 0xF); }

    static List<String> hexDump(byte[] data) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < data.length; i += 16) {
            StringBuilder hex = new StringBuilder(), asc = new StringBuilder();
            for (int j = 0; j < 16 && (i + j) < data.length; j++) {
                int b = data[i + j] & 0xFF;
                hex.append(String.format("%02X ", b));
                asc.append((b >= 0x20 && b < 0x7F) ? (char) b : '.');
            }
            lines.add(String.format(Locale.US, "%06X:  %-48s  %s", i, hex, asc));
        }
        return lines;
    }

    private static String pad(String c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static List<String> list(String s) {
        List<String> l = new ArrayList<>(); l.add(s); return l;
    }
}
