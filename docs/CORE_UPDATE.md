# Обновление встроенных модулей ядра (Hysteria и другие Go-зависимости патчей)

sing-box-extended на Android собирается из исходников с серией патчей
`core-patches/0001…0005`. Часть зависимостей живёт не в `core.properties`, а в
`go.mod`/`go.sum`, которые эти патчи добавляют. Так устроена официальная
Hysteria: `github.com/apernet/hysteria/{core,extras}/v2` вшиты в libbox патчем
`0003-official-hysteria2-core.patch`, а `0005` снова трогает соседние строки
`go.mod`. Поэтому новая версия Hysteria — это перегенерация серии, а не правка
одной строки.

Какую версию брать, решает Windows: `scripts/resolve_core_versions.py` в
репозитории windows выбирает последний стабильный `app/vX.Y.Z`, а
`check_core_release_freeze.py` требует тот же `HYSTERIA_CORE_TAG` на Android.
Отставание останавливает подготовку Android-релиза, незаметно отстать нельзя.

## Процедура

1. Копия исходников ядра (не `git clone --shared`: `core-build/source` —
   неглубокий клон), в скриптах только `git -C "$SB"`:

   ```bash
   cp -a core-build/source "$SB"
   ```

2. Серия патчей цепочкой коммитов с тегами `p0000`…`p0005` (`git apply` +
   `commit` по порядку из `core-patches/series.sha256`). Проверка настройки
   диффа: `git -C "$SB" diff --abbrev=8 p0002 p0003` байт в байт совпадает с
   `0003-official-hysteria2-core.patch` — тогда в новых патчах изменится только
   то, что вы поменяли.

3. На `p0003`, с тем Go, что в `GO_VERSION` (`GOTOOLCHAIN=local`):

   ```bash
   go get github.com/apernet/hysteria/core/v2@vX.Y.Z github.com/apernet/hysteria/extras/v2@vX.Y.Z
   ```

   `go get` поднимает и транзитивные зависимости (quic-go, testify). Проверьте
   `go mod tidy -diff`: если он требует новую `// indirect`-строку в `go.mod`,
   добавьте только её. Полный `tidy` на этом шаге не запускать — он перестраивает
   блоки из `0001`. Из `go.sum` уберите только хеши прежней Hysteria и прежнего
   quic-go.

4. `git cherry-pick p0004 p0005`. В `0005` ожидаем конфликт в `go.mod`: он
   переносит amneziawg-go из косвенных в прямые, а рядом стоит quic-go. Итог:
   строки amneziawg в блоке `// indirect` нет, quic-go — новый.

5. На финальном состоянии (после `protocol/xraycore/prepare.sh`):
   `go mod tidy -diff` не требует изменений `go.mod`, а
   `go test -count=1 -run '^$' -tags … ./protocol/hysteria2 ./protocol/xraycore
   ./parser/link ./route/rule ./transport/wireguard` компилируется.

6. Новые патчи — `git diff --abbrev=8` между соседними тегами; пересчитать
   `core-patches/series.sha256`, `CORE_PATCH_SHA256`, `HYSTERIA_CORE_TAG`,
   `HYSTERIA_CORE_COMMIT` (`go mod download -json …@vX.Y.Z` → `Origin.Hash`),
   обновить версию в `docs/THIRD_PARTY_NOTICES.md` и
   `app/src/main/res/raw/third_party_notices.txt` (файлы одинаковые).

7. Полный `scripts/build-core.sh` (Go из `GO_VERSION` первым в `PATH`,
   `ANDROID_HOME`), затем unit-тесты и `scripts/verify-project.sh`.

## Версия Go

`GO_VERSION` на Android и `amnezia.toolchain` в `core-lock.windows-x64.json`
обязаны совпадать (`check_core_release_freeze.py`). Поднимаются вместе: toolchain
ставится в `~/.local/share/toolchains/go-X.Y.Z` (SHA-256 из
`https://go.dev/dl/?mode=json`), `/usr/local/bin/go` переключается после коммита
пинов, в `.forgejo/workflows/*.yml` меняется `go-version`.
