# Карта проекта

Справочник каталогов, модулей и точек входа. Архитектура тактики — [TACTICS-ARCHITECTURE.md](TACTICS-ARCHITECTURE.md), оформление кода — [CODE-STYLE.md](CODE-STYLE.md).

## Откуда начать

Код разделён на модули: общий `common/` (маршруты и HTN-ядро `ai/planning`, `ai/coordination`, `ai/tactics`) и `neoforge/` (всё остальное, включая адаптер `ai/integration`). Большая часть кода — Kotlin в `neoforge/src/main/kotlin/com/sbwnpc/squad/`. Java используется для mixin-вставок в Minecraft и SuperbWarfare.

| Путь от корня репозитория | Содержимое |
|---|---|
| [src/main/kotlin/com/sbwnpc/squad/](../neoforge/src/main/kotlin/com/sbwnpc/squad/) | Основной код мода |
| [src/main/java/com/sbwnpc/squad/mixin/](../neoforge/src/main/java/com/sbwnpc/squad/mixin/) | Вставки в классы Minecraft и SuperbWarfare |
| [src/main/resources/](../neoforge/src/main/resources/) | Текстуры, модели, переводы, metadata и список mixin |
| [neoforge/src/test/kotlin/com/sbwnpc/squad/](../neoforge/src/test/kotlin/com/sbwnpc/squad/) | Автоматические проверки, расположенные по тем же модулям |
| [gradle.properties](../gradle.properties) | Версия мода, Minecraft и зависимостей |
| [build.gradle.kts](../build.gradle.kts), [neoforge/build.gradle.kts](../neoforge/build.gradle.kts), [common/build.gradle.kts](../common/build.gradle.kts) | Корневые команды; сборка NeoForge, сцены, ktlint и `BuildFlags`; проверка общего модуля |
| [settings.gradle.kts](../settings.gradle.kts) | Модули `common` и `neoforge`, `build-logic` и выбор входа SuperbWarfare |
| `SuperbWarfare/` | Отдельный локальный репозиторий зависимости; не входит в git этого проекта |
| `build/` | Результаты сборки, отчёты тестов и сгенерированные файлы |
| [PLAN-1.0.0.md](../PLAN-1.0.0.md) | План версии; описание намерений, а не подтверждение реализации |
| [AGENTS.md](../AGENTS.md) | Правила работы с репозиторием и технические особенности |

## Основные модули

