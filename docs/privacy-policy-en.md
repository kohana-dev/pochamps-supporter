# Privacy Policy — PokeChamps Supporter (포챔스 서포터)

**Last updated: 2026-07-05**

> Korean (한국어): [privacy-policy.md](privacy-policy.md) · [privacy-policy.html](privacy-policy.html)

## Summary (one line)

**This app collects no personal data and transmits no data to any server.** All processing happens entirely on your device. There are no accounts, no logins, no ads, and no tracking.

---

## 1. Data we collect

**None. No data collected.**

PokeChamps Supporter does not collect, store, or transmit any of the following:

- Personally identifying information (name, email, phone number, etc.)
- Location data
- Access to contacts, photos, or files
- Advertising identifiers or device identifiers
- Usage statistics, analytics, or behavioral tracking
- Account information (**the app has no account or login concept at all**)

---

## 2. Scope of screen capture (MediaProjection) use

The app uses Android's screen capture (MediaProjection) permission to display information over the game. This permission is only active after you explicitly consent each time.

- Captured screen frames are processed **in device memory only**, and are used **solely to read the opponent's Pokémon name text shown on screen via on-device OCR (optical character recognition).**
- **Captured images are never saved (0) and never transmitted anywhere (0).** Each frame is immediately discarded when the next one arrives.
- OCR is performed by the Google ML Kit on-device models bundled inside the app and **does not access the internet.**
- The app does not read game memory, communicate with game servers, or automate input. It uses **only information already visible on screen.**

---

## 3. Scope of internet use

The app may use the internet for exactly one purpose:

- **Pokémon data update (optional, manual)**: Only when you press the "Update data" button in Settings, the app downloads **static JSON files** (Pokémon types, abilities, usage stats, etc.) from GitHub Pages (static file hosting). No personal data is sent to any server during this process — it is a plain file download.
  - GitHub's servers may keep standard web request logs (such as IP addresses) as part of serving those files, but that is **under GitHub's own infrastructure and privacy policy**. The app developer neither accesses nor collects those logs. Please refer to GitHub's privacy policy for how GitHub handles that.
- If you never press that button, the app makes no internet connection and **runs fully offline using only the data bundled inside the app.**

---

## 4. Crash (error) logs

If the app terminates unexpectedly (crashes), it stores error information (stack trace, app version, device model) **locally, on the device's internal storage only**, to help diagnose the problem.

- These logs are **never transmitted automatically.** They are not sent to the developer or anyone else automatically.
- Only when you choose to share them yourself — via Settings > Advanced > "Share bug report" — is the log text handed to the app you pick (email, messaging, etc.). **Whether to share, and with whom, is entirely your decision.**
- Locally stored crash logs are kept for the 5 most recent crashes only, and contain no personally identifying information (the device model is generic information, e.g. "Galaxy S23").

---

## 5. Data sharing / selling

Because the app collects no data, there is **no data to share or sell** with third parties. It contains no advertising networks, analytics SDKs, or tracking libraries whatsoever.

---

## 6. Children's privacy

The app does not collect personal data from any user, and therefore does not collect personal data from children either.

---

## 7. Permissions and reasons

| Permission | Reason |
|---|---|
| Display over other apps (SYSTEM_ALERT_WINDOW) | Show the information card overlay on top of the game |
| Screen capture (MediaProjection, consent each time) | Read opponent name text via on-device OCR (no saving, no transmission) |
| Foreground service | Keep the status-bar notification alive while capturing (Android requirement) |
| Notifications (POST_NOTIFICATIONS) | Status-bar notification indicating capture is active |
| Internet (INTERNET) | Only to download static JSON when you press "Update data" |

---

## 8. Changes to this policy

If this policy changes, the "Last updated" date on this page will be revised. Significant changes will be announced through app update notes.

---

## 9. Contact

For questions, feedback, or reports, please use the Issues page of the GitHub repository.

- GitHub Issues: <https://github.com/kohana-dev/pochamps-supporter/issues>

---

*This is an unofficial, fan-made tool and is not affiliated with Nintendo, Creatures Inc., GAME FREAK inc., The Pokémon Company, or their affiliates. All related trademarks and copyrights belong to their respective owners.*
