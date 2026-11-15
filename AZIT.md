# Azit Tracker for Komikku (project 5)

This fork of [Komikku](https://github.com/komikku-app/komikku) adds an **Azit Tracker** tracker. It records your
reading in your own self-hosted Azit Tracker, the same way Komikku already updates AniList or MyAnimeList.

- Branch: `azit-tracker` (upstream Komikku is the `upstream` remote: `git fetch upstream && git rebase upstream/master`)
- Code: `app/src/main/java/eu/kanade/tachiyomi/data/track/azit/` (`Azit.kt` tracker, `AzitApi.kt` HTTP client,
  `dto/` JSON models). Edits to existing Komikku files are wrapped in `// KMK -->` / `// KMK <--` markers.

## Build and install

You need JDK 21 or newer (JDK 24 from `~/.jdks` works) and the Android SDK (platform 36). Put the SDK path in
`local.properties`, using forward slashes:

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

```bash
./gradlew assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`. Install it with `./gradlew installDebug` (phone connected over USB
with debugging turned on) or copy the APK to the phone. The debug build is `app.komikku.dev`, so it installs
alongside a normal Komikku.

## Log in

1. In Azit, open **Settings → Devices & scrobbler** and create a device token (for example "Phone - Komikku").
   Copy it: it starts with `azd_` and is shown only once.
2. In Komikku, open **Settings → Tracking → Azit Tracker** and enter:
   - **Server address**: the address the phone can reach, for example `http://192.168.1.10:8080`
     (`http://` is added if you leave it out). `localhost` won't work, because on the phone it means the phone itself.
   - **Device token**: the `azd_...` token.
3. Komikku checks the token with `GET /api/devices/me` before saving it.

The token is stored in Komikku's private preferences, like the other trackers' credentials. Revoke it in Azit to
log the phone out.

## What is synced

| Komikku | Azit |
|---|---|
| Binding a manga (tracking sheet → Azit Tracker) | `POST /api/tracking/lookup` while you search, then `POST /api/tracking/progress` with no chapter. This finds the title, or creates it if it is missing and that option is on. Its `media_id` becomes the remote id. |
| Reading a chapter / marking chapters read | `POST /api/tracking/progress` with `chapter` and `only_forward: true`. Re-reading an old chapter never moves progress back. |
| Changing the status in the tracking sheet | `status`: reading → `current`, completed → `completed`, on hold → `paused`, dropped → `dropped`, plan to read → `planning`, re-reading → `repeating` |
| Refresh | `POST /api/tracking/lookup` (progress and status from Azit) |

Every call sends what Azit needs to find the title: the bound Azit title, the manga's titles (custom and original),
the AniList id if the manga is also tracked on AniList, and the manga's page address on its source. Azit tries the
AniList id first, then the address, then the titles. When it creates a title, it saves that address as a link, so
later calls always find it.

Settings (Settings → Tracking → *Azit Tracker* group):

- **Track library manga automatically** (on by default): the first time you read a chapter of a manga in your
  library, the manga is linked to Azit without opening the tracking sheet. Manga outside your library are never
  linked automatically.
- **Add missing titles to Azit** (on by default): this is `create_if_missing`. When it is off, titles that aren't
  already in Azit give a "not found" error.

Komikku's own settings still apply: **Update progress after reading** (from the reader) and **Update progress when
marked as read** (from the manga screen). When one is off, Azit isn't updated in that case. A failed update (server
unreachable) is retried later by Komikku's delayed tracking job.

Not synced: scores, which you give in Azit, and start/finish dates. Total chapters aren't known, so Komikku doesn't
set "completed" by itself; set it in the tracking sheet.

## Extending

- New API calls: add them to `AzitApi` and their models to `dto/AzitDto.kt`.
- Tracker behaviour (status mapping, what is sent): `Azit.kt`. The status mapping functions are in its companion
  object and are unit tested in `app/src/test/java/eu/kanade/tachiyomi/data/track/azit/AzitApiTest.kt`.
- Tracker id is `TrackerManager.AZIT = 100`. It is far from the upstream ids, so future upstream trackers won't
  collide with it.
- Tests: `./gradlew :app:testDebugUnitTest --tests "*.AzitApiTest"`