| Модуль | За что отвечает | Точки входа |
|---|---|---|
| Инициализация | Подключение портов, регистраций и конфигов | [SquadMod.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/SquadMod.kt), [init/](../neoforge/src/main/kotlin/com/sbwnpc/squad/init/) |
| Серверные события | Общие обновления и очистка временных данных между мирами | [ServerTick.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/ServerTick.kt), [ServerLifecycle.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/ServerLifecycle.kt) |
| NPC | Сущность, её Brain, состояние, поза, восстановление после застревания | [entity/NpcEntity.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/NpcEntity.kt), [entity/NpcStuckRecovery.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/NpcStuckRecovery.kt) |
| Индивидуальный AI | Стрельба, укрытия, лечение, снабжение, движение, техника | [entity/ai/](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/) |
| Бой | Общие расчёты видимости, угроз, огня, формаций и безопасных позиций | [combat/](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/) |
| Тактика отряда | HTN-ядро: задачи, операции, роли штурма, обязанности бойцов | [common/.../ai/](../common/src/main/kotlin/com/sbwnpc/squad/ai/), адаптер [ai/integration/](../neoforge/src/main/kotlin/com/sbwnpc/squad/ai/integration/) |
| Исполнение тактики | Снимок отряда, поиск позиций, маршруты, дистанции | [combat/tactics/](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/tactics/), см. [TACTICS-ARCHITECTURE.md](TACTICS-ARCHITECTURE.md) |
| Отряды и приказы | Состав, владелец, приказы, выделение, развёртывание, сохранение | [squad/Squad.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/squad/Squad.kt), [squad/SquadManager.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/squad/SquadManager.kt) |
| Опыт бойцов | Начисление, вклад в поражение цели, боевые раны, пороги рангов | [experience/](../neoforge/src/main/kotlin/com/sbwnpc/squad/experience/) |
| Описания NPC | Классы, ранги, пресеты, фракции и модели техники | [npc/](../neoforge/src/main/kotlin/com/sbwnpc/squad/npc/) |
| Фракции и дипломатия | Команды scoreboard, враждебность, союзы | [team/](../neoforge/src/main/kotlin/com/sbwnpc/squad/team/), [squad/PlayerFactionRegistry.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/squad/PlayerFactionRegistry.kt) |
| Длинные маршруты | Планирование движения по местности и ограничения вычислений | [route/](../common/src/main/kotlin/com/sbwnpc/squad/route/) |
| Техника | Вода, воздушное пространство, вертолёты, питание и принадлежность водителя | [vehicle/](../neoforge/src/main/kotlin/com/sbwnpc/squad/vehicle/) |
| Казармы и Supply | Блоки, очередь пополнения, наборы снаряжения и респавн | [block/](../neoforge/src/main/kotlin/com/sbwnpc/squad/block/), [block/entity/](../neoforge/src/main/kotlin/com/sbwnpc/squad/block/entity/) |
| Предмет управления | Использование инструмента, конфигурация и выдача игроку | [item/SquadToolItem.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/item/SquadToolItem.kt) |
| Сеть | Сообщения клиент ↔ сервер и серверная обработка действий | [network/Payloads.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/network/Payloads.kt), [network/ModNetwork.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/network/ModNetwork.kt) |
| Интерфейс | Экраны, HUD, клавиши, клиентские снимки состояния, рендер NPC | [client/](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/), [client/screen/](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/screen/) |
| Карта | Серверная выдача данных, клиентское состояние, JourneyMap | [map/MapFeed.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/map/MapFeed.kt), [integration/journeymap/](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/journeymap/) |
| Контракты с SBW | Интерфейсы оружия, транспорта, гранат, снаряжения и их подключения | [domain/port/](../neoforge/src/main/kotlin/com/sbwnpc/squad/domain/port/) |
| Реализация интеграции | Вызовы конкретных классов и API SuperbWarfare | [integration/sbw/](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/sbw/) |
| Настройки и диагностика | Конфиги, группы логов, измерение нагрузки | [config/](../neoforge/src/main/kotlin/com/sbwnpc/squad/config/), [combat/DebugFlags.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/DebugFlags.kt), [util/PerfProbe.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/util/PerfProbe.kt) |

## Где менять конкретную механику

### Поведение NPC и бой

В [NpcEntity.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/NpcEntity.kt) ищи `getSensors`, `getCoreTasks`, `getIdleTasks`, `getFightTasks`: здесь поведения подключаются к Brain. Реализации находятся в `entity/ai/`. Общие вычисления вынесены в `combat/`.

