# Synced Wallpapers (Android prototype)

Small trusted groups propose a photo and a time. If **every** member approves, the
photo becomes the wallpaper on every member's phone for that window, then each
person's own wallpaper comes back.

Everything runs on free tiers with no credit card: **Supabase Free** (Postgres, Auth,
Storage, Edge Functions, Realtime, pg_cron, pg_net), **Firebase Spark** (Cloud
Messaging only) and **Google Sign-In**.

```
synced-wallpapers/
├── app/                     Android app (Kotlin, Compose, Material 3, Hilt, Room, WorkManager)
├── supabase/
│   ├── config.toml          local stack config (supabase start)
│   ├── migrations/          schema, RLS, RPCs, storage, jobs (all state changes are RPCs)
│   ├── tests/               pgTAP tests (supabase test db)
│   └── functions/           edge functions: send-push, purge-images, delete-account
├── scripts/
│   ├── test-db-native.sh    runs migrations + pgTAP on a plain local Postgres (no Docker)
│   └── race-test.sh         two-connection concurrency test (slot race, vote race)
└── local.properties.example config template (copy to local.properties, git-ignored)
```

## 1. Configure the app

```bash
cp local.properties.example local.properties
```

Fill in `local.properties`. It is git-ignored; values reach the code only through
`BuildConfig` (see `app/build.gradle.kts`). Only the publishable/anon key goes here,
never the service_role key.

`supabase.target=local` (the default) points the app at your local stack;
`supabase.target=hosted` points it at the hosted project. Keep it on `local` until the
migrations and functions are deployed.

The app id (package name) is **`com.syncedwallpapers.app`**.

## 2. Local Supabase stack

