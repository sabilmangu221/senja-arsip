# Extension Shinigami Pribadi untuk Mihon

Fork dari extension Shinigami milik Keiyoushi (Apache-2.0) dengan satu perubahan
besar: **domain tidak lagi hardcoded**. Extension ini dibuat untuk arif dan
ditandatangani kunci sendiri, jadi updatenya tidak tergantung maintainer
Keiyoushi.

- Package: `eu.kanade.tachiyomi.extension.id.shinigami` (sama seperti Keiyoushi)
- Source ID: `3411809758861089969` (sama — library Mihon tetap nyambung)
- Versi awal fork: versionCode 86 (upstream 85 + resolver domain)

## Cara extension menemukan domain (3 lapis)

1. **Override manual** — di Mihon: buka sumber Shinigami → ikon gear →
   ada kolom "Domain web (override)" dan "Domain API (override)".
   Isi kalau lagi darurat; kosongkan lagi kalau sudah normal.
2. **Remote config** — saat pertama dipakai, extension mengambil
   `config.json` dari branch `repo` repo ini
   (`raw.githubusercontent.com/sabilmangu221/shinigami-ext/repo/config.json`).
   Isinya daftar kandidat host API & web. Mengubah file itu = mengubah
   domain extension di semua HP, tanpa APK baru.
3. **Default bawaan** — `https://api.shngm.io` (API) dan domain web dari
   build (`https://11.shinigami.asia`). API dicoba satu per satu dari
   daftar kandidat sampai ada yang menjawab; yang berhasil diingat.

Catatan penting: baca/search/detail/chapter/gambar semuanya lewat
`api.shngm.io` + `assets.shngm.id`, **bukan** domain web bernomor
(`11.shinigami.asia`, dst). Jadi selama ini pun sebenarnya yang pindah-
pindah itu cuma domain web untuk membuka link di WebView — data bacanya
relatif kebal. Lapisan di atas menutup sisa risikonya.

## Auto-update di Mihon

Repo ini menerbitkan repo extension sendiri:

- GitHub Actions (`.github/workflows/build.yml`) membangun APK+JAR,
  mengunggahnya ke GitHub Release `ext-v<versionCode>`, lalu membuat
  `index.pb` (script `.github/scripts/publish-personal.py`) dan
  mendorongnya ke branch `repo`, bersama `config.json`.
- Tambahkan ke Mihon sekali saja:
  **More → Settings → Browse → Extension stores → Add** →
  `https://github.com/sabilmangu221/shinigami-ext/raw/repo/index.pb`
- Setelah itu, setiap ada build baru (versionCode naik), Mihon menawarkan
  update extension seperti biasa. **Syaratnya: kunci tanda tangan tidak
  boleh ganti.** Fingerprint kunci repo ini:
  `23c10063b1946693a2121a9e57578a97aad294699810f0b6567ad6562d3feefd`

## Kalau Shinigami ganti domain nanti

| Yang berubah | Yang dilakukan |
|---|---|
| Domain web (`11.` → `12.` dst) | Edit `config/config.json` di branch `main` (atau `config.json` langsung di branch `repo` lewat web GitHub). Extension menyesuaikan sendiri. |
| Host API | Tambahkan host baru ke daftar `"api"` di `config.json` yang sama. |
| API mati total / darurat | Isi override manual di pengaturan sumber Shinigami dalam Mihon. |
| Format API berubah (bukan cuma domain) | Ubah kode di `src/id/shinigami/`, naikkan `versionCode` di `build.gradle.kts`, push. Actions membangun & menerbitkan update otomatis. |

## Secrets yang dibutuhkan Actions

Di repo: **Settings → Secrets and variables → Actions**:

| Nama secret | Isi |
|---|---|
| `SIGNING_KEY_B64` | Base64 dari `signingkey.jks` |
| `KEY_STORE_PASSWORD` | Password keystore |
| `KEY_PASSWORD` | Password key (sama dengan keystore di setup awal) |
| `ALIAS` | `shinigami` |

File-file kunci aslinya disimpan mlyana di
`~/workspace/shinigami-ext/signing/` (tidak masuk repo — lihat `.gitignore`).
**Jangan sampai hilang**: tanpa keystore itu, extension tidak akan pernah
bisa di-update (Android menolak tanda tangan berbeda); jalan keluarnya
uninstall total.

## Catatan install pertama

Package extension ini sama dengan punya Keiyoushi tetapi tanda tangannya
beda, jadi Android menolak memasangnya di atas yang lama. **Uninstall dulu
extension Shinigami dari Keiyoushi** (cuma extension-nya, bukan Mihon),
lalu install dari repo "Shinigami Personal". Library aman: Mihon mengikat
library ke source ID, dan ID-nya sama persis.