| Что нужно изменить | Где смотреть |
|---|---|
| Выбор и обнаружение противника | [SquadTargetSensor.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/SquadTargetSensor.kt), [DetectionSightline.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/DetectionSightline.kt), [Vision.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/Vision.kt) |
| Выбор доступной для стрельбы известной цели | [TacticalTargets.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalTargets.kt), [TacticalTargetSelection.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalTargetSelection.kt), [FiringOpportunity.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/FiringOpportunity.kt) |
| Лучи для стрельбы и укрытий | [Sightline.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/Sightline.kt); сравни с отдельным `DetectionSightline` перед изменением |
| Стрельба, дистанции, очереди, перезарядка | [GunAttackBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/GunAttackBehaviour.kt), [AutomaticBurst.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/AutomaticBurst.kt), [OpeningFire.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/OpeningFire.kt), [npc/NpcClass.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/npc/NpcClass.kt) |
| Вызовы оружейного API SBW | [integration/sbw/SbwGuns.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/sbw/SbwGuns.kt), [domain/port/Guns.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/domain/port/Guns.kt) |
| Уход в укрытие и выход для стрельбы | [SeekCoverBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/SeekCoverBehaviour.kt), [CoverSearch.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/CoverSearch.kt), [CombatPosition.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/CombatPosition.kt) |
| Потеря противника в траве, память точки и ограниченные очереди | [LastSeenFire.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/LastSeenFire.kt), исполнитель — [BlindReturnFireBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/BlindReturnFireBehaviour.kt) |
| Реакция на попадания и неизвестного стрелка | [IncomingFire.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/IncomingFire.kt), [BlindReturnFireBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/BlindReturnFireBehaviour.kt), [SuppressionEvents.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/SuppressionEvents.kt) |
| Направление оружия независимо от корпуса NPC | [ShotAim.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/ShotAim.kt), вызов в `GunAttackBehaviour.tickShooting`, адаптер `SbwGuns` |
| Безопасность стрельбы рядом с союзниками | [FriendlyFireGuard.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/FriendlyFireGuard.kt), [FiringCone.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/FiringCone.kt), [SbwProjectileSafety.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/sbw/SbwProjectileSafety.kt), [FireAllocation.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/FireAllocation.kt) |
| Разведение стрелков по позициям | [FiringSpots.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/FiringSpots.kt), `TacticalPositions.kt`, `CoverSearch.kt` |
| Цель поиска позиции и точное занятие огневого поста | [TacticalAim.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalAim.kt), [FiringPostArrival.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/FiringPostArrival.kt) |
| Продолжение поиска заблокированного огневого поста | [TacticalFirePosts.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalFirePosts.kt), [TacticalTask.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalTask.kt), [NativePositionAction.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/ai/integration/NativePositionAction.kt) |
| Очереди лучей и native A* | [TickBudget.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/TickBudget.kt), [TacticalBudget.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalBudget.kt), продолжение между очередями — `TacticalPositions.kt` / `TacticalTask.kt` |
| Дистанция боевой позиции от известных врагов, безопасность маршрута | [CombatPositionSafety.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/CombatPositionSafety.kt), [TacticalClearance.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalClearance.kt) |
| Приоритет укрытия перед открытой позицией | [TacticalPositionScore.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalPositionScore.kt), `TacticalPositions.kt`; индивидуальный поиск — `GunAttackBehaviour.bestFiringSpot` |
| Лечение | [MedicHealBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/MedicHealBehaviour.kt), [SelfTreatBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/SelfTreatBehaviour.kt) |
| Гранаты и уклонение | [GrenadeUseBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/GrenadeUseBehaviour.kt), [GrenadeThrowBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/GrenadeThrowBehaviour.kt), [GrenadeEvadeBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/GrenadeEvadeBehaviour.kt) |
| Тактика отряда | HTN-контроллер решает в [ai/integration/NativeTactics.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/ai/integration/NativeTactics.kt), операции и роли — в [ai/tactics/](../common/src/main/kotlin/com/sbwnpc/squad/ai/tactics/); исполнение одного назначения — [NativePositionAction.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/ai/integration/NativePositionAction.kt) |
| Защищённая высота снайпера и пулемётчика | [DefensiveOverwatch.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/tactics/DefensiveOverwatch.kt), [TacticalPositions.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/tactics/TacticalPositions.kt) |

### Приказы, движение и техника

В `GunAttackBehaviour.tick` виден порядок боевого обновления: смена приказа → наблюдение и прицеливание → движение → огневая линия → работа оружия и очередь. Реализация этапов находится в `updateAim`, `updateCombatMovement`, `updateFiringLane`, `clearBlockedFiringLane` и `tickShooting`. `holdOrderedPosition` отвечает за удержание позиции и возврат в зону обороны.

В `VehicleTransportBehaviour.tickDriving` виден порядок управления поездкой. Проверка питания — `stopIfPowerExhausted`, прогресса — `updateProgressAndCheckStall`, застревания — `recoverIfStuck`, союзника перед техникой — `yieldToBlockingAlly`. `steerBoat` применяет водный маршрут; сухопутные точки выбирают `currentWaypoint` и `driveWaypoint`.

`route/` содержит планирование маршрутов по миру. `squad/RouteManager.kt` хранит **патрульные маршруты, записанные игроком**. Это разные задачи, несмотря на похожие названия.

