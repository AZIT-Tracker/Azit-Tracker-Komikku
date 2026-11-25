# Azit Tracker for Komikku (project 5)

This fork of [Komikku](https://github.com/komikku-app/komikku) adds an **Azit Tracker** tracker. It records your
reading in your own self-hosted Azit Tracker, the same way Komikku already updates AniList or MyAnimeList.
Every other feature the fork adds (chapters, downloads, migration, build) is listed in
[Everything this fork adds](#everything-this-fork-adds) at the end.

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
  what Komikku knows opens first, and you can edit everything in it:
  - the title: title, alternative titles, type, cover URL (with a preview), description, authors, genres,
    release status and total chapters (from the manga). The type is guessed like Komikku does for the reader
    (genres and source: manga, manhwa, manhua, webtoon, comic); novels come from the genres or the source name
    (light / web novel when said). It stays automatic when Azit has no such type;
  - your entry: reading status, chapters read, score (0-10) and start / finish dates (`YYYY-MM-DD`). They come
    from the other trackers of the manga, AniList first, then the others for what AniList lacks. Without a
    tracker, they come from Komikku: chapters marked read, start = first chapter read in the history, finish =
    last chapter read when every chapter is read.

  The new title also gets the source page (labelled with the source name) and the AniList id. It is added to your
  library with the entry as shown in the form; creating is a deliberate action, so these values are written.
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

If Azit's **Pause tracking from devices** switch (Settings → Devices) is on, every write is refused (HTTP 423) and
Komikku says "Tracking from devices is paused in Azit".

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

## Everything this fork adds

Every change compared with Komikku, by area. Code changed in Komikku files is marked `// KMK` or `// Azit`;
new files hold only fork code.

### Azit Tracker

- **The tracker** (all of the above): log in with a device token, link a manga (from AniList, after logging in, on
  a chapter read, by hand), **Create in Azit** with an editable form, sync of chapters / status / score / dates /
  volumes, refresh, remove, **Migrate from AniList**.
- **Manga and comics only**: Azit's novels belong to LNReader, so Komikku never sees them (search, links, type
  guessing); every request says it is Komikku (`X-Azit-App: komikku`) and Azit refuses anything else.
- **Doubles checked before creating**: "Create in Azit" first looks for a title with the same page, AniList id or
  name and offers it ("Is it one of these?") instead of making a second one.
- **Cover upload**: the cover is downloaded on the phone and uploaded to Azit (sites often refuse Azit's own
  download).
- **Chapter 0 waits for chapter 1**: while the tracker is at 0, its sync marks nothing read, so a chapter 0 (a
  prologue) stays unread until chapter 1 is read.
- **Offline queue**: chapters read while Azit (or any tracker) can't be reached wait, per tracker, and go out as
  soon as that tracker answers again (several at once for Azit). Settings → Tracking → **Show chapters waiting to
  be sent** lists them, why they wait, and sends them again.
- **Unlinked entries**: Settings → Tracking → **Check unlinked entries** lists the library series not linked to
  each tracker; tap one to link it.
- **Manual search** when the automatic one finds nothing, from the tracking sheet.
- **A finished series completes the entry**: reading the last chapter of a series marked Completed sets the
  tracker entry to completed, even when the tracker doesn't know the chapter count (it gets the series' own),
  also when sent later from the offline queue.
- **Chapters set back by hand**: on a completed Azit entry, setting chapters below the count (down to 0) sends
  "reading" ("plan to read" at 0), so Azit keeps the number you chose.

### Chapters

- **Hide duplicate chapters** (chapter filter): one copy per chapter number, the first uploaded or the last
  uploaded ("Show the first / last uploaded").
- **Hide chapters one by one**: selection bar → ⋮ → Hide / Unhide. Filter → **Show hidden chapters (N)** brings
  them back faded, **Unhide all hidden chapters** clears them. Hidden chapters are left out of the reader's
  next / previous, Updates, the Start / Resume button, the library's counts and "continue reading".
- **The library's unread badge** (and the unread filter / sort) counts only the chapters the series' list shows:
  hidden ones and hidden duplicates are not counted. The green downloaded badge still counts every download.
- **Chapter edition**: selection bar → ⋮ → **Chapter edition**. Each selected chapter can count as another chapter
  number, or as a volume holding chapters (`2` or `2-4`). A chapter number fills the chapters after it
  (3 → 4, 5, 6…), volumes are skipped and a number typed by hand stops it; extra chapters keep their decimal part
  (10, 10.5, 11 with 20 typed → 20, 20.5, 21). A checkbox makes them all volumes. The keyboard's Next goes to the next
  chapter; the window shrinks above the keyboard so the list scrolls down to the last one.
  The chapter list shows the new number in grey after the name (`(Ch. 3)`, `(Ch. 2-4)` for a volume). Trackers
  get the last number given. **Remove chapter edition** clears it.
- **Sort by chapter number uses Chapter edition**: an edited chapter sorts by the number given to it (a volume by
  its first number), in the chapter list, the reader's next / previous and "download next".
- **Missing chapters count with Chapter edition**: a volume covers its whole range (39-43 then 44-49 leaves nothing
  missing), in the header's count and the "N missing chapters" rows; the row for the chapters before the first one
  only shows next to the lowest chapter.
- **Chapter edition keyboard**: the phone's keyboard opens as a signed number pad, so it has digits, "." and "-"
  (Samsung's plain number pad has no "-"; the keyboard app decides where the keys go). A "," typed becomes "."
  and anything else is dropped.
- **Selection bar ⋮ menu**: Hide / Unhide and Chapter edition sit in a "more" menu like the library's.
- **Read is saved first**: the last page saves the chapter as read at once; trackers, deletions and duplicates
  follow in the background, so an unreachable tracker never holds it back.

### Downloads

- **Delete after automatically marked as read**: with "after manually marked as read" also on, chapters the
  tracker sync marks as read are deleted like the ones you mark yourself.
- **More download-ahead amounts**, and a **custom amount** (1 to 999 chapters).

### Migration

- The **back arrow** of the migration screen returns to the search results of the entry you were picking (kept in
  memory, cleared once the migration is confirmed), then to the entry; it never says "stop migrating".

### App

- **Blue icon** (the Azit site's colour) for the dev build, so it is told apart from a normal Komikku.
- **`azit` build type**: optimized like a release (much smoother than debug) but the same app id and key as the
  debug build, so it installs over it and keeps the library: `./gradlew assembleAzit`.
- Kept up to date with Komikku (`upstream/master` merged into `azit-tracker`).
