# Cómo generar e instalar los APKs de Termux Work (`tx.work`)

Esta build **no reemplaza** Termux oficial (`com.termux`). El `applicationId` es `tx.work` (7 caracteres, mismo largo de PREFIX que `/data/data/com.termux` en usuarios 10–99) y el launcher muestra **Termux Work**. Hay que **desinstalar** `com.termux.work` si lo tenías.

Usa **Android 7+** → variante `apt-android-7`.  
`apt-android-5` solo es para Android 5/6 y **no** recibe paquetes actualizados.

En un teléfono moderno (casi todos los de 2018+) instala el APK **`arm64-v8a`** o el **`universal`**.

---

## 1. Build automático con GitHub Actions (recomendado)

El workflow **Build** (`.github/workflows/debug_build.yml`) genera APKs debug firmados con la test key del repo.

### Cuándo se dispara

- Push a `master`, `github-releases/**` o `arena/**`
- Pull request hacia `master`
- Manualmente: **Actions → Build → Run workflow**

### Disparar a mano (web)

1. Abre el repositorio en GitHub.
2. Pestaña **Actions**.
3. Workflow **Build** (columna izquierda).
4. Botón **Run workflow**.
5. Elige la rama `arena/01a077e7-termux-app` (o `master`).
6. **Run workflow**.

Hay **2 jobs** (matrix): `apt-android-7` y `apt-android-5`. Cada uno tarda varios minutos (descarga bootstrap + NDK + Gradle).

### Disparar a mano (CLI)

Necesitas [GitHub CLI](https://cli.github.com/) autenticado (`gh auth login`).

```bash
cd termux-app
gh workflow run Build --ref arena/01a077e7-termux-app
gh run watch
```

Listar corridas:

```bash
gh run list --workflow=Build --limit 10
```

### Descargar los APKs

Cuando el job termine en verde:

**Web:** Actions → el run → **Artifacts** (abajo).  
Cada artifact es un zip. Para work profile / teléfono actual descarga:

- `termux-app_v0.118.0+<sha>-apt-android-7-github-debug_universal`  
  **o**
- `termux-app_v0.118.0+<sha>-apt-android-7-github-debug_arm64-v8a`

**CLI:**

```bash
# ID del run (columna de gh run list)
gh run download <RUN_ID> -n termux-app_v0.118.0+XXXXXXX-apt-android-7-github-debug_universal
```

También:

```bash
gh run download <RUN_ID>
```

baja **todos** los artifacts del run.

### Instalar en el work profile

1. Copia el `.apk` al teléfono (Drive, USB, `adb`, etc.).
2. En el **work profile** (Shelter / Island / perfil de trabajo) abre el APK con el instalador de ese perfil.  
   No instales sobre Termux oficial: son paquetes distintos.
3. Primera apertura extrae el bootstrap. Espera a que termine.
4. Opcional: `termux-setup-storage` para el almacenamiento **de ese perfil**.

Firma: test key compartida del repo (`app/testkey_untrusted.jks`). **No** es una release de producción.

---

## 2. Build local (sin Actions)

Requisitos:

- JDK **17**
- Android SDK (`compileSdk` 36) y NDK **29.0.14206865** (Gradle lo puede bajar)
- ~4 GB RAM, red para descargar bootstrap zips

```bash
git clone https://github.com/sarro3/termux-app.git
cd termux-app
git checkout arena/01a077e7-termux-app

# Android 7+ (lo que quieres para work profile)
export TERMUX_PACKAGE_VARIANT=apt-android-7
export JAVA_HOME=/ruta/a/jdk-17
./gradlew :app:assembleDebug
```

APKs en:

```
app/build/outputs/apk/debug/
```

Nombres típicos:

```
termux-app_apt-android-7-debug_universal.apk
termux-app_apt-android-7-debug_arm64-v8a.apk
termux-app_apt-android-7-debug_armeabi-v7a.apk
termux-app_apt-android-7-debug_x86_64.apk
termux-app_apt-android-7-debug_x86.apk
```

Variables opcionales (las usa Actions):

| Variable | Efecto |
|---|---|
| `TERMUX_PACKAGE_VARIANT` | `apt-android-7` (default) o `apt-android-5` |
| `TERMUX_APP_VERSION_NAME` | `versionName` (debe ser semver, p.ej. `0.118.0+abc1234`) |
| `TERMUX_APK_VERSION_TAG` | Prefijo del nombre de archivo del APK |
| `TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS` | `1` (default) genera un APK por ABI + universal |

Instalar por ADB (perfil personal):

```bash
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
```

Work profile: usa el user id del perfil (suele ser `10`):

```bash
adb shell pm list users
adb install --user 10 -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
```

---

## 3. Qué APK elegir

| Archivo | Cuándo |
|---|---|
| `*_arm64-v8a.apk` | Casi todos los teléfonos actuales |
| `*_armeabi-v7a.apk` | Teléfonos 32-bit viejos |
| `*_universal.apk` | Si no sabes la ABI (~más pesado) |
| `*_x86_64.apk` / `*_x86.apk` | Emuladores |

Package: **`com.termux.work`**. Convive con Termux oficial.

---

## 4. Release con APKs adjuntos (opcional)

El workflow **Attach Debug APKs To Release** se dispara al **publicar un GitHub Release** con tag semver (`v0.118.0`, `v0.118.1`, …). Compila y sube los APKs a ese release.

```bash
git tag v0.118.1
git push origin v0.118.1
gh release create v0.118.1 --title "v0.118.1" --notes "Termux Work profile build"
```

El tag debe coincidir con semver 2.0.0 (incluye el patch: `v0.118.1`, no `v0.118`).

---

## 5. Si Actions no arranca

- En el repo: **Settings → Actions → General → Allow all actions**.
- Los artifacts caducan (por defecto ~90 días); vuelve a lanzar el workflow si desaparecieron.
- Tienes que estar **logueado en GitHub** para bajar artifacts de un workflow.
- El primer run en un fork a veces pide habilitar Actions una vez.