| Что нужно изменить | Где смотреть |
|---|---|
| Доступные команды по классу отряда | [squad/SquadOrder.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/squad/SquadOrder.kt), метод `availableFor`; его потребители — экраны, HUD, JourneyMap, менеджер |
| Выполнение приказа пехотой | [SquadOrderBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/SquadOrderBehaviour.kt) |
| Длинный марш, точки движения и прогресс | [SquadMarch.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/SquadMarch.kt), [MarchProgress.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/MarchProgress.kt), [GroundRouteProgress.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/GroundRouteProgress.kt), [route/Walking.kt](../common/src/main/kotlin/com/sbwnpc/squad/route/Walking.kt) |
| Короткий путь и застревание NPC | [VehicleAwareNavigation.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/VehicleAwareNavigation.kt), [WalkingClearance.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/WalkingClearance.kt), [NpcStuckRecovery.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/NpcStuckRecovery.kt) |
| Память физически непроходимых шагов и отложенная отмена прыжка | [FailedWalkingSteps.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/FailedWalkingSteps.kt), `VehicleAwareNavigation.rejectStuckStep` / `stopAfterLanding`, `TacticalRuntimeEvents` |
| Посадка, высадка и поездка на машине | [VehicleTransportBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/VehicleTransportBehaviour.kt), [VehicleRoutes.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/VehicleRoutes.kt), [GroundTravelProgress.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/GroundTravelProgress.kt) |
| Проезд по суше и через воду | [route/Driving.kt](../common/src/main/kotlin/com/sbwnpc/squad/route/Driving.kt), [route/GroundMap.kt](../common/src/main/kotlin/com/sbwnpc/squad/route/GroundMap.kt), [vehicle/WaterRoutes.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/vehicle/WaterRoutes.kt), [BoatTrips.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/BoatTrips.kt) |
| Танк и оружие экипажа | [VehicleCrewBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/VehicleCrewBehaviour.kt), [TankWeaponSelection.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/TankWeaponSelection.kt) |
| Вертолёт: приказы, полёт, атака по кругу | [HelicopterPilotBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/HelicopterPilotBehaviour.kt), [HelicopterFlightController.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/HelicopterFlightController.kt), [BenchAttackOrbit.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/BenchAttackOrbit.kt), [BenchOrbitContact.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/BenchOrbitContact.kt) |
| Стрелки и пассажиры вертолёта | [HelicopterGunnerBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/HelicopterGunnerBehaviour.kt), [HelicopterRideBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/HelicopterRideBehaviour.kt) |
| Авиация: дальность показа и LOD | [integration/sbw/client/AircraftVisibility.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/sbw/client/AircraftVisibility.kt), [AircraftLods.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/sbw/client/AircraftLods.kt), [SbwAircraftRendering.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/sbw/client/SbwAircraftRendering.kt), mixin `EntityTrackingRangeMixin.java` |
| Дроны | [DroneOperatorBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/DroneOperatorBehaviour.kt), [DroneFlightController.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/DroneFlightController.kt), [AntiDroneBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/AntiDroneBehaviour.kt) |
| Миномёт | [MortarCrewBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/MortarCrewBehaviour.kt), [MortarClaims.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/MortarClaims.kt), [MortarCycle.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/MortarCycle.kt), [MortarDeployment.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/MortarDeployment.kt) |

### Состав, снабжение и отображение

