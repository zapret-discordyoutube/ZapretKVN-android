# Third-party notices

## sing-box-extended

- Source: <https://github.com/shtorm-7/sing-box-extended>
- Pinned commit: `ff11f007ec798136a5de258f947a4f34011a37ea`
- Android patchset manifest: `core-patches/series.sha256`
- Patchset manifest SHA-256: `9ed4cdc263f5ea5b6413d3bc55068f5f8bca7dc0132e0ecb91f96818f5dcf441`
- License: GNU General Public License version 3 or later.
- Copyright: 2022 nekohasekai and contributors.

Pinned source additionally states that no derivative work may use the name of, or imply association with, the original application without prior consent. Zapret KVN uses its own name and identity.

Release artifacts must include corresponding source/build instructions required by the license.
The exact pinned license text, including the additional naming condition, is
also retained as an application raw resource and as a CI core artifact.
The patch is applied only after verifying the pinned checkout and patch hash,
is recorded separately from the upstream revision, and is reversed after compilation.

## Android WireGuard data-plane

- Vanilla engine: <https://github.com/MetaCubeX/wireguard-go>, commit represented by module version `v0.0.0-20250820062549-a6cecdd7f57f`.
- AmneziaWG engine: <https://github.com/MetaCubeX/amneziawg-go>, commit represented by module version `v0.0.0-20260612143004-19b4f1cdd5ec`.
- License: MIT for both modules; their complete license text is reproduced below.

```text
Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## SagerNet sing-geoip rule-set

- Source: <https://github.com/SagerNet/sing-geoip>
- Binary snapshot: `5605651c12ed5b2fcf3b5de580c041eb9d8d938e`
- License of the generator: GNU General Public License version 3 or later.

The APK contains only the pinned RU IP binary rule-set. Its SHA-256 and source
URL are recorded in `app/src/main/assets/rule-sets/manifest.json`; the upstream
license is retained as `app/src/main/res/raw/sing_geoip_license.txt`.

The generated `libbox.aar` also contains the Go modules resolved by the pinned
`go.mod` and `go.sum`. Their exact source versions are therefore reproducible
from the commit above. Their original copyright and license files remain in the
downloaded module sources under `core-build/gopath/pkg/mod`; release source
bundles must preserve them.

## AndroidX and Jetpack Compose

The app uses AndroidX Core, Activity, Compose UI, Foundation, Material icons and
Material 3. These components are distributed under the Apache License 2.0.
License resources supplied by these runtime libraries are not stripped from the
APK.

- Source: <https://android.googlesource.com/platform/frameworks/support/>
- License: <https://www.apache.org/licenses/LICENSE-2.0>

## Kotlin and Compose compiler

- Source: <https://github.com/JetBrains/kotlin>
- License: Apache License 2.0.

## ZXing Android Embedded and ZXing Core

- Sources: <https://github.com/journeyapps/zxing-android-embedded> and
  <https://github.com/zxing/zxing>
- Versions: `zxing-android-embedded` 4.3.0 and `zxing-core` 3.4.1.
- License: Apache License 2.0.

They are used only by the explicit QR import action. The Apache 2.0 license
text is retained in the APK under `META-INF` together with the notices here.

## Gradle Wrapper

- Source: <https://github.com/gradle/gradle>
- License: Apache License 2.0.
- The checked-in wrapper JAR downloads only the distribution and checksum pinned
  in `gradle/wrapper/gradle-wrapper.properties`.

## JUnit 4

JUnit is used only for local/CI tests and is not shipped in the APK.

- Source: <https://github.com/junit-team/junit4>
- License: Eclipse Public License 1.0.
