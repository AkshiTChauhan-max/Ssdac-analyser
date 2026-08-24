# SSDAC / HASSDAC Event Logger Analyzer — Android App

Android USB Serial analyzer for SSDAC and HASSDAC Event Logger Cards.

---

## Features

| Feature | Detail |
|---------|--------|
| USB Serial Connection | CH340, FTDI FT232, PL2303, CP210x, CDC ACM |
| Baud Rate | 2400 / 9600 / 19200 / 38400 / 57600 / **115200** (default) + Custom |
| Download Data | Sends `%D$`, receives all flash data, decodes records |
| Get RTC Time | Sends `%T$`, displays current device time |
| Set RTC Time | Sends `%ddDDMMYYYYhhmmss$` with date/time picker |
| Erase Flash | Sends `%C$` (with confirmation dialog) |
| Save Output | Decoded data saved as `.txt` in Downloads folder |
| Data Decode | Parses structured records: date, time, event status, channel |
| CRC Check | CRC-16 CCITT verification on received packets |

---

## Protocol (from SSDAC User Manual)

| Command | Byte String | Description |
|---------|-------------|-------------|
| Download | `%D$` | Download data from flash |
| Get RTC  | `%T$` | Read real-time clock |
| Set RTC  | `%ddDDMMYYYYhhmmss$` | Set clock (dd=day-of-week HEX) |
| Erase    | `%C$` | Erase all flash data |

Serial: **115200 baud, 8 data bits, None parity, 1 stop bit**

---

## Build Instructions (Android Studio)

### Requirements
- Android Studio Hedgehog or later
- Android SDK API 34
- Java 8
- Phone with **USB OTG** support

### Steps
1. Unzip `SSDAC_Analyzer_Android.zip`
2. Open Android Studio → **Open Project** → select extracted folder
3. Wait for Gradle sync
4. Connect your Android phone via USB to PC
5. Click **Run ▶** (or `Build → Generate Signed APK` for distribution)

### Direct APK Build (command line)
```bash
cd SSDAC_Analyzer
./gradlew assembleDebug
# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

---

## Usage

1. Connect Event Logger Card to Android via USB OTG cable
2. Open app → tap **CONNECT**
3. Grant USB permission when prompted
4. Use command buttons:
   - **DOWNLOAD DATA** → downloads all records → saves `.txt` to Downloads
   - **GET RTC TIME** → reads current device clock
   - **SET RTC TIME** → opens date/time picker → sends to device
   - **ERASE FLASH** → deletes all data (asks for confirmation)

---

## Output File Format

```
SSDAC / HASSDAC Event Logger Data
Downloaded : 25/08/2026 10:30:00
Summary    : 142 event records, 2272 total bytes
Total Bytes: 2272
Records    : 142
========================================================================

Sr.No. | Date       | Time     | Event Status  | Channel | Data(HEX)
-----------------------------------------------------------------------
1      | 01/08/2026 | 08:00:00 | POWER_ON       | Ch0     | 600000000000
2      | 01/08/2026 | 08:00:05 | INPUT_ON       | Ch1     | 100100000000
3      | 01/08/2026 | 09:30:12 | INPUT_OFF      | Ch1     | 200100000000
...
```

---

## Troubleshooting

| Problem | Solution |
|---------|---------|
| "No USB Serial device found" | Check OTG cable, try another USB Serial adapter |
| Wrong data / garbage | Try different baud rate (38400 or 115200) |
| Permission denied | Unplug/replug USB, grant permission again |
| No records decoded | Data shown as hex dump — share the .txt file for analysis |
| File not saved | Grant storage permission in Android Settings |

---

## Supported Devices

Works with SSDAC and HASSDAC Event Logger Cards manufactured by **CEL** (Compact Electronic Loggers).
MSADC cards use a different protocol — see separate MSADC version.
