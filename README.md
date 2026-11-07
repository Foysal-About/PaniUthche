# Pani Uthche (পানি উঠছে)

Android flood early-warning app for Bangladesh, in English and Bangla.

- **Forecast:** 7-day river discharge for pilot districts (Sylhet, Sunamganj, Kurigram, Feni, Sirajganj) from the [Open-Meteo Flood API](https://open-meteo.com/en/docs/flood-api). Risk is LOW/MEDIUM/HIGH: the 7-day forecast peak compared with the median flow of the past 90 days.
- **SOS mesh:** sends help requests (people count, elderly, children, pregnant, note) phone to phone over Bluetooth and Wi-Fi with Google Nearby Connections. It works without internet. Messages are stored and forwarded up to 5 hops.
- **ML pipeline (`ml/`):** a geospatial flood-probability model trained on Sentinel-1 flood maps and scored with GFS rain forecasts. See [`ml/README.md`](ml/README.md).

## Build

Requires Android SDK 37 and JDK 11+.

```sh
./gradlew assembleDebug   # APK in app/build/outputs/apk/debug/
./gradlew test            # unit tests (risk level, SOS relay)
```

## Limits

- Prototype. Not an official warning; FFWC and BMD remain the authority.
- Risk uses a recent-flow baseline, not historical return periods.
- SOS messages live in memory only, and connections are accepted without authentication or message signing.
