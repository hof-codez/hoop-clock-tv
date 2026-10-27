# Hoop Clock TV

Android TV app for the Hoop Game Clock. It opens the clock full screen with no browser, so every remote and controller button goes straight to the clock.

- Loads the live site at https://hof-hoop-clock.netlify.app, so clock updates on Netlify show up without reinstalling.
- Falls back to the copy in `app/src/main/assets/index.html` when the TV is offline.
- The Back button closes Setup or opens the mode picker. Press Back twice on the mode picker to exit.
- The screen stays on while the app is open.

## Build

Every push to `main` builds the app with GitHub Actions. The finished file is attached to the release named `latest`:

https://github.com/OWNER/REPO/releases/download/latest/hoop-clock-tv.apk

## Install on the TV

1. On the TV, install **Downloader** (by AFTVnews) from the Play Store.
2. In TV Settings, allow Downloader to install unknown apps.
3. In Downloader, enter the release link above and install.
4. Open **Hoop Clock** from the Apps row.

Updates: push a change, wait for the build, then install the new file the same way. It installs over the old one and keeps your settings.

## Updating the offline copy

Replace `app/src/main/assets/index.html` with the latest clock `index.html` and push.
