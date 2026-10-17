# Google Play: the owner's checklist

Everything the repo can do is done: the app targets API 36, CI builds and validates a Play bundle, the store listing text and graphics are in [`android/fastlane/metadata/android/en-US`](../android/fastlane/metadata/android/en-US), and the privacy policy is [docs/privacy.md](privacy.md). What's left needs your Google account.

The plan: **$4.99, one-time, on Google Play.** The source stays GPL-3.0, and the free APK stays on GitHub Releases.

## Before you start: two things that can't be undone

1. **Set the price before the first release, on any track.** Once an app has been offered for free, Google won't let it become paid. You'd need a new package name. Paid can always become free later.
2. **The package name is permanent:** `io.github.ravenor907.handheldforge`.

Also good to know: selling an app makes Google show your developer contact details on the listing, including a physical address (and in the EU, as a "trader", your legal name). If you don't want your home address or name public, use a PO box or virtual mailbox, or sell through an LLC with an organization account.

## 1. Accounts (one time)

1. Create a Play developer account at [play.google.com/console](https://play.google.com/console) ($25 once) and finish identity verification.
2. Set up a **payments profile** (Play Console > Setup > Payments profile). Needed to sell anything. Add your tax info.

## 2. Upload key (one time)

The key was generated for you (alias `upload`, valid until 2056). In GitHub: repo > Settings > Secrets and variables > Actions > **New repository secret**, add:

- `PLAY_UPLOAD_KEY_B64`
- `PLAY_UPLOAD_KEY_PASSWORD`

The values are in `PLAY_UPLOAD_KEY_SECRETS.txt`. Back up that file and `handheld-forge-upload.p12` somewhere private. Google re-signs the app for users (Play App Signing), so this key only proves uploads come from you; if it's ever lost, Google can reset it.

The next push to `main` that bumps the version builds `app-release.aab`, signed with it, in the Actions run's **play-bundle** artifact.

## 3. Create the app

Play Console > Create app: name **Handheld Forge**, language English (US), **App**, **Paid**.

Then Monetize > App pricing: **$4.99** (Google converts it for other countries).

## 4. Store listing

Grow > Store presence > Main store listing. Everything is in `android/fastlane/metadata/android/en-US/`:

| Field | File |
| --- | --- |
| App name | `title.txt` |
| Short description | `short_description.txt` |
| Full description | `full_description.txt` |
| App icon (512 x 512) | `images/icon.png` |
| Feature graphic (1024 x 500) | `images/featureGraphic.png` |
| Phone screenshots | `images/phoneScreenshots/1.png` to `7.png` |

Category: **Tools**. Contact email: whichever address you want public.

## 5. App content (Policy > App content)

| Section | Answer |
| --- | --- |
| Privacy policy | `https://github.com/Ravenor907/Handheld-Forge/blob/main/docs/privacy.md` |
| Ads | No ads |
| App access | All functionality is available without special access. Add a note: "Needs a USB drive connected with a USB-C adapter (USB OTG). Tap a system, pick the drive, tap Create boot drive." |
| Content rating | Category: Utility / Productivity / Communication / Other. Answer **No** to everything (violence, sexuality, language, controlled substances, gambling, user interaction, sharing location, digital purchases). Expected rating: Everyone / PEGI 3. |
| Target audience | **18 and over** (keeps it out of the Families program, which it isn't built for) |
| Data safety | **Does your app collect or share any of the required user data types? No.** (Downloads come straight from each publisher; the app has no servers. See the privacy policy.) |
| Government, financial, health, news | Not applicable |
| Foreground services | See below |

### Foreground service declarations

Both types the app uses need a short description and a video. Record the video on your phone with Android's built-in screen recorder: open the app, pick a system and your USB drive, tap **Create boot drive**, show the progress screen and notification, done. Upload it to YouTube as **Unlisted** and use that link for both.

**Connected device** (use case: *Continuous data transfer to an external device*)
- What it does: "Writes a bootable operating system image to a USB drive the user connected and chose, then reads the whole drive back to verify it. Started only when the user taps Create boot drive."
- Impact if interrupted: "The USB drive is left half written and won't boot; the user has to start over."

**Data sync** (use case: *Network transfer: download/upload*, user-initiated)
- What it does: "Downloads the operating system image (several GB) the user asked for, right before writing it to their USB drive. Only runs after the user taps Create boot drive."
- Impact if interrupted: "The multi-GB download stops; it resumes next time but the drive isn't made."

## 6. Testing (personal accounts created after November 13, 2023)

Google wants a **closed test with at least 12 testers, opted in for 14 days in a row**, before you can apply for production.

1. Testing > Closed testing > create a track, add your testers' Google accounts (an email list or a Google Group).
2. Upload the `app-release.aab` from the latest **play-bundle** artifact. The first upload has to be done by hand in Play Console.
3. Testers of a paid app may be charged. Give them **promo codes** instead (Monetize > Promo codes; up to 500 per quarter for paid apps), or refund them from Order management.
4. After 14 days with 12+ testers: Dashboard > **Apply for production**.

## 7. Production

Create a production release with the same bundle (or a newer one), roll out, done. Version 1.0 is a good moment for it.

## Optional: automatic uploads from CI

After the first manual upload, CI can send each new version to Play by itself:

1. In Google Cloud, create a project, enable the **Google Play Android Developer API**, create a service account and download a JSON key.
2. Play Console > Users and permissions > Invite the service account's email, with permission to release to testing tracks (and production, if you want).
3. Add the JSON as the repo secret `PLAY_SERVICE_ACCOUNT_JSON`.
4. Repo variables (Settings > Secrets and variables > Actions > Variables): `PLAY_TRACK` = `internal`, `alpha` (closed testing) or `production`; `PLAY_STATUS` = `draft` until the app is live, then `completed`.

From then on, every version bump on `main` publishes the GitHub APK and uploads the bundle to that Play track.

## Keeping both

The Play version and the GitHub APK are signed with different keys, so Android won't update one with the other. Someone switching has to uninstall first. The Play version updates itself; the GitHub APK is updated by downloading a new one.
