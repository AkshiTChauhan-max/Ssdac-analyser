package com.cel.ssdac;

import java.util.Calendar;
import java.util.Locale;

/**
 * SSDAC / HASSDAC Event Logger Card Protocol
 *
 * Commands (from official SSDAC User Manual):
 *   Download Data : %D$
 *   Get RTC       : %T$
 *   Set RTC       : %ddDDMMYYYYhhmmss$  (dd = day-of-week in HEX, rest in decimal)
 *   Erase Flash   : %C$
 *
 * Serial: 115200 baud, 8 data bits, None parity, 1 stop bit (default)
 */
public class SsdacProtocol {

    // ----- ASCII Commands -----
    public static final byte[] CMD_DOWNLOAD = "%D$".getBytes();
    public static final byte[] CMD_GET_RTC  = "%T$".getBytes();
    public static final byte[] CMD_ERASE    = "%C$".getBytes();

    // Day-of-week mapping (Calendar.DAY_OF_WEEK -> hex nibble used by device)
    // Calendar: 1=Sun,2=Mon,...,7=Sat
    private static final int[] DOW_MAP = {0, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06};

    /**
     * Build Set-RTC command.
     * Format: %ddDDMMYYYYhhmmss$
     * where dd = day-of-week as 2-digit HEX, rest are decimal digits.
     */
    public static byte[] buildSetRtcCommand(Calendar c) {
        int dow = DOW_MAP[c.get(Calendar.DAY_OF_WEEK)];
        String cmd = String.format(Locale.US, "%%%02X%02d%02d%04d%02d%02d%02d$",
                dow,
                c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.MONTH) + 1,
                c.get(Calendar.YEAR),
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
                c.get(Calendar.SECOND));
        return cmd.getBytes();
    }

    /**
     * Parse RTC response bytes into readable string.
     * Device echoes something like: %T<DD><MM><YYYY><HH><mm><ss>$
     */
    public static String parseRtcResponse(byte[] raw) {
        if (raw == null || raw.length < 4) return "(empty)";
        try {
            String s = new String(raw).trim();
            // Strip % and $ markers
            s = s.replace("%", "").replace("T", "").replace("$", "").trim();
            if (s.length() >= 14) {
                String dd   = s.substring(0, 2);
                String mm   = s.substring(2, 4);
                String yyyy = s.substring(4, 8);
                String hh   = s.substring(8, 10);
                String mi   = s.substring(10, 12);
                String ss   = s.substring(12, 14);
                return dd + "/" + mm + "/" + yyyy + "  " + hh + ":" + mi + ":" + ss;
            }
            return s;
        } catch (Exception e) {
            return bytesToHex(raw);
        }
    }

    public static String bytesToHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    // Default baud rates
    public static final int[] BAUD_RATES = {2400, 9600, 19200, 38400, 57600, 115200};
    public static final int DEFAULT_BAUD = 115200;
}