| Что нужно изменить | Где смотреть |
|---|---|
| Оружие, характеристики и боевые дистанции класса | [npc/NpcClass.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/npc/NpcClass.kt), выдача снаряжения в `NpcEntity.applyRole` и `integration/sbw/SbwGear.kt` |
| Имя, опыт и повышение NPC | [experience/ExperienceRules.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/experience/ExperienceRules.kt), `NpcEntity.gainExperience` / сохранение NBT; пороги в [SquadConfig.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/config/SquadConfig.kt) |
| Начисление за урон, ассисты и лечение | [ExperienceAwards.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/experience/ExperienceAwards.kt), [ExperienceEvents.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/experience/ExperienceEvents.kt); mixin `VehicleExperienceMixin` и `ExperienceHealingMixin` |
| Карточка и объединение отрядов | [SquadDetailsScreen.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/screen/SquadDetailsScreen.kt), [SquadDetailsSnapshot.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/network/SquadDetailsSnapshot.kt), [SquadMerging.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/squad/SquadMerging.kt), `SquadManager.merge` / `reconcileMembership` |
| Ранг и наборы отрядов | [npc/NpcRank.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/npc/NpcRank.kt), [npc/SquadPreset.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/npc/SquadPreset.kt) |
| Спавн и выбор безопасного места | [SquadDeployment.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/squad/SquadDeployment.kt), [SafeSpawn.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/squad/SafeSpawn.kt) |
| Производительность казарм и очередь | [BarracksBlockEntity.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/block/entity/BarracksBlockEntity.kt), [BarracksRecruitmentQueue.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/squad/BarracksRecruitmentQueue.kt), [RecruitmentRoster.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/squad/RecruitmentRoster.kt) |
| Supply и поиск снабжения NPC | [SupplyBlockEntity.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/block/entity/SupplyBlockEntity.kt), [SupplyPoints.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/block/entity/SupplyPoints.kt), [ResupplyBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/ResupplyBehaviour.kt), [OutOfAmmoBehaviour.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/OutOfAmmoBehaviour.kt) |
| Ограничение отхода с пустым боезапасом без Supply | [AmmoRetreat.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/entity/ai/AmmoRetreat.kt), вызовы в `OutOfAmmoBehaviour` |
| Цвета и названия фракций | [npc/SquadFaction.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/npc/SquadFaction.kt) |
| Враждебность и союзы | [SquadTeams.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/team/SquadTeams.kt), [Diplomacy.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/team/Diplomacy.kt), [AllianceRegistry.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/team/AllianceRegistry.kt) |
| Выделение NPC в мире | [SelectionHighlight.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/squad/SelectionHighlight.kt), [SquadSelection.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/squad/SquadSelection.kt) |
| Маркеры, прозрачность, меню и цели на карте | [SquadMapPlugin.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/journeymap/SquadMapPlugin.kt), [MapShapes.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/integration/journeymap/MapShapes.kt); состав данных — `MapFeed.kt` и `client/MapState.kt` |
| Главное меню отрядов, набор, Supply, дипломатия | [client/screen/](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/screen/); имя экрана соответствует задаче |
| Быстрые команды и клавиши | [HudClientEvents.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/HudClientEvents.kt), [HudOverlayState.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/HudOverlayState.kt), [HudKeys.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/client/HudKeys.kt) |
| Текстуры и переводы | [assets/sbwnpc/](../common/src/main/resources/assets/sbwnpc/), [lang/en_us.json](../common/src/main/resources/assets/sbwnpc/lang/en_us.json); [описание скинов](skins/README.md) |

## Тесты, сборка и диагностика

| Группа | Расположение |
|---|---|
| Тесты тактики | [neoforge/src/test/kotlin/com/sbwnpc/squad/combat/tactics/](../neoforge/src/test/kotlin/com/sbwnpc/squad/combat/tactics/) |
| Покрытие и ограничения тестов тактики | [TACTICAL-TESTS.md](TACTICAL-TESTS.md) |
| Серверные сцены с настоящими NPC | [neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/](../neoforge/src/gameTest/kotlin/com/sbwnpc/squad/gametest/); шаблоны — [src/gameTest/resources/data/sbwnpc/structure/](../neoforge/src/gameTest/resources/data/sbwnpc/structure/), запуск — [HEADLESS-TESTS.md](HEADLESS-TESTS.md) |
| Тесты остальных модулей | [neoforge/src/test/kotlin/com/sbwnpc/squad/](../neoforge/src/test/kotlin/com/sbwnpc/squad/) — каталоги соответствуют основному коду |
| Задачи сборки, форматирования и тестов | [build.gradle.kts](../build.gradle.kts) |
| Настройки форматирования | [.editorconfig](../.editorconfig) |
| JAR-файлы | `build/libs/` |
| Отчёты тестов | `build/reports/tests/test/`, `build/test-results/test/` |
| Нативные проверки общей атаки и подсчёт работы | `src/gameTest/kotlin/com/sbwnpc/squad/gametest/AttackOperationGameTests.kt`, `NativeCombatWork.kt` |
| Воспроизведение сохранённого боя и причины простоя | `src/gameTest/kotlin/com/sbwnpc/squad/gametest/SavedBattleGameTests.kt`, `SavedBattleReplay.kt`, `CombatParticipationTrace.kt` |
| Мир и отчёты headless GameTest | `build/gametest/server/`, `build/reports/gameTest/` |
| Генерация BuildFlags | `build.gradle.kts`; результат — `build/generated/buildFlags/` |
| Группы диагностических сообщений | [combat/LogGroup.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/LogGroup.kt) |
| Настройки диагностики | [combat/DebugFlags.kt](../neoforge/src/main/kotlin/com/sbwnpc/squad/combat/DebugFlags.kt); в игре — `config/sbwnpc-debug.toml` |
