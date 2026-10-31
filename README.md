# ⚡ ZukuBoost 2.5

Herní booster pro Android přes Shizuku / Shizuku Next. Bez rootu.

## Co dělá při spuštění hry
- ukončí appky z kill listu, zakáže jim běh na pozadí a uvolní procesy v cache
- Nerušit + blokace vyskakovacích oznámení
- zamkne displej na maximální Hz (včetně Xiaomi klíčů `user_refresh_rate` / `miui_refresh_rate`, pokud existují)
- vypne spořič baterie, přepne hru do Android Game Mode „performance“
- volitelně: hrám nehlásí přehřátí (s pojistkou při 44 °C baterie), vypne animace
- overlay: FPS, průměr a minimum za relaci, CPU/GPU/baterie °C, příkon ve W, RAM, čas hraní

Po odchodu ze hry (3 s mimo hru) nebo zhasnutí displeje se vše vrátí.

## Proti propadům FPS (1.2)
- **FPS limit pro každou hru** (Android 13+): stabilní snímky místo skákání a přehřívání
- **Hz podle FPS limitu:** displej na násobku limitu (45 → 90 Hz) = rovnoměrné snímky
- **Utlumení systémových žroutů:** aktualizace z Obchodu Play / GetApps, analytika a reklamy Xiaomi, skenování galerie, optimalizace appek na pozadí
- **Vypnutí synchronizace účtů** během hraní
- **Stabilní výkon (fixed performance)** tam, kde ho telefon podporuje
- **Počítadlo propadů** v overlayi a **Historie** her (ø FPS, minimum, propady, max. teploty)

## Výkon CPU/GPU bez rootu (1.3)
- **Xiaomi výkonový profil:** během hraní `power_mode high` + `speed_mode 1`, po hře se vrátí (experimentální)
- **Skrytý výkonový režim Xiaomi:** tlačítko otevře systémový výběr Úspora / Vyvážený / Výkon
- **Kontrola zápisu nastavení:** HyperOS vyžaduje v Možnostech pro vývojáře zapnout
  *Ladění USB (Bezpečnostní nastavení)*, jinak shell nemůže měnit systémová nastavení

## 2.5 – podle hloubkového průzkumu
- FPS limit/rozlišení teď i přes `cmd game set` (druhá cesta vedle `device_config game_overlay`)
- Během hry `device_config set_sync_disabled_for_tests persistent` – server nepřepíše herní profil (po hře se vrátí)
- **HyperOS: nezmrazovat hru a hlasové appky** (`MILLET_NO_RESTRICT_APP`)
- **Vypnutí skenování Wi-Fi/Bluetooth** na pozadí (méně skoků pingu)
- Kill list navíc do standby bucketu `restricted` (po hře se vrátí původní)
- `speed_mode` + `speed_mode_enable` a GPU volba v Game Turbu se obnovují při každé hře (cloud je resetuje)
- **Zmrazování appek v cache** (`cached_apps_freezer`, po restartu)
- **Diagnostika boostu** – čte skutečný stav přes `dumpsys`
- Opravené popisy: fixed performance = stabilní takty, ne max výkon; skrytí přehřátí = experimentální

## 2.4
- **Odříznutí internetu appkám na pozadí** během hry (connectivity chain 3 – stejný mechanismus jako ShizuWall)

## 2.3
- **Síť: klidný režim** – během hry vypne „mobilní data vždy aktivní“, Wi-Fi asistenta Xiaomi a skenování okolí

## 2.2
- **Xiaomi chytré FPS** (`is_smart_fps`) se během hry vypne – displej nesnižuje Hz sám od sebe
- **Ochrana hlasových appek** (Discord, WhatsApp, Telegram, Skype, Teams) – během hraní je systém neuspí
- Workflow na nejnovějších verzích akcí (checkout v7, setup-java v5, setup-gradle v6, upload-artifact v7)

## 2.1
- **Displej:** během hry vypne automatický jas a režim čtení (doporučení Xiaomi)
- **Vypnutí polohy** během hraní (volitelné)
- **Odemknutí skrytého GPU nastavení v Game Turbu** (`GPUTUNER_SWITCH`)

## 2.0 – nový herní vzhled
- **Domů (Game Space):** výběr hry, profil hry, velké tlačítko **SPUSTIT S BOOSTEM**
  (úklid paměti → zapnutí auto boostu → spuštění hry), živá teplota a volná RAM
- Spodní navigace: Domů · Hry · Kill list · Historie · **Nastavení** (vlastní kategorie se sekcemi
  Připojení, Automatika, Při hraní, Xiaomi, Obnova)

## 1.4
- FPS limit a rozlišení teď přes `device_config game_overlay` (postup z dokumentace Androidu), záloha konfigurace výrobce
- **Herní GPU ovladač** pro každou hru (jen telefony s aktualizovatelným ovladačem)

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
