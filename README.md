# ⚡ ZukuBoost 1.1

Herní booster pro Android přes Shizuku / Shizuku Next. Bez rootu.

## Co dělá při spuštění hry
- ukončí appky z kill listu, zakáže jim běh na pozadí a uvolní procesy v cache
- Nerušit + blokace vyskakovacích oznámení
- zamkne displej na maximální Hz (včetně Xiaomi klíčů `user_refresh_rate` / `miui_refresh_rate`, pokud existují)
- vypne spořič baterie, přepne hru do Android Game Mode „performance“
- volitelně: hrám nehlásí přehřátí (s pojistkou při 44 °C baterie), vypne animace
- overlay: FPS, průměr a minimum za relaci, CPU/GPU/baterie °C, příkon ve W, RAM, čas hraní

Po odchodu ze hry (3 s mimo hru) nebo zhasnutí displeje se vše vrátí.

## Navíc
- **Joyose (Xiaomi):** vypnutí služby, která podle cloudových profilů omezuje výkon her
- **Rozlišení hry** 100–60 % přes Game Mode intervence (Android 13+, hra to musí podporovat)
- **Kompilovat / Kompilovat vše:** AOT kompilace her v režimu `speed`
- **Přidat všechny 🎮:** automaticky najde nainstalované hry
- **Dlaždice v rychlém nastavení** pro pozastavení boostu
- automatický start po restartu telefonu

## Build bez počítače (GitHub Actions)
1. Nahraj obsah složky do repa na GitHubu.
2. Záložka **Actions** → *Build APK* (spustí se samo, nebo *Run workflow*).
3. Stáhni artefakt **ZukuBoost-apk**.

## První spuštění
1. Spusť Shizuku Next.
2. V ZukuBoostu: **Povolit přístup** → **Nastavit vše přes Shizuku**.
3. HyperOS: povol Autostart a baterii nastav na *Bez omezení*.
4. Vyber hry (**Přidat všechny 🎮**), případně kill list, a zapni **Automatický boost**.
5. Pro tyto hry vypni HyperOS Game Turbo.

## Limity (bez rootu nejde)
CPU/GPU governory a frekvence, vypnutí hardwarového thermal throttlingu, touch sampling rate.
