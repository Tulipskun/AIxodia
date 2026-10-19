# Signing

Every published APK is signed with one stable key, so a new build installs
**over** the app already on a phone and keeps its Room cache and settings. Sign
with anything else and Android refuses the update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`)
until the app is uninstalled — which loses local history.

## Where the key lives

`app/aixodia-debug.keystore` is gitignored; it is **not** in this repository. The
single copy is the private repo [`Tulipskun/aixodia-keys`](https://github.com/Tulipskun/aixodia-keys):

```bash
# writes app/aixodia-debug.keystore and checks its md5
git clone https://github.com/Tulipskun/aixodia-keys /tmp/aixodia-keys
/tmp/aixodia-keys/fetch.sh /path/to/AIxodia
```

CI decodes the same file from the `AIXODIA_KEYSTORE_B64` repository secret
(`app/build.gradle.kts` falls back to the default debug key when neither is
present, which produces an APK that cannot install over a release):

```bash
gh secret set AIXODIA_KEYSTORE_B64 --repo Tulipskun/AIxodia \
  < /tmp/aixodia-keys/aixodia-debug.keystore.b64
```

## Key facts

| | |
|---|---|
| File | `aixodia-debug.keystore` (PKCS12, 4246 bytes) |
| Alias | `aixodia-debug` |
| Store / key password | `aixodiadebug` |
| Created | 2026-09-25 12:22:23 UTC (rotated when this repo went public) |
| Subject / Issuer | `CN=AIxodia` |
| Serial | `eedc3a3bc7b88ee8` |
| SHA-1 | `A5:5B:56:94:85:81:23:4D:83:58:86:C1:E7:90:98:95:FA:C3:2B:41` |
| md5 | `30b3cd5702f8bac4139efeb414cd20c8` |

The key used before 2026-09-25 was published in this repository and is dead: no
phone has an APK signed with it that we still update.

## Check before releasing

```bash
keytool -list -v -keystore app/aixodia-debug.keystore -storepass aixodiadebug | grep SHA1
# A5:5B:56:94:85:81:23:4D:83:58:86:C1:E7:90:98:95:FA:C3:2B:41

apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# the same digest, or the APK cannot be installed as an update
```

`scripts/release.sh` refuses to run when the keystore is missing, so a release
built here is always signed with the stable key.

## History

- 2026-10-19: the key was recovered from the engine machine's Hugging Face
  environment snapshot (`kyomu53n/kgat-snapshot-a`, `env_backup.part26.tar.zst`,
  path `root/AIxodia/app/aixodia-debug.keystore`) and moved into the private
  `aixodia-keys` repo. Certificate creation time (12:22:23 UTC) matches the
  `AIXODIA_KEYSTORE_B64` secret (created 12:22:59 UTC), which is how we know it is
  the shipping key.
  A different key generated locally the same day
  (`CN=AIxodia, OU=AIxodia, O=AIxodia, L=Bangkok, ST=Bangkok, C=TH`, serial
  `b35f2d1cc5cb2134`) was never used and is retired.
