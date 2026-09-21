<div align="center">

# [SBW] NPC Squads

**An NPC squad addon for [SuperbWarfare](https://github.com/Mercurows/SuperbWarfare)** — recruit, deploy,
and command your own AI-driven infantry, mortar, tank, and drone squads.

![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1-orange)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetbrains-7F52FF?logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)
![Status](https://img.shields.io/badge/status-active%20dev-yellow)

[English](#english) · [Русский](#русский)

</div>

---

## English

### What is this

[SBW] NPC Squads lets you recruit NPCs with a single tool, form them into squads, and command them like
a small AI-controlled army: riflemen, medics, snipers, machine gunners, grenadiers, mortar crews,
tank crews, and kamikaze-drone operators. Squads take cover, dig in under fire, avoid shooting
their own side, relay spotted enemies to the rest of their faction, ride and crew vehicles, and
follow orders — Attack, Defend, Patrol, Move — from a quick-command HUD or by pointing and
clicking.

### Features

- **One tool, two modes** — Recruit mode opens a GUI to pick class, rank, faction and squad
  preset and deploy it; Command mode selects squads and issues orders. Ctrl+right-click switches
  between them.
- **Squad presets** — Single, 5 (riflemen + medic), 7 (riflemen, sniper, machine gunner, medic),
  16 (large mixed squad), Mortar Crew, Tank Crew, Drone Team. 5 and 7 can deploy with a support
  vehicle; the tank crew preset lets you pick the model (ZTZ-99A / T-90A / M1A2).
- **8 factions**, each with its own uniform, team, and color — pick freely, including OPFOR
  test squads.
- **Combat AI** — cover-seeking, suppression, digging in as a last resort, partial-cover firing
  positions, friendly-fire avoidance modeled on the actual firing cone (not just a straight line),
  and faction-wide relayed contact awareness.
- **Vehicles** — NPC crews drive, escort, and gun for their squad; a squad far from its objective
  will commandeer nearby vehicles on its own. Players can now ride along in a seat next to an
  allied NPC driver instead of bumping them out.
- **Drone operators** — fly a kamikaze FPV drone at a spotted target (uses the SBW Drone Warfare
  addon's drone if installed, falls back to a stock SBW drone otherwise); other NPCs will shoot
  down or take cover from a hostile drone and alert the squad.
- **Helicopters** — NPC pilots fly a Mi-28 gunship or an AH-6 that carries the squad, takes off,
  picks a landing zone and puts them down.
- **Barracks** — a placeable garrison point, configured like the deploy tool, that deploys a squad
  and restocks its losses over time.
- **Quick-command HUD** — a lightweight panel (default key `B`) to pick a squad and an order
  without opening a menu.

### Requirements

- Minecraft 1.21.1 + NeoForge
- Kotlin for Forge (NeoForge build) — this mod is written in Kotlin and loads through it
- [SuperbWarfare](https://github.com/Mercurows/SuperbWarfare) — this addon builds directly on its
  weapons, vehicles, and combat systems and won't do anything without it
- SmartBrainLib — the behavior-tree AI framework every NPC's combat/movement logic runs on
- Optional: the SBW Drone Warfare addon, for the drone operator class to use its FPV drone model

### Installation

1. Install NeoForge, Kotlin for Forge, SuperbWarfare, and SmartBrainLib first.
2. Grab the latest jar from the [Releases](../../releases) page.
3. Drop it into your `mods/` folder.

Recommended alongside: [GeckolibBetterFPS](https://www.curseforge.com/minecraft/mc-mods/geckolibbetterfps)
— noticeably better frame rate when a lot of NPCs are on screen.

### Where everything is

| Thing | Where to get it | What it does |
|---|---|---|
| **Squad Command Tool** (`sbwnpc:squad_tool`) | Creative, *Superb Warfare Items* tab — or `/give @s sbwnpc:squad_tool` | Deploys NPCs and commands squads. No survival recipe yet. |
| **Barracks** (`sbwnpc:barracks`) | Same tab — or `/give @s sbwnpc:barracks` | A placed block that garrisons a squad and keeps it at strength. |
| **Quick-command HUD** | Key `B`, rebindable under controls category *SBW NPC Squads* | Pick a squad and an order without opening a menu. |

On first use the tool asks you to pick a faction once. That is only your own default — you can
still deploy any of the eight afterwards, including hostile ones to fight against.

### Using the tool

Two modes, switched with **Ctrl + right-click on air**. The current one is on the item tooltip.

**Recruit mode — what gets deployed**

- **Right-click air** opens the deploy config: preset, class (Single only), rank, faction, and the
  vehicle or airframe where the preset has one.
- **Right-click a block** deploys it there, lined up abreast and facing you.
- Presets: `Single`, `5: Riflemen`, `7: Standard`, `16: Large`, `Mortar Crew`, `Tank Crew`
  (ZTZ-99A / T-90A / M1A2), `Drone Team`, `Heli Crew` — Mi-28 gunship or AH-6 transport, and the
  airframe decides which crew comes with it. `5` and `7` can bring a LAV-25 / LAV-150 / BMP-2.
- Ranks go `RECRUIT → REGULAR → VETERAN → ELITE`: more health, tighter spread, quicker reactions.
- Anything bigger than a single NPC is formed into a squad automatically, holding where it landed.

**Command mode — what they do**

- **Right-click an NPC** selects it; one that is already in a squad selects the whole squad.
  Shift + right-click clears the selection.
- **Right-click air** opens the squad screen.
- **Right-click a block** with a squad selected sets that squad's objective.
- **Right-click a hostile** focuses the selected squad on it — hunted under Attack, guarded
  against under Defend.

### The squad screen

One row per squad, scrollable, with the footer buttons under the list:

- **Order** cycles through the orders that squad can actually carry out.
- **Objective** arms a click: the next block you right-click is where the squad works from.
- **Focus** arms the same thing on an entity instead.
- **R** renames · **X** disbands (the NPCs stay, the squad doesn't) · **DEL** deletes the squad
  and everything in it, vehicles included.
- **Routes** opens patrol routes: add one, right-click blocks to drop waypoints, finish, then
  assign it to a squad. A squad with a route walks it under Patrol.

There is no limit on how many squads you run. The HUD's number keys reach the first nine;
disbanding one moves the rest up into the freed numbers.

### Orders

| Order | What it means | Who can be given it |
|---|---|---|
| **Defend** | Hold near the objective, don't chase far | Everyone except tank crews |
| **Patrol** | Wander the area, or walk the assigned route | Infantry |
| **Attack** | Advance on the objective, fight through what's in the way | Everyone except transports and tank crews |
| **Move** | Walk there calmly, then hold | Everyone |
| **Barrage** | Shell a 40-block area around the objective instead of one point | Mortar crews |

Tank crews only take **Move** — they fight from the tank by themselves. A Mi-28 gunship takes
Attack / Defend / Move and holds a standoff hover 20 blocks off its target; an AH-6 transport
takes Defend (patrols low with its door gunners, and calls contacts in to the whole faction) and
Move (flies the squad there and lands).

### The Barracks

Right-click a Barracks you placed to configure it the same way the tool is configured, then press
**Deploy garrison**. It deploys that squad and keeps it at strength, replacing losses over time.
Re-deploying replaces the garrison standing there. Only whoever placed it can configure it.

### Worth knowing

- Squads take cover, dig in when badly hurt, and won't fire through a squadmate — or through a
  parked vehicle.
- A squad with a distant objective commandeers a vehicle nearby and drives. You can ride along in
  a free seat without bumping the NPC driver out.
- Mortar crews only shell what their own side has actually seen. Out of reach of the target, they
  break the mortar down, carry it closer and set it back up.
- Each piece of an NPC's kit drops with a 30% chance when a player kills it.

### How this mod was made

This is a hobby project, built purely for fun. The entire codebase was written by an AI (Claude
Code) — architecture, features, bug fixes, all of it — while the meatbags did the actual
playtesting, including multiplayer sessions, and sent back bug reports on whatever broke.

Pull requests and issues are welcome and will be looked at whenever there's free time — no
guaranteed response time.

### Skins

All eight faction skins were redesigned with original military uniforms while keeping each mob's
head pixel-identical, so they stay instantly recognizable. See [`docs/skins/`](docs/skins/) for
the generation notes.

![Skin preview](docs/skins/preview.png)

*Gameplay screenshots are still needed — if you'd like to contribute some, open an issue or a PR.*

### License

[GNU GPL v3.0](LICENSE) — see the `LICENSE` file for the full text.

---

## Русский

### Что это

[SBW] NPC Squads позволяет одним инструментом вербовать NPC, собирать их в отряды и командовать ими,
как небольшой армией под управлением ИИ: автоматчики, медики, снайперы, пулемётчики, гренадёры,
миномётные и танковые расчёты, операторы дронов-камикадзе. Отряды прячутся в укрытия, окапываются
под огнём, не стреляют по своим, передают информацию о замеченных врагах остальной фракции, ездят
и воюют в технике, выполняют приказы — Атака, Оборона, Патруль, Марш — через быструю HUD-панель
или простым наведением и кликом.

### Возможности

- **Один инструмент, два режима** — режим «Вербовка» открывает GUI выбора класса, ранга, фракции
  и пресета отряда и деплоит его; режим «Командование» выделяет отряды и отдаёт приказы.
  Переключение — Ctrl + ПКМ.
- **Пресеты отрядов** — Single, 5 (автоматчики + медик), 7 (автоматчики, снайпер, пулемётчик,
  медик), 16 (большой смешанный отряд), Mortar Crew, Tank Crew, Drone Team. Пресеты 5 и 7 можно
  задеплоить вместе с техникой поддержки; танковый расчёт — с выбором модели (ZTZ-99A / T-90A /
  M1A2).
- **8 фракций**, у каждой своя форма, команда и цвет — выбор свободный, в том числе для тестовых
  отрядов противника.
- **Боевой ИИ** — поиск укрытий, подавление, окапывание как последний рубеж, позиции с частичным
  укрытием тела при стрельбе, защита от дружественного огня по реальному конусу разброса выстрела
  (а не по прямой линии), и осведомлённость всей фракции о замеченных врагах.
- **Техника** — экипажи NPC водят, сопровождают и стреляют за свой отряд; отряд, которому далеко
  до цели, сам реквизирует ближайшую технику. Игрок теперь может подсесть пассажиром к союзному
  NPC-водителю, а не выкидывать его при посадке.
- **Операторы дронов** — запускают дрон-камикадзе по замеченной цели (использует дрон аддона SBW
  Drone Warfare, если он установлен, иначе — обычный дрон SBW); остальные NPC сбивают вражеский
  дрон или прячутся от него и поднимают тревогу в отряде.
- **Вертолёты** — NPC-пилоты водят Ми-28 или AH-6: взлетают, возят отряд, выбирают площадку и
  высаживают.
- **Казарма** — устанавливаемая точка гарнизона с теми же настройками, что и у инструмента:
  разворачивает отряд и со временем восстанавливает его потери.
- **Быстрое командование через HUD** — лёгкая панель (по умолчанию клавиша `B`) для выбора отряда
  и приказа без открытия меню.

### Требования

- Minecraft 1.21.1 + NeoForge
- Kotlin for Forge (сборка для NeoForge) — мод написан на Kotlin и грузится через него
- [SuperbWarfare](https://github.com/Mercurows/SuperbWarfare) — аддон построен прямо поверх его
  оружия, техники и боевой системы и без него не работает
- SmartBrainLib — фреймворк поведенческих деревьев, на котором держится весь боевой/навигационный
  AI NPC
- Опционально: аддон SBW Drone Warfare — чтобы оператор дрона использовал его модель FPV-дрона

### Установка

1. Сначала установи NeoForge, Kotlin for Forge, SuperbWarfare и SmartBrainLib.
2. Скачай последний jar со страницы [Releases](../../releases).
3. Положи его в папку `mods/`.

Рекомендуется рядом: [GeckolibBetterFPS](https://www.curseforge.com/minecraft/mc-mods/geckolibbetterfps)
— заметно поднимает FPS, когда на экране много NPC.

### Что где лежит

| Что | Где взять | Зачем |
|---|---|---|
| **Squad Command Tool** (`sbwnpc:squad_tool`) | Креатив, вкладка *Superb Warfare Items* — или `/give @s sbwnpc:squad_tool` | Деплоит NPC и командует отрядами. Крафта пока нет. |
| **Barracks** (`sbwnpc:barracks`) | Та же вкладка — или `/give @s sbwnpc:barracks` | Ставится блоком, держит гарнизон и восполняет его потери. |
| **HUD быстрых команд** | Клавиша `B`, переназначается в категории *SBW NPC Squads* | Выбрать отряд и приказ, не открывая меню. |

При первом использовании инструмент один раз попросит выбрать фракцию. Это только твой дефолт —
деплоить дальше можно любую из восьми, в том числе враждебную, чтобы было с кем воевать.

### Инструмент

Два режима, переключаются **Ctrl + ПКМ по воздуху**. Текущий написан в подсказке предмета.

**Режим «Вербовка» — что деплоится**

- **ПКМ по воздуху** открывает конфиг: пресет, класс (только для Single), ранг, фракция и техника
  или тип вертолёта — там, где пресет это поддерживает.
- **ПКМ по блоку** деплоит на это место, шеренгой, лицом к тебе.
- Пресеты: `Single`, `5: Riflemen`, `7: Standard`, `16: Large`, `Mortar Crew`, `Tank Crew`
  (ZTZ-99A / T-90A / M1A2), `Drone Team`, `Heli Crew` — Ми-28 или AH-6, и выбранный борт
  определяет, какой экипаж с ним выйдет. С пресетами `5` и `7` можно выдать LAV-25 / LAV-150 /
  БМП-2.
- Ранги идут `RECRUIT → REGULAR → VETERAN → ELITE`: больше здоровья, меньше разброс, быстрее
  реакция.
- Всё, что больше одного NPC, автоматически собирается в отряд и остаётся оборонять точку высадки.

**Режим «Командование» — что они делают**

- **ПКМ по NPC** выделяет его; если он уже в отряде — выделяется весь отряд. Shift + ПКМ сбрасывает
  выделение.
- **ПКМ по воздуху** открывает экран отрядов.
- **ПКМ по блоку** с выделенным отрядом ставит ему цель.
- **ПКМ по врагу** назначает его фокус-целью: при «Атаке» отряд его преследует, при «Обороне» —
  сторожит.

### Экран отрядов

По строке на отряд, со скроллом; кнопки внизу — под списком.

- **Order** переключает приказы, доступные именно этому отряду.
- **Objective** взводит клик: следующий ПКМ по блоку станет точкой, от которой отряд работает.
- **Focus** — то же самое, но по существу.
- **R** переименовать · **X** расформировать (NPC остаются, отряда нет) · **DEL** удалить отряд
  вместе со всеми, включая технику.
- **Routes** — маршруты патрулирования: добавить, ПКМ по блокам расставить точки, завершить и
  назначить отряду. Отряд с маршрутом ходит по нему на приказе «Патруль».

Лимита на количество отрядов нет. Цифровые клавиши HUD достают до первых девяти; при
расформировании одного остальные подтягиваются на освободившиеся номера.

### Приказы

| Приказ | Что значит | Кому можно дать |
|---|---|---|
| **Defend** | Держаться у точки, далеко не гоняться | Всем, кроме танковых экипажей |
| **Patrol** | Ходить по округе или по назначенному маршруту | Пехоте |
| **Attack** | Наступать на точку, пробиваясь через то, что мешает | Всем, кроме транспорта и танковых экипажей |
| **Move** | Спокойно дойти и встать | Всем |
| **Barrage** | Обрабатывать площадь радиусом 40 блоков вокруг точки, а не одну точку | Миномётным расчётам |

Танковый экипаж принимает только **Move** — дальше он воюет из танка сам. Ми-28 берёт
Attack / Defend / Move и висит в 20 блоках от цели, а не над ней; AH-6 берёт Defend (патрулирует
низко со стрелками на скамьях и раздаёт контакты всей фракции) и Move (везёт отряд и садится).

### Казарма

ПКМ по поставленной казарме открывает тот же конфиг, что и у инструмента, дальше — **Deploy
garrison**. Казарма разворачивает отряд и держит его в составе, восполняя потери со временем.
Повторный деплой заменяет стоящий гарнизон. Настраивать может только тот, кто её поставил.

### Что стоит знать

- Отряды занимают укрытия, окапываются при тяжёлых ранениях и не стреляют сквозь своих — и сквозь
  стоящую технику тоже.
- Отряд с далёкой целью сам реквизирует ближайшую технику и поедет. Подсесть в свободное место
  можно, не выкидывая NPC-водителя.
- Миномётчики бьют только по тому, что их сторона реально видела. Если до цели не достают —
  собирают миномёт, подходят ближе и разворачиваются заново.
- С убитого игроком NPC каждый предмет снаряжения падает с шансом 30%.

### Как это сделано

Это хобби-проект, сделанный просто ради развлечения. Весь код написан ИИ (Claude Code) —
архитектура, фичи, багфиксы, всё — а тестированием (в т.ч. в мультиплеере) занимаются кожаные
мешки, которые потом присылают баг-репорты о том, что сломалось.

PR и issue приветствуются и будут рассмотрены в свободное время — без гарантий по срокам.

### Скины

Все восемь фракционных скинов переоформлены в оригинальной военной форме с сохранением исходной
головы каждого моба пиксель-в-пиксель, чтобы они оставались мгновенно узнаваемыми. Подробности
генерации — в [`docs/skins/`](docs/skins/).

![Превью скинов](docs/skins/preview.png)

*Скриншотов геймплея пока нет — если хочешь помочь, открой issue или PR.*

### Лицензия

[GNU GPL v3.0](LICENSE) — полный текст в файле `LICENSE`.
