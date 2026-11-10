# Daily Alarm (Mac)

A small alarm app for macOS. Add the times you want it to ring each day (24-hour, e.g. `07:00`, `18:30`).
At each time, the alarm sound repeats every couple of seconds until you press **Stop Alarm**, or 3 minutes pass.

## Requirements
- macOS with a Java JDK (version 8 or newer)

## Run
```
javac DailyAlarm.java
java DailyAlarm
```

Keep the app open. Times are saved in `alarms.txt` next to the program, so they stay after you close it.
Use **Test Alarm** to check that the sound works.
