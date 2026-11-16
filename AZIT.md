# Azit Tracker for Komikku (project 5)

This fork of [Komikku](https://github.com/komikku-app/komikku) adds an **Azit Tracker** tracker. It records your
reading in your own self-hosted Azit Tracker, the same way Komikku already updates AniList or MyAnimeList.

- Branch: `azit-tracker` (remote `origin` = AZIT-Tracker/Azit-Tracker-Komikku; you push it yourself). Upstream Komikku is the
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

**Linking only links.** It finds the Azit title and reads its state (status, chapters, score, dates) into
Komikku's track. Nothing is written to Azit or AniList, and a title Azit doesn't have is skipped, never created.
The requests are `POST /api/tracking/lookup` (a read-only search) or `GET /api/tracking/entry/{media_id}`.
Komikku usually pushes your local progress to a tracker right after binding it; that step is skipped for Azit.

- **Automatically from AniList:** when a manga gets an AniList track, it is linked to its Azit title, found by
  AniList id, then page address, then titles.
- **After logging in:** library manga already tracked on AniList are linked in the background, one at a time.
- **On a chapter read** of a library manga with no Azit link yet: it is linked first. Then the chapter is sent,
  like every chapter read (see below).
- **By hand:** tracking sheet → Azit Tracker. The search looks through Azit's books (manga, manhwa, manhua,
  webtoon, comic, novels), shows their covers and says whether each one is in your library. Choosing one links
  to it.
- **Create in Azit** (the last search result, always there): the only way a title gets created. A form filled with
  what Komikku has opens first: title, alternative titles, type (automatic or one of Azit's book types), cover
  URL, description, authors, genres, release status and total chapters. You can edit any of them. The new title
  also gets the source page (labelled with the source name) and the AniList id, and it is added to your library
  (status "planning"). Komikku's read chapters are not pushed; the next chapter you read is.
- **Migrate from AniList:** Settings → Tracking → *Azit Tracker* → **Migrate from AniList**. Every library manga
  with an AniList track and no Azit link is linked, as above, and nothing else is changed. You can choose to unlink
  AniList afterwards (only for manga that got linked); that only removes the link in Komikku, nothing on AniList.
  It runs in the background, one manga per second, with a progress notification. The summary gives linked /
  not found in Azit / failed, and the titles that were not found or failed. Manga already linked to Azit are
  skipped, so running it again resumes a stopped run.

Once linked, the manga is tied to Azit's `media_id`: no more searching.

## What is synced

| Komikku | Azit |
|---|---|
| Reading a chapter / marking chapters read (the only automatic write) | `POST /api/tracking/progress` with `media_id`, `chapter` and `only_forward: true`, plus the chapter page `url` and the source name as `link_label`. Azit keeps the last page read on each site as the title's shortcut. |
| Tracking sheet edits (status, chapters, score, start and finish dates) | `POST /api/tracking/progress` with `media_id` and `only_forward: false`. Score and dates are always sent; `null` clears them. |
| Remove (tracking sheet → remove, with the option to also remove it from the tracker) | `DELETE /api/tracking/entry/{media_id}`: it leaves the library, the title stays in Azit |
| Volumes (track card → Volumes) | `POST /api/tracking/progress` with `media_id`, `volume` and `only_forward: false` |
| Refresh | `GET /api/tracking/entry/{media_id}` |

Statuses: reading → `current`, completed → `completed`, on hold → `paused`, dropped → `dropped`, plan to read →
`planning`, re-reading → `repeating`. Score: shown 0.0-10.0 like AniList's "10 point decimal", sent as 0-100.
Dates: `YYYY-MM-DD`. When Azit knows the total chapters, reading the last one sets "completed".

The Azit card in the tracking sheet also shows the cover Azit has (when there is one), the volumes read, and
**Open in Azit** (`{server}/media/{media_id}`; tapping the logo does the same). Tapping the title searches again,
to link the manga to another title. Komikku's track has no field for the cover or the volumes, so they are kept per
title in Komikku's preferences and updated by every answer of the server.

Settings (Settings → Tracking → *Azit Tracker* group):

- **Track library manga automatically** (on by default): turns on all the automatic linking above. Manga outside
  your library are only linked automatically when they get an AniList track.

Komikku's own settings still apply: **Update progress after reading** (from the reader) and **Update progress when
marked as read** (from the manga screen). With **Auto sync progress from Trackers**, opening a manga can also
send Komikku's chapter count when it is ahead of Azit's (Azit's own status, score and dates are sent back
unchanged). A failed update (server unreachable) is retried later by Komikku's
delayed tracking job.

## Extending

- New API calls: add them to `AzitApi` and their models to `dto/AzitDto.kt`.
- Tracker behaviour (what is sent when): `Azit.kt`. Conversions (status, score, dates, release status, kind) are in
  `AzitMapping.kt`. Both are unit tested in `app/src/test/java/eu/kanade/tachiyomi/data/track/azit/`.
- Background migration: `AzitMigrationJob.kt` (WorkManager); the per-manga step is `Azit.migrateFromAnilist`.
- UI: `app/src/main/java/eu/kanade/presentation/track/azit/` (`AzitTrackExtras` on the track card,
  `AzitCreateDialog` for "Create in Azit").
- Hooks in Komikku code (`// KMK` markers): `TrackChapter` (link on chapter read), `AddTracks` (link when AniList
  is bound), `TrackInfoDialog` (opens the create form), `TrackInfoDialogHome` (card extras),
  `SettingsTrackingScreen` (login dialog, settings, migration), `TrackerManager`, `TrackStatus`, `TrackPreferences`.
- Tracker id is `TrackerManager.AZIT = 100`. It is far from the upstream ids, so future upstream trackers won't
  collide with it.
- Tests: `./gradlew :app:testDebugUnitTest --tests "*.azit.*"`
