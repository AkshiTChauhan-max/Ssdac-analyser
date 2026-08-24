package com.cel.ssdac;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.*;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity
        implements SerialInputOutputManager.Listener {

    private static final String ACTION_USB_PERMISSION = "com.cel.ssdac.USB_PERMISSION";
    private static final int PERM_STORAGE  = 1001;
    private static final int REQ_OPEN_FILE = 1002;
    private static final int DOWNLOAD_TIMEOUT_MS = 3000;

    // USB
    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private SerialInputOutputManager ioManager;

    // Download
    private final List<Byte> rawBuffer = new ArrayList<>();
    private boolean isDownloading = false;
    private long lastByteTime = 0;
    private int customBaud = 38400;

    // UI
    private TextView tvStatus, tvLog;
    private Button btnConnect, btnDownload, btnGetTime, btnSetTime, btnErase, btnOpenFile;
    private Spinner spBaudRate;
    private ProgressBar pbDownload;
    private TextView tvProgress;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ──────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("SSDAC Analyzer v1.0");
            getSupportActionBar().setSubtitle("by Akshit Chauhan");
        }
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        bindViews();
        setupBaudSpinner();
        registerUsbReceiver();
        requestStoragePermission();
        setCommandsEnabled(false);
        log("SSDAC / HASSDAC Event Logger Analyzer  v1.0");
        log("Developed by: Akshit Chauhan");
        log(pad("─", 48));
        log("Plug USB OTG cable and press CONNECT");
        log("or tap OPEN .DAT FILE to analyze saved data");
    }

    // ════════════════ MENU ════════════════
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "About").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ════════════════ UI SETUP ════════════════
    private void bindViews() {
        tvStatus    = findViewById(R.id.tv_status);
        tvLog       = findViewById(R.id.tv_log);
        pbDownload  = findViewById(R.id.pb_download);
        tvProgress  = findViewById(R.id.tv_progress);
        btnConnect  = findViewById(R.id.btn_connect);
        btnDownload = findViewById(R.id.btn_download);
        btnGetTime  = findViewById(R.id.btn_get_time);
        btnSetTime  = findViewById(R.id.btn_set_time);
        btnErase    = findViewById(R.id.btn_erase);
        btnOpenFile = findViewById(R.id.btn_open_file);
        spBaudRate  = findViewById(R.id.sp_baud_rate);

        tvLog.setMovementMethod(new ScrollingMovementMethod());

        btnConnect.setOnClickListener(v -> toggleConnect());
        btnDownload.setOnClickListener(v -> cmdDownload());
        btnGetTime.setOnClickListener(v -> cmdGetRtc());
        btnSetTime.setOnClickListener(v -> showSetTimeDialog());
        btnErase.setOnClickListener(v -> confirmErase());
        btnOpenFile.setOnClickListener(v -> openDatFile());
    }

    private void setupBaudSpinner() {
        int[] bauds = SsdacProtocol.BAUD_RATES;
        String[] labels = new String[bauds.length + 1];
        for (int i = 0; i < bauds.length; i++) labels[i] = String.valueOf(bauds[i]);
        labels[bauds.length] = "Custom";
        ArrayAdapter<String> adp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBaudRate.setAdapter(adp);
        spBaudRate.setSelection(5); // 115200 default

        spBaudRate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == SsdacProtocol.BAUD_RATES.length) showCustomBaudDialog();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    // ════════════════ FILE OPEN (.dat) ════════════════
    private void openDatFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Select .dat file"), REQ_OPEN_FILE);
        } catch (Exception e) {
            log("File picker not available: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN_FILE && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            String name = getFileName(uri);
            log(pad("─", 48));
            log("Opening file: " + name);
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    InputStream is = getContentResolver().openInputStream(uri);
                    byte[] raw = is.readAllBytes();
                    is.close();
                    handler.post(() -> log("File size: " + raw.length + " bytes — decoding..."));
                    processData(raw, name);
                } catch (Exception e) {
                    handler.post(() -> log("File read error: " + e.getMessage()));
                }
            });
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = c.getString(idx);
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result != null ? result : "unknown.dat";
    }

    // ════════════════ SERIAL CONNECTION ════════════════
    private void toggleConnect() {
        if (serialPort != null) disconnectSerial();
        else connectSerial();
    }

    private void connectSerial() {
        List<UsbSerialDriver> drivers =
                UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            log("❌ No USB Serial device found. Check OTG cable.");
            setStatus("No device"); return;
        }
        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();
        log("Found: " + device.getProductName() + "  VID=" +
                Integer.toHexString(device.getVendorId()).toUpperCase());
        if (!usbManager.hasPermission(device)) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(device, pi);
            log("Requesting USB permission…");
        } else {
            openPort(driver);
        }
    }

    private void openPort(UsbSerialDriver driver) {
        try {
            UsbDeviceConnection conn = usbManager.openDevice(driver.getDevice());
            if (conn == null) { log("❌ Cannot open device"); return; }
            serialPort = driver.getPorts().get(0);
            serialPort.open(conn);
            int baud = getSelectedBaud();
            serialPort.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            ioManager = new SerialInputOutputManager(serialPort, this);
            Executors.newSingleThreadExecutor().submit(ioManager);
            setStatus("✅ Connected  " + baud + " baud");
            btnConnect.setText("DISCONNECT");
            setCommandsEnabled(true);
            log("✅ Connected @ " + baud + " baud, 8N1");
        } catch (Exception e) {
            log("❌ Open failed: " + e.getMessage());
            serialPort = null;
        }
    }

    private void disconnectSerial() {
        if (ioManager != null) { ioManager.stop(); ioManager = null; }
        if (serialPort != null) {
            try { serialPort.close(); } catch (Exception ignored) {}
            serialPort = null;
        }
        setStatus("🔌 Disconnected");
        btnConnect.setText("CONNECT");
        setCommandsEnabled(false);
        log("Disconnected.");
    }

    private int getSelectedBaud() {
        int pos = spBaudRate.getSelectedItemPosition();
        return pos < SsdacProtocol.BAUD_RATES.length ? SsdacProtocol.BAUD_RATES[pos] : customBaud;
    }

    private void sendBytes(byte[] data) {
        if (serialPort == null) { log("⚠ Not connected"); return; }
        try {
            serialPort.write(data, 500);
            log("→ Sent: " + new String(data).trim());
        } catch (Exception e) { log("❌ Send: " + e.getMessage()); }
    }

    // ════════════════ COMMANDS ════════════════
    private void cmdDownload() {
        rawBuffer.clear();
        isDownloading = true;
        lastByteTime = System.currentTimeMillis();
        pbDownload.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        tvProgress.setText("Downloading…");
        log(pad("─", 48));
        log("Sending: Download Data  %D$");
        sendBytes(SsdacProtocol.CMD_DOWNLOAD);
        handler.postDelayed(downloadWatchdog, 1000);
    }

    private final Runnable downloadWatchdog = new Runnable() {
        @Override public void run() {
            long elapsed = System.currentTimeMillis() - lastByteTime;
            tvProgress.setText("Received: " + rawBuffer.size() + " bytes");
            if (isDownloading && elapsed > DOWNLOAD_TIMEOUT_MS) {
                isDownloading = false;
                pbDownload.setVisibility(View.GONE);
                tvProgress.setVisibility(View.GONE);
                byte[] raw = toByteArray(rawBuffer);
                processData(raw, "download");
            } else if (isDownloading) {
                handler.postDelayed(this, 500);
            }
        }
    };

    private void processData(byte[] raw, String source) {
        if (raw.length == 0) { handler.post(() -> log("No data received.")); return; }
        handler.post(() -> log("Decoding " + raw.length + " bytes…"));
        Executors.newSingleThreadExecutor().execute(() -> {
            DataParser.ParseResult result = DataParser.parse(raw);
            String fileContent = buildFileContent(result, source);
            String savedPath = saveToFile(fileContent, source);
            handler.post(() -> {
                log(pad("─", 48));
                log("✅ " + result.summary);
                // Show first 30 lines in log
                List<String> lines = result.lines;
                for (int i = 0; i < Math.min(lines.size(), 30); i++) log(lines.get(i));
                if (lines.size() > 30) log("  …(" + (lines.size() - 30) + " more lines in file)");
                if (savedPath != null) log("💾 Saved: " + savedPath);
                log(pad("─", 48));
            });
        });
    }

    private void cmdGetRtc() {
        log(pad("─", 48));
        log("Sending: Get RTC  %T$");
        sendBytes(SsdacProtocol.CMD_GET_RTC);
    }

    private void showSetTimeDialog() {
        Calendar now = Calendar.getInstance();
        View v = getLayoutInflater().inflate(R.layout.dialog_set_time, null);
        TimePicker tp = v.findViewById(R.id.time_picker);
        DatePicker dp = v.findViewById(R.id.date_picker);
        tp.setIs24HourView(true);
        tp.setHour(now.get(Calendar.HOUR_OF_DAY));
        tp.setMinute(now.get(Calendar.MINUTE));
        dp.updateDate(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
        new AlertDialog.Builder(this)
                .setTitle("Set RTC Date & Time")
                .setView(v)
                .setPositiveButton("Set", (d, w) -> {
                    Calendar c = Calendar.getInstance();
                    c.set(dp.getYear(), dp.getMonth(), dp.getDayOfMonth(),
                            tp.getHour(), tp.getMinute(), 0);
                    cmdSetRtc(c);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void cmdSetRtc(Calendar c) {
        byte[] cmd = SsdacProtocol.buildSetRtcCommand(c);
        String ts = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(c.getTime());
        log("Sending: Set RTC → " + ts);
        sendBytes(cmd);
    }

    private void confirmErase() {
        new AlertDialog.Builder(this)
                .setTitle("⚠ Erase Flash")
                .setMessage("This will DELETE ALL data on the device.\nAre you sure?")
                .setPositiveButton("ERASE", (d, w) -> {
                    log(pad("─", 48));
                    log("Sending: Erase Flash  %C$");
                    sendBytes(SsdacProtocol.CMD_ERASE);
                    log("Erase sent — wait for device confirmation.");
                })
                .setNegativeButton("Cancel", null)
                .setIcon(android.R.drawable.ic_dialog_alert).show();
    }

    // ════════════════ SERIAL CALLBACKS ════════════════
    @Override
    public void onNewData(byte[] data) {
        lastByteTime = System.currentTimeMillis();
        if (isDownloading) {
            for (byte b : data) rawBuffer.add(b);
            return;
        }
        String resp = new String(data).trim();
        if (resp.contains("T") && resp.contains("$")) {
            String time = SsdacProtocol.parseRtcResponse(data);
            handler.post(() -> log("← RTC: " + time));
        } else if (!resp.isEmpty()) {
            handler.post(() -> log("← " + SsdacProtocol.bytesToHex(data) +
                    "  [" + resp.replaceAll("[^\\x20-\\x7E]", ".") + "]"));
        }
    }

    @Override
    public void onRunError(Exception e) {
        handler.post(() -> {
            log("❌ Serial error: " + e.getMessage());
            disconnectSerial();
        });
    }

    // ════════════════ FILE SAVE ════════════════
    private String buildFileContent(DataParser.ParseResult result, String source) {
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        sb.append("SSDAC / HASSDAC Event Logger Data\n");
        sb.append("Developed by : Akshit Chauhan  (SSDAC Analyzer v1.0)\n");
        sb.append("Source       : ").append(source).append("\n");
        sb.append("Generated    : ").append(sdf.format(new Date())).append("\n");
        sb.append("Summary      : ").append(result.summary).append("\n");
        sb.append(pad("═", 80)).append("\n\n");
        for (String line : result.lines) sb.append(line).append("\n");
        return sb.toString();
    }

    private String saveToFile(String content, String hint) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String base = hint.replaceAll("[^A-Za-z0-9_]", "_");
            File f = new File(dir, "SSDAC_" + base + "_" + ts + ".txt");
            try (FileWriter fw = new FileWriter(f)) { fw.write(content); }
            return f.getAbsolutePath();
        } catch (Exception e) {
            handler.post(() -> log("Save error: " + e.getMessage()));
            return null;
        }
    }

    // ════════════════ USB RECEIVER ════════════════
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    List<UsbSerialDriver> d = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
                    if (!d.isEmpty()) openPort(d.get(0));
                } else {
                    log("❌ USB permission denied");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                log("USB detached");
                disconnectSerial();
            }
        }
    };

    private void registerUsbReceiver() {
    IntentFilter f = new IntentFilter();
    f.addAction(ACTION_USB_PERMISSION);
    f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
    } else {
        registerReceiver(usbReceiver, f);
    }
}
    }

    // ════════════════ HELPERS ════════════════
    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE}, PERM_STORAGE);
        }
    }

    private void showCustomBaudDialog() {
        EditText et = new EditText(this);
        et.setText(String.valueOf(customBaud));
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
                .setTitle("Custom Baud Rate")
                .setView(et)
                .setPositiveButton("OK", (d, w) -> {
                    try { customBaud = Integer.parseInt(et.getText().toString()); }
                    catch (NumberFormatException e) { customBaud = 38400; }
                    log("Custom baud: " + customBaud);
                })
                .setNegativeButton("Cancel", (d, w) -> spBaudRate.setSelection(5)).show();
    }

    private void setStatus(String s)  { handler.post(() -> tvStatus.setText(s)); }

    private void log(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        handler.post(() -> {
            tvLog.append("[" + ts + "] " + msg + "\n");
            int scroll = tvLog.getLayout() == null ? 0
                    : tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight();
            if (scroll > 0) tvLog.scrollTo(0, scroll);
        });
    }

    private void setCommandsEnabled(boolean en) {
        handler.post(() -> {
            btnDownload.setEnabled(en);
            btnGetTime.setEnabled(en);
            btnSetTime.setEnabled(en);
            btnErase.setEnabled(en);
        });
    }

    private static byte[] toByteArray(List<Byte> list) {
        byte[] arr = new byte[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    private static String pad(String c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectSerial();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
    }
}