Needs Docker and the [Supabase CLI](https://supabase.com/docs/guides/local-development).

```bash
supabase start            # prints the local API URL and anon key
supabase db reset         # applies supabase/migrations
supabase test db          # runs the pgTAP tests in supabase/tests
```

Put the printed anon key into `supabase.local.anonKey`. From the Android emulator the
local API is `http://10.0.2.2:54321`; from a real phone on the same Wi-Fi use your
computer's LAN IP.

Let the database call the edge functions (push and purge). In the SQL editor
(Studio at http://127.0.0.1:54323):

```sql
select vault.create_secret('http://host.docker.internal:54321', 'sw_project_url');
select vault.create_secret('<a long random string>', 'sw_cron_secret');
```

Then serve the functions with the same secret:

```bash
cat > supabase/functions/.env <<'EOF'
CRON_SECRET=<the same long random string>
FCM_SERVICE_ACCOUNT=<one-line JSON of the Firebase service account, see section 4>
EOF
supabase functions serve --env-file supabase/functions/.env
```

`supabase/functions/.env` is git-ignored.

**No Docker?** `scripts/test-db-native.sh` runs every migration and pgTAP test
against a plain Postgres 16 with `pgtap` and `pg_cron` installed. It uses a small
stand-in for Supabase's `auth` and `storage` schemas (`scripts/native-bootstrap.sql`).
`scripts/race-test.sh` shows the concurrency guarantees with two real connections.

## 3. Google Sign-In (Google Cloud console)

1. **OAuth consent screen**: user type External, publishing status **Testing**.
   Add every tester's Google account under *Test users* (only they can sign in).
2. **Credentials → Create OAuth client ID → Web application.** This is the
   `serverClientId`: put it in `google.webClientId`. Also enter it (and its secret) in
   Supabase → Authentication → Providers → Google. For the local stack, export
   `GOOGLE_WEB_CLIENT_ID` and `GOOGLE_WEB_CLIENT_SECRET` before `supabase start`
   (see `config.toml`).
3. **Credentials → Create OAuth client ID → Android**, package
   `com.syncedwallpapers.app`, with the **SHA-1** of the key that signs the APK.
   Debug key:

   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```

   (Windows: `%USERPROFILE%\.android\debug.keystore`.) `./gradlew signingReport`
   prints the same. The release build in this prototype is also signed with the debug
   key, so one Android client covers both. If you sign releases with your own key, add
   a second Android client with that key's SHA-1.
4. In Supabase's Google provider keep **Skip nonce check off**. The app sends a
   SHA-256 nonce to Google and the raw nonce to Supabase.

The app has no reCAPTCHA or Play Integrity step, so sign-in works on a sideloaded APK.
It needs Google Play services on the phone for the account picker.

## 4. Push (Firebase, Spark plan)

1. Create a Firebase project and add an Android app with package
   `com.syncedwallpapers.app`. You may add the SHA-1/SHA-256 too; FCM doesn't need
   them.
2. Copy `appId`, `apiKey`, `projectId` and `senderId` from the app's config (or from
   the downloaded `google-services.json`) into `local.properties`. The app initialises
   Firebase from these values and needs no `google-services.json`.
3. Project settings → Service accounts → *Generate new private key*. Give that JSON to
   the functions as the `FCM_SERVICE_ACCOUNT` secret. It never goes in the app or the
   repo.

Without Firebase the app still works: schedules arrive through reconciliation (on app
open, every 6 hours and on system events) and through Realtime while the app is open.

## 5. Deploying to the hosted project (only when you decide to)

```bash
supabase link --project-ref <project-ref>
supabase db push
supabase functions deploy send-push purge-images delete-account
supabase secrets set CRON_SECRET=<long random string>
supabase secrets set FCM_SERVICE_ACCOUNT="$(cat service-account.json)"
```

Then, in the hosted SQL editor:

```sql
select vault.create_secret('https://<project-ref>.supabase.co', 'sw_project_url');
select vault.create_secret('<same CRON_SECRET>', 'sw_cron_secret');
```

`SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are provided to edge functions
automatically. Free projects pause after about a week without activity; the app then
shows "Server is waking up, try again in a moment" until you restore the project.

## 6. Build and test

```bash
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # JVM + Robolectric unit tests
supabase test db                      # or scripts/test-db-native.sh
scripts/race-test.sh                  # concurrency (plain Postgres)
```

## How it works

**The server decides, the client asks.** Clients can only `SELECT` (under RLS) and call
RPCs. Every RPC is `SECURITY DEFINER` with an empty `search_path`, checks `auth.uid()`,
runs in one transaction and raises a stable error code (for example `SLOT_TAKEN` or
`PERSONAL_CONFLICT`) that the app turns into a friendly sentence.

- **Group lane.** An exclusion constraint makes overlapping PENDING/APPROVED/LIVE
  proposals in one group impossible, even when two are inserted at the same moment.
- **Personal lane.** `cast_vote`, `create_proposal` and `accept_join_terms` lock the
  user's profile row (`SELECT … FOR UPDATE`) before checking commitments, so two
  approvals in two groups can't race.
- **Anonymity.** Vote, skip and delivery rows are readable only by their owner. Counts
  and reasons come only from summary RPCs, without names.
- **Joining.** The joiner must accept every upcoming schedule (images readable by
  people with a pending join request). If a new schedule is approved before the
  last member approves, the joiner has to accept again.
- **Images.** Upload is allowed only to the path reserved by `create_proposal`, only by
  the proposer, and only until the image is confirmed. A purge queue deletes images
  immediately on REJECTED/EXPIRED/CANCELLED and one hour after ENDED.
- **Push.** RPCs write `notification_outbox`. A statement trigger kicks `send-push`
  through pg_net, and pg_cron retries every minute. Rows are claimed with
  `FOR UPDATE SKIP LOCKED`, so a notification is never sent twice. The Activity inbox
  shows the same rows.
- **Device.** `DeliveryEngine.reconcile()` rebuilds everything from Room plus
  `my_schedules()`: a missing schedule means cancel and restore. It runs on app open,
  on every push, every 6 hours, and on boot, time or time-zone change and app update.
  `DeliveryDecider` (pure, unit-tested) decides what to do at any instant and never
  applies outside the window. Images are cropped for this screen, encrypted with Tink
  under an Android Keystore key, kept only in app storage, and set with
  `allowBackup = false`. At the end, the wallpaper is restored only if the wallpaper id
  is still ours.

## Milestone status

| # | Milestone | Works | Stubbed or limited | How to test |
|---|---|---|---|---|
| 1 | Setup, theme, nav, Google Sign-In, profile | Credential Manager + hashed nonce → `signInWithIdToken`; profile trigger; name/phone edit; sign-out | Needs real Google OAuth clients and Play services | Run on a device, sign in with a test user |
| 2 | Schema, RLS, groups, invites, join | Codes/links (single-use, 24h), email invites resolved on sign-up, unanimous join + terms + conflict block | Email invites aren't emailed; share the app link yourself | `03_join_test.sql` |
| 3 | Admin actions | All six types, anonymous rejects, 48h expiry, hidden from the removal target, handover/decline | – | `02_admin_test.sql` |
| 4 | Propose + image processing + upload | Photo Picker, 20 MB/format checks, ≤2560 px JPEG <2 MB with metadata stripped, focal point, phone-frame preview, reminder styles | HEIC needs Android 9+ | Propose from the app; check the object size in Storage |
| 5 | Voting RPCs + conflicts + pgTAP | Group and personal lanes, deadlines, suggestions, one-tap re-propose reusing the image | – | `01_proposals_test.sql`, `race-test.sh` |
| 6 | Delivery engine | Download, crop, encrypt, exact alarms + WorkManager fallback, apply/restore, late/missed/not-tapped, skip, reconciliation | The lock screen restores by clearing our lock wallpaper (apps can't read the previous one) | `DeliveryEngineTest`, `AlarmSchedulerTest` |
| 7 | Push pipeline + Setup health | Outbox → send-push (FCM v1 with a service-account JWT), dead-token cleanup; checklist with OEM deep links | Needs Firebase values + `FCM_SERVICE_ACCOUNT` | `supabase functions serve`, then trigger any RPC |
| 8 | pg_cron + purge | tick (expire, LIVE/ENDED, reminders, admin expiry), purge-images, cleanup after 7 days | – | `04_membership_jobs_test.sql` |
| 9 | Delivery status UI, Activity, Settings | "8 of 9 ready", who's ready/live (never who skipped), inbox, prefs, account deletion | – | Manual |
| 10 | Tests, empty/error states, README | 91 pgTAP + 47 Android unit tests, race test, empty/error/"server waking" states | No instrumented UI tests yet | Commands above |

## Known limitations

- Anyone can screenshot their own home screen (said during onboarding).
- On Android 12+ the system may recolour itself to match a live wallpaper.
- OEM "autostart" screens are private and change between versions; Setup health tries
  several and falls back to the app's settings page.
- Designed for one device per account. A second signed-in device also registers its
  push token and works, but delivery states are reported per account, not per device.
