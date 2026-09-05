# Подпись Zapret KVN Android

Release APK всегда должен быть подписан одним постоянным ключом. Stable собирается и
публикуется локально владельцем; production JKS и пароли не передаются в Forgejo Actions
и не добавляются в Git.

## Однократное создание ключа офлайн

На отключённой от сети машине с JDK 17:

```bash
keytool -genkeypair \
  -keystore zapret-kvn-release.jks \
  -alias zapret-kvn-release \
  -keyalg RSA -keysize 4096 -validity 10000
sha256sum zapret-kvn-release.jks > zapret-kvn-release.jks.sha256
gpg --symmetric --cipher-algo AES256 zapret-kvn-release.jks
keytool -exportcert -rfc -keystore zapret-kvn-release.jks -alias zapret-kvn-release \
  | openssl x509 -outform DER \
  | sha256sum
```

Сделать минимум две офлайн-копии `zapret-kvn-release.jks.gpg` и checksum на разных
носителях. Проверить восстановление на третьем временном носителе командами
`gpg --decrypt` и `sha256sum -c`, затем безопасно удалить расшифрованную проверочную
копию. Потеря ключа означает невозможность обновить уже установленное приложение.

## Локальное приватное хранилище

По умолчанию локальный publisher читает каталог `~/.zapret-kvn-signing` с правами `700`.
Файлы внутри должны иметь права `600`:

- `zapret-kvn-release.jks` — постоянный production key;
- `certificate-sha256.txt` — SHA-256 сертификата;
- `signing-secrets.env` — owner-only env-файл с
  `ANDROID_SIGNING_STORE_PASSWORD`, `ANDROID_SIGNING_KEY_ALIAS`,
  `ANDROID_SIGNING_KEY_PASSWORD` и `ANDROID_SIGNING_CERT_SHA256`.

Для совместимости publisher также понимает историческое имя `github-secrets.env`, но
файл по-прежнему хранится только локально и не передаётся Forgejo.

Другой каталог можно явно задать через `ZAPRET_SIGNING_DIR`. Публичный fingerprint
закреплён в `release.properties`; publisher до сборки требует точного совпадения JKS,
приватного metadata-файла, env-файла и публичного значения.
`scripts/ci-build.sh` автоматически отключает Gradle configuration cache, когда переданы
`ZAPRET_SIGNING_*`, чтобы пароли не попали в повторно используемый Gradle state.

## Финальный approval, версии и публикация

После закрытия всех обязательных host-пунктов финального gate stable выпускается
из чистого `main`, который точно совпадает с `origin/main`. Проверка production-signed
ABI-specific APK на физическом Android-устройстве даёт дополнительное подтверждение, но
не является обязательным условием публикации; её фактический статус всегда указывается
в release notes:

```bash
git tag -a vMAJOR.MINOR.PATCH -m "Zapret KVN MAJOR.MINOR.PATCH"
scripts/publish-local-stable.sh vMAJOR.MINOR.PATCH --final-gate-approved
```

Скрипт повторно fetch-ит `origin/main`, при необходимости отправляет только уже созданный
tag, запускает локальные тесты и release-аудиты, собирает и подписывает три ABI, проверяет
bundle и создаёт приватный draft Forgejo Release. Восемь assets загружаются по одному с
ограничением времени и повторами; после каждой загрузки файл скачивается обратно из
Forgejo и его размер и SHA-256 сравниваются с локальным файлом. Release становится публичным только после точного
совпадения всего набора. Существующий опубликованный Release или несовпадающий tag
никогда не заменяется.

- stable tag: `vMAJOR.MINOR.PATCH`;
- beta tag: `vMAJOR.MINOR.PATCH-beta.N`, где `N` от 1 до 98;
- versionCode вычисляется детерминированно; stable получает slot 99 и поэтому новее beta
  той же версии;
- опубликованные assets не заменяются: исправление выпускается только новым tag.

Лимит APK проверяется по фактическому ABI: 96 МиБ для `arm64-v8a` и
`armeabi-v7a`, 112 МиБ для `x86_64` (согласован владельцем после встраивания
официального Xray). Native-библиотеки остаются несжатыми для прямой загрузки.

Локальный publisher публикует подписанные `arm64-v8a`, `armeabi-v7a`, `x86_64` APK,
отдельные `.sha256` и `release-metadata-v2.json` с package, version, точным commit ядра,
signing fingerprint и размером APK.

После публикации он запускает `release-verify.yml` и сразу завершается, не ожидая workflow.
Forgejo Actions ничего не публикует и не имеет production key: он собирает исходники с
одноразовым тестовым ключом, скачивает опубликованные локально assets и независимо
проверяет metadata, checksum, ABI, версии и production fingerprint. ADB-проверки на
эмуляторе и реальном устройстве необязательны и не отмечаются как пройденные, если
фактически не запускались; instrumentation APK при этом должен успешно собираться.

## Возобновление прерванной публикации

Не удалять `build/local-release/TAG`, draft, remote assets или tag и не использовать
`--clobber`. Повторный запуск той же команды сначала полностью проверит сохранённый
bundle, затем продолжит только draft с тем же tag: совпавшие по размеру и фактическому
SHA-256 скачанных из Forgejo файлов будут пропущены, отсутствующие — загружены по одному.

Если remote asset существует, но отличается размером или содержимым, publisher
остановится. Такой asset не удаляется и не заменяется; исправление выпускается новым tag.
Именно пакетная загрузка восьми файлов одной операцией зависла при
публикации `v0.2.12`, тогда как отдельные загрузки APK завершились за несколько секунд,
поэтому batch-upload для stable запрещён.
