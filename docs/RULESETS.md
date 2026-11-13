# Built-in rule sets

`zapret-ru-domains.json` is a small source list of Russian domain zones and individual
sites that must go through `direct`. The binary `.srs` is built by the exact audit CLI
from `core.properties`.

`zapret-ru-ip.srs` comes from the pinned snapshot `SagerNet/sing-geoip@66c864fcfe1535549e0658569cb514dbd35756ba`; the expected SHA-256 is `55869c680cf6489991cbfdf62c2f5da77ea85bf571babddb6cd9c4941d84cbfa`.

Both files are delivered only together with the APK. There is no runtime download of the sets: they are updated only together with the APK (the background check and download of the APK itself are described in ARCHITECTURE.md).

## The "IP → country" table (server flags)

`app/src/main/assets/geoip/country.bin` is the offline DB-IP "IP to Country Lite" table (CC BY 4.0, "IP Geolocation by DB-IP", <https://db-ip.com>). The app uses it to find the country by the address of the server itself, to show a flag when the name has none. The source, the month, the SHA-256 and the sizes are in `app/src/main/assets/geoip/manifest.json`; the file format is described at the top of `scripts/build-geoip-country.py`.

It is updated by hand before a release, not in the app:

```bash
python3 -I scripts/build-geoip-country.py          # the current month, the previous one if it is missing
python3 -I scripts/build-geoip-country.py --check  # the file matches manifest.json
bash scripts/verify-project.sh                     # checks the hash, the size (up to 3 MiB) and the attribution
```

After an update, fix the month in `docs/THIRD_PARTY_NOTICES.md` and copy it to `app/src/main/res/raw/third_party_notices.txt` (the files must be byte-for-byte equal). The table is never downloaded while the app runs.
