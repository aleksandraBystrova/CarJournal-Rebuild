# CarJournal 0.4 Cloud Preview

## What this build does

- One shared Supabase Auth email/password identity for the workshop. Every Android device uses the same account, hence all the same database rows under current RLS rules.
- Local Room v3 cache, staged upload queue, stable UUIDs, soft deletions, HTTPS transfer, private photo bucket.
- Synchronization on app opening, while app is open every ~30 seconds, after local changes, and manually via the sync icon.
- Remote machines, repairs, and photos restored on a newly signed-in phone. On temporary network loss, local data is retained.
- Cloud photos stored under `car-photos/<auth-user-id>/<vehicle-uuid>/<photo-uuid>.jpg`, protected with RLS.

## Safety first

This **debug** Cloud Preview installs as a **second app** with application ID `com.carjournal.rebuild.cloudpreview`, not over the already installed local app. Do not uninstall the existing app with your real car records! This first pilot does NOT import records from your previous app. They remain separate until we verify end-to-end cloud upload and download and deliberately implement export/import or obtain a consistent signing key.

It is an initial online sync prototype, not a verified production-grade backup system. Last successful upload wins for simultaneous edits. No true background sync when the app is fully stopped. Remote deletion propagates. Make regular independent database AND Storage backups. Tokens currently use ordinary private app preferences; strengthen using Android Keystore before real production use.

## Setup for one shared account

1. Supabase Dashboard -> carjournal -> Authentication -> Users -> Add user.
2. Create a dedicated workshop email + password, enable/mark email confirmed as appropriate. Do NOT put a password in GitHub or send it in ChatGPT.
3. Install the preview APK on two Android phones. Log in with the same email and password.
4. On phone A add a TEST car (e.g., TEST-001), a repair and a photo. Wait for status **"Облако: все данные синхронизированы"**.
5. Confirm the three rows in Supabase Table Editor (`vehicles`, `repairs`, `photos`) and a matching image in Storage -> car-photos.
6. On phone B open the app and log in with exactly the same account. Verify the car, repair, and photo are fetched.
7. Try an edit on phone B and sync on phone A. Test deletion using test records only.

## GitHub Actions

Use `.github/workflows/gradle-publish.yml` already in this complete archive. Repository structure should be:

```
.github/workflows/gradle-publish.yml
CarJournal-Rebuild/settings.gradle.kts
CarJournal-Rebuild/build.gradle.kts
CarJournal-Rebuild/app/build.gradle.kts
CarJournal-Rebuild/app/src/...
```

Replace the contents of the `CarJournal-Rebuild` folder with the full archive's directory contents **without deleting `.github`**. Existing workflow uses `working-directory: CarJournal-Rebuild`. Build runs `gradle :app:assembleDebug` and publishes `CarJournal-debug-apk`.

## Supabase setup already configured

Connected project `carjournal`, tables `public.vehicles`, `public.repairs`, `public.photos`, and private `car-photos` bucket with appropriate per-user RLS.

Only a Supabase **publishable** key is shipped in the APK, never a secret/service_role key. Do not make Storage public or disable RLS.

## Caveats and next step

For production: test sync conflicts on two devices, upgrade session storage to Android Keystore, handle multiple workshop users if separate identities are desired, implement periodic independent backups, test deletion and restoration, and design an explicit import procedure for local records from the previously installed app. Debug builds from ephemeral GitHub runners may use different signing keys; this preview intentionally uses a different application ID.
