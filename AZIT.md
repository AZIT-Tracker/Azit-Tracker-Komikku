# Azit Tracker for Komikku (project 5)

This fork of [Komikku](https://github.com/komikku-app/komikku) adds an **Azit Tracker** tracker. It records your
reading in your own self-hosted Azit Tracker, the same way Komikku already updates AniList or MyAnimeList.

- Branch: `azit-tracker`, pushed to `origin` (AZIT-Tracker/Azit-Tracker-Komikku). Upstream Komikku is the
  `upstream` remote: `git fetch upstream && git rebase upstream/master`.
- Code: `app/src/main/java/eu/kanade/tachiyomi/data/track/azit/` (`Azit.kt` tracker, `AzitApi.kt` HTTP client,
  `AzitMapping.kt` conversions, `dto/` JSON models). Edits to existing Komikku files are wrapped in `// KMK -->` / `// KMK <--` markers.

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

## Linking a manga

- **Automatically from AniList:** when a manga gets an AniList track (or already has one), it is linked to Azit
  too. Azit finds the title by its AniList id. If it isn't in Azit yet, Azit creates it from AniList (when **Add
  missing titles to Azit** is on).
- **After logging in:** library manga that are already tracked on AniList are linked in the background, one at a
  time.
- **On the first chapter read** of a library manga that has no Azit link yet: Azit finds the title (AniList id,
  page address, titles) or creates it.
- **By hand:** tracking sheet → Azit Tracker. The search shows the matching Azit title (if any) and always
  **Create in Azit: …**. That entry creates a new title from what Komikku has: title and alternative title, cover,
  description, author/artist, genres, release status, total chapters when the series is finished, the source page
  (labelled with the source name) and the AniList id. It is added to your library, even when a matching title
  already exists.

Once linked, the manga is tied to Azit's `media_id`: no more searching.

## What is synced

| Komikku | Azit |
|---|---|
| Reading a chapter / marking chapters read | `POST /api/tracking/progress` with `media_id`, `chapter` and `only_forward: true`, plus the chapter page `url` and the source name as `link_label`. Azit keeps the last page read on each site as the title's shortcut. |
| Tracking sheet edits (status, chapters, score, start and finish dates) | `POST /api/tracking/progress` with `media_id` and `only_forward: false`. Score and dates are always sent; `null` clears them. |
| Remove (tracking sheet → remove, with the option to also remove it from the tracker) | `DELETE /api/tracking/entry/{media_id}`: it leaves the library, the title stays in Azit |
| Refresh | `GET /api/tracking/entry/{media_id}` |

Statuses: reading → `current`, completed → `completed`, on hold → `paused`, dropped → `dropped`, plan to read →
`planning`, re-reading → `repeating`. Score: shown 0.0-10.0 like AniList's "10 point decimal", sent as 0-100.
Dates: `YYYY-MM-DD`. When Azit knows the total chapters, reading the last one sets "completed".

Settings (Settings → Tracking → *Azit Tracker* group):

- **Track library manga automatically** (on by default): turns on all the automatic linking above. Manga outside
  your library are only linked automatically when they get an AniList track.
- **Add missing titles to Azit** (on by default): this is `create_if_missing` when linking automatically. "Create
  in Azit" always creates.

Komikku's own settings still apply: **Update progress after reading** (from the reader) and **Update progress when
marked as read** (from the manga screen). A failed update (server unreachable) is retried later by Komikku's
delayed tracking job.

## Extending

- New API calls: add them to `AzitApi` and their models to `dto/AzitDto.kt`.
- Tracker behaviour (what is sent when): `Azit.kt`. Conversions (status, score, dates, release status, kind) are in
  `AzitMapping.kt`. Both are unit tested in `app/src/test/java/eu/kanade/tachiyomi/data/track/azit/`.
- Hooks in Komikku code (`// KMK` markers): `TrackChapter` (link on chapter read), `AddTracks` (link when AniList
  is bound), `SettingsTrackingScreen` (login dialog and settings), `TrackerManager`, `TrackStatus`, `TrackPreferences`.
- Tracker id is `TrackerManager.AZIT = 100`. It is far from the upstream ids, so future upstream trackers won't
  collide with it.
- Tests: `./gradlew :app:testDebugUnitTest --tests "*.azit.*"`
