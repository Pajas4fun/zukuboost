# ⚡ ZukuBoost 5.3

A game booster for Android that runs through **Shizuku / Shizuku Next** – no root required.
Built to beat stock game modes like HyperOS Game Turbo: it does more, shows you what it does,
and puts everything back when you stop playing.

## What happens when a game starts
- **Background analysis** – sees what's running and how much memory it takes, then closes it
  (e.g. “Closing 9: TikTok, Chrome, Facebook…”)
- **Closing mode**: *All except exceptions* (default, with recommended exceptions for messaging, music and
  watches) or *Selected only*. Never closed: launcher, keyboard, accessibility services, notification
  listeners, always-on VPN, live wallpaper, system apps, voice apps, the game, Shizuku, ZukuBoost
- Closed apps are kept out of the background (app ops + `restricted` standby bucket) and cut off from
  the internet (connectivity chain 3)
- System hogs throttled (Play Store / GetApps updates, Xiaomi analytics & ads, gallery scanning, Mi Connect,
  Samsung Bixby / Galaxy Store, HeyTap, vivo store…), background dexopt cancelled, account sync paused
- **Notifications hidden completely** (Do Not Disturb policy), optional call blocking, no heads-up banners
- **Display**: Hz lock, Hz matched to the game's real FPS (90 FPS → 90 Hz for even frame pacing),
  manual Hz choice (Auto / 60 / 90 / 120 / 144), optional forced display mode, Xiaomi smart-FPS off
- Battery saver off, Android Game Mode *performance*, per-game FPS cap and render resolution
  (`device_config game_overlay` + `cmd game set`), optional game GPU driver per game
- Xiaomi: HyperOS performance profile, `MILLET_NO_RESTRICT_APP` (game and voice apps aren't frozen),
  Game Turbo hidden GPU settings unlock, Joyose control
- Wi-Fi / Bluetooth background scanning off, optional quiet network mode and location off
- **Smart cooling**: when the battery stays above the limit (default 44 °C, tuned per chip), Hz steps down,
  then 60 Hz and −15 % brightness; restored when it cools down. Warns when playing while charging

Everything is restored automatically when you leave the game (or when the screen turns off).

## Your phone
ZukuBoost detects the manufacturer, system (HyperOS / MIUI / One UI / ColorOS / OriginOS), chip
(Snapdragon / MediaTek / Exynos / Tensor), RAM and maximum refresh rate, and applies recommended settings
for that phone on first start (Settings → Your phone).

## In game
- **In-game panel** (handle at the right edge): live FPS with a graph, average / minimum / drops,
  CPU and battery heat bars, screenshot, screen recording, overlay toggle, edge blocking, RAM cleanup,
  touch lock
- **Overlay**: FPS, drops, CPU / GPU / battery temperature, power draw, RAM, CPU load and clocks
  (current / max – you can see throttling), GPU load (Adreno, MediaTek, Mali)
- **History** of every session: average FPS, minimum, drops, max temperatures

## Also
- Launch screen with an animated boost sequence
- Cleaner: RAM and app cache (`pm trim-caches`, gallery thumbnails, log)
- Gaming obstacles check (Force 4x MSAA, Don't keep activities, debug overlays…) with one-tap fix
- App freezer, boost diagnostics (reads the real state via `dumpsys`), Quick Settings tile,
  auto start after reboot
- Arena design: Chakra Petch typeface (OFL, see `licenses/`), chamfered HUD style

## Build without a computer (GitHub Actions)
1. Upload this folder to a GitHub repository (including the hidden `.github` folder).
2. **Actions** tab → *Build APK* runs automatically (or *Run workflow*).
3. Download the **ZukuBoost-apk** artifact.

The repo contains a fixed signing key, so new builds install over old ones.

## First start
1. Start Shizuku Next.
2. In ZukuBoost: **Allow access** → **Set up everything via Shizuku**.
3. HyperOS: in Developer options enable *USB debugging (Security settings)*; in the app settings enable
   Autostart and set battery to *No restrictions*.
4. Add your games (**+ All 🎮**), check the exceptions in the **Closing** tab and launch a game.
5. Turn off HyperOS Game Turbo for those games so they don't fight over settings.

## Limits (impossible without root)
CPU / GPU governors and frequencies, disabling hardware thermal throttling, touch sampling rate,
bypass charging on Xiaomi.
