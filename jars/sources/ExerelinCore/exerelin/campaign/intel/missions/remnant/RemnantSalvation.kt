package exerelin.campaign.intel.missions.remnant

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.Script
import com.fs.starfarer.api.campaign.*
import com.fs.starfarer.api.campaign.FactionAPI.ShipPickMode
import com.fs.starfarer.api.campaign.FactionAPI.ShipPickParams
import com.fs.starfarer.api.campaign.JumpPointAPI.JumpDestination
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.listeners.FleetEventListener
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.characters.FullName
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.combat.BattleCreationContext
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin
import com.fs.starfarer.api.impl.campaign.DerelictShipEntityPlugin.DerelictShipData
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl.*
import com.fs.starfarer.api.impl.campaign.events.OfficerManagerEvent
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3
import com.fs.starfarer.api.impl.campaign.ids.*
import com.fs.starfarer.api.impl.campaign.intel.contacts.ContactIntel
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithBarEvent
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithSearch.MarketRequirement
import com.fs.starfarer.api.impl.campaign.missions.hub.ReqMode
import com.fs.starfarer.api.impl.campaign.procgen.themes.BaseThemeGenerator
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.Nex_MarketCMD
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.PerShipData
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.ShipRecoverySpecial.ShipRecoverySpecialData
import com.fs.starfarer.api.loading.VariantSource
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import exerelin.campaign.DiplomacyManager
import exerelin.campaign.PlayerFactionStore
import exerelin.campaign.SectorManager
import exerelin.campaign.intel.merc.MercContractIntel
import exerelin.campaign.intel.missions.BuildStation.SystemUninhabitedReq
import exerelin.utilities.*
import lombok.Getter
import org.apache.log4j.Logger
import org.lazywizard.lazylib.MathUtils
import java.awt.Color
import kotlin.math.abs


open class RemnantSalvation : HubMissionWithBarEvent(), FleetEventListener {
    enum class Stage {
        GO_TO_BASE, INVESTIGATE_LEADS, RETURN_TO_MIDNIGHT, DEFEND_PLANET, EPILOGUE, COMPLETED, FAILED, BAD_END
    }

    @Getter protected var base: MarketAPI? = null
    @Getter protected var remnantSystem: StarSystemAPI? = null
    @Getter protected var target: MarketAPI? = null
    @Getter protected var arroyoMarket: MarketAPI? = null
    @Getter protected var fleet1: CampaignFleetAPI? = null
    @Getter protected var fleet2: CampaignFleetAPI? = null
    @Getter protected var knightFleet: CampaignFleetAPI? = null

    @Getter protected var defeatedFleet1 = false
    @Getter protected var defeatedFleet2 = false
    protected var talkedToArroyo = false
    protected var talkedToTowering1 = false
    @Getter protected var targetPKed = false
    protected var hiredEndbringer = false

    @Getter protected var timerToPK : Float = 30f;

    companion object {
        @JvmStatic val STAT_MOD_ID = "nex_remSalvation_mod";

        @JvmStatic val log : Logger = Global.getLogger(ContactIntel::class.java)
        // runcode exerelin.campaign.intel.missions.remnant.RemnantSalvation.Companion.devAddTriggers()
        @JvmStatic fun devAddTriggers() {
            var mission = Global.getSector().memoryWithoutUpdate["\$nex_remSalvation_ref"] as RemnantSalvation
            mission.addAdditionalTriggersDev()
        }

        // runcode exerelin.campaign.intel.missions.remnant.RemnantSalvation.Companion.debugArgentRank()
        @JvmStatic fun debugArgentRank() {
            var person = RemnantQuestUtils.getOrCreateM4LuddicKnight()
            person.setPersonality(Personalities.AGGRESSIVE)
            log.info("Person rank is ${person.rankId} (${person.rank})")
        }
    }

    override fun create(createdAt: MarketAPI, barEvent: Boolean): Boolean {
        if (!setGlobalReference("\$nex_remSalvation_ref")) {
            return false
        }
        val madeira = Global.getSector().economy.getMarket("madeira")
        base = if (madeira != null && madeira.factionId == Factions.PERSEAN) madeira else {
            requireMarketFaction(Factions.PERSEAN)
            requireMarketNotHidden()
            requireMarketNotInHyperspace()
            preferMarketSizeAtLeast(5)
            preferMarketIsMilitary()
            pickMarket()
        }
        if (base == null) return false

        arroyoMarket = Global.getSector().importantPeople.getPerson(People.ARROYO).market;
        if (arroyoMarket == null) {
            requireMarketFaction(Factions.TRITACHYON)
            requireMarketNotHidden()
            requireMarketNotInHyperspace()
            preferMarketSizeAtLeast(5)
            arroyoMarket = pickMarket()
            if (arroyoMarket != null) setupArroyoIfNeeded();
        }
        if (arroyoMarket == null) return false;

        val gilead = Global.getSector().economy.getMarket("gilead")
        target = if (gilead != null && NexUtilsFaction.isLuddicFaction(gilead.factionId)) gilead else {
            requireMarketFaction(Factions.LUDDIC_CHURCH)
            requireMarketNotHidden()
            requireMarketNotInHyperspace()
            requireMarketSizeAtLeast(6)
            preferMarketSizeAtLeast(7)
            requireMarketConditions(ReqMode.ALL, Conditions.HABITABLE)
            // prefer non-military
            search.marketPrefs.add(MarketRequirement { market -> !Misc.isMilitary(market) })
            pickMarket()
        }
        if (target == null) return false

        // pick Remnant location
        requireSystemNotHasPulsar()
        requireSystemTags(
            ReqMode.NOT_ANY,
            Tags.THEME_UNSAFE,
            Tags.THEME_CORE,
            Tags.THEME_REMNANT_RESURGENT,
            Tags.THEME_REMNANT_SECONDARY,
            Tags.TRANSIENT,
            Tags.SYSTEM_CUT_OFF_FROM_HYPER,
            Tags.THEME_HIDDEN
        )
        preferSystemTags(ReqMode.ANY, Tags.THEME_REMNANT, Tags.THEME_DERELICT, Tags.THEME_INTERESTING)
        requireSystemWithinRangeOf(base!!.locationInHyperspace, 15f)
        search.systemReqs.add(SystemUninhabitedReq())
        preferSystemOutsideRangeOf(base!!.locationInHyperspace, 7f)
        preferSystemUnexplored()
        remnantSystem = pickSystem()
        if (remnantSystem == null) return false
        setStartingStage(Stage.GO_TO_BASE)
        addSuccessStages(Stage.COMPLETED)
        addFailureStages(Stage.FAILED, Stage.BAD_END)
        setStoryMission()
        setupTriggers()

        //makeImportant(station, "$nex_remBrawl_target", RemnantBrawl.Stage.GO_TO_TARGET_SYSTEM, RemnantBrawl.Stage.BATTLE, RemnantBrawl.Stage.BATTLE_DEFECTED);
        setRepPersonChangesVeryHigh()
        setRepFactionChangesHigh()
        setCreditReward(CreditReward.VERY_HIGH)
        setCreditReward(creditReward * 5)
        return true
    }

    protected fun setupTriggers() {
        makeImportant(base, "\$nex_remSalvation_base_imp", Stage.GO_TO_BASE)
        makeImportant(remnantSystem!!.hyperspaceAnchor, "\$nex_remSalvation_remnantSystem_imp", Stage.INVESTIGATE_LEADS)
        makeImportant(person, "\$nex_remSalvation_returnStage_imp", Stage.RETURN_TO_MIDNIGHT)
        makeImportant(target, "\$nex_remSalvation_target_imp", Stage.DEFEND_PLANET)
        makeImportant(RemnantQuestUtils.getOrCreateM4LuddicKnight(), "\$nex_remSalvation_epilogue", Stage.EPILOGUE)

        // just sets up a memory value we'll use later
        beginStageTrigger(Stage.GO_TO_BASE)
        triggerSetMemoryValue(base, "\$nex_remSalvation_seenBaseIntro", false)
        endTrigger()

        // Approach base: add some wreckage to the attacked base and disrupt its industries
        beginWithinHyperspaceRangeTrigger(base, 2f, false, Stage.GO_TO_BASE)
        val loc = LocData(base!!.primaryEntity, true)
        triggerSpawnDebrisField(DEBRIS_MEDIUM, DEBRIS_DENSE, loc)
        triggerSpawnShipGraveyard(Factions.REMNANTS, 2, 2, loc)
        triggerSpawnShipGraveyard(Factions.PERSEAN, 4, 6, loc)
        triggerRunScriptAfterDelay(0.01f, GenericMissionScript(this, "trashBase"))
        endTrigger()

        // Approach Remnant system: add a salvagable station and the first fleet
        beginWithinHyperspaceRangeTrigger(remnantSystem, 2f, false, Stage.INVESTIGATE_LEADS)
        triggerRunScriptAfterDelay(0.01f, GenericMissionScript(this, "setupFleet1"))
        endTrigger()

        // Approach target planet: add the Knight fleet
        beginWithinHyperspaceRangeTrigger(target, 2f, false, Stage.DEFEND_PLANET)
        triggerRunScriptAfterDelay(0.01f, GenericMissionScript(this, "setupKnightFleet"))
        endTrigger()

    }

    protected fun addAdditionalTriggersDev() {
    }

    protected fun checkPK() {
        if (fleet2 != null) {
            // TODO: engage any nearby fleets before attempting PK
            for (otherFleet in target!!.containingLocation.fleets) {
                if (!otherFleet.isHostileTo(fleet2)) continue;
                if (MathUtils.getDistance(otherFleet, target!!.primaryEntity) < 250f) continue;

                // engage station if there's one
                // as for patrols, fuck 'em, if they can't catch us we get a free PK
                if (otherFleet == Misc.getStationFleet(target)) {
                    Global.getFactory().createBattle(fleet2, otherFleet)
                    return
                }
            }
        }

        deployPK()
        sendUpdateIfPlayerHasIntel(null, false)
    }

    protected fun deployPK() {
        Nex_MarketCMD.addBombardVisual(target!!.primaryEntity)
        DecivTracker.decivilize(target, true, true)
        target!!.addCondition(Conditions.POLLUTION)
        targetPKed = true

        // relationship effects
        lowerRep(Factions.LUDDIC_CHURCH, Factions.PERSEAN, false)
        lowerRep(Factions.LUDDIC_CHURCH, Factions.TRITACHYON, true)
        lowerRep(Factions.HEGEMONY, Factions.PERSEAN, false)
        lowerRep(Factions.HEGEMONY, Factions.TRITACHYON, false)

        fleet2?.memoryWithoutUpdate?.unset("\$genericHail")
        knightFleet?.memoryWithoutUpdate?.unset("\$genericHail")
    }

    /**
     * Lowers relations between the two factions, creating a Declare War diplomacy event if needed.
     */
    protected fun lowerRep(factionId1 : String, factionId2 : String, severe: Boolean) {
        // TODO: maybe set relationship cap between Church and TT? Meh probably not needed, don't they already have a cap
        var faction1 = Global.getSector().getFaction(factionId1)
        var faction2 = Global.getSector().getFaction(factionId2)
        var loss = if (severe) .6f else .3f
        var curr = faction1.getRelationship(factionId2)
        var isHostile = faction1.isHostileTo(factionId2)
        if (!isHostile && curr - loss < -RepLevel.HOSTILE.min) {
            // declare war event
            DiplomacyManager.createDiplomacyEventV2(
                faction1, faction2,
                "declare_war", null
            )
        } else {
            // apply rep ding and show in campaign UI
            var atBest = if (severe) RepLevel.INHOSPITABLE else RepLevel.NEUTRAL
            var repResult = DiplomacyManager.adjustRelations(faction1, faction2, -loss, atBest, null, null)

            val relation = faction1.getRelationship(factionId2)
            val relationStr = NexUtilsReputation.getRelationStr(relation)
            val relColor = NexUtilsReputation.getRelColor(relation)
            var str = StringHelper.getString("exerelin_diplomacy", "intelRepResultNegative")
            str = StringHelper.substituteToken(str, "\$faction1", faction1.displayName)
            str = StringHelper.substituteToken(str, "\$faction2", faction2.displayName)
            str = StringHelper.substituteToken(str, "\$deltaAbs", "" + abs(repResult.delta * 100).toInt())
            str = StringHelper.substituteToken(str, "\$newRelationStr", relationStr)
            val nhl = Misc.getNegativeHighlightColor()
            Global.getSector().campaignUI.addMessage(str, Misc.getTextColor(), "" + repResult.delta, relationStr, nhl, relColor)
        }
    }

    /**
     * Generates the station that Towering's first fleet will orbit.
     */
    protected fun setupFleet1Station() : SectorEntityToken {
        //log.info("Running station setup")
        val loc = this.generateLocation(null, EntityLocationType.ORBITING_PLANET_OR_STAR, null, remnantSystem)
        val stationDefId = if (Global.getSettings().modManager.isModEnabled("IndEvo")) "IndEvo_arsenalStation" else "station_mining_remnant"
        var station = remnantSystem!!.addCustomEntity("nex_remSalvation_fleet1_station", null, stationDefId, Factions.REMNANTS)
        station.orbit = loc.orbit.makeCopy()
        //log.info(String.format("Wololo, orbit period %s, target %s", station.orbit.orbitalPeriod, station.orbit.focus.name))
        return station
    }

    /**
     * Generates Towering's first fleet.
     */
    protected fun setupFleet1(toOrbit : SectorEntityToken): CampaignFleetAPI {
        val playerStr = NexUtilsFleet.calculatePowerLevel(Global.getSector().playerFleet).toFloat()
        val capBonus = Math.round(NexUtilsFleet.getPlayerLevelFPBonus())
        var fp = (playerStr + capBonus) * 0.6f// - 40
        fp = fp.coerceAtLeast(10f)

        var params = FleetParamsV3(remnantSystem!!.location, Factions.REMNANTS, 1.2f, FleetTypes.TASK_FORCE,
                fp, // combat
                0f, 0f, 0f, 0f, 0f,  // freighter, tanker, transport, liner, utility
                0f
        )
        params.aiCores = OfficerQuality.AI_BETA_OR_GAMMA
        params.averageSMods = 1
        //params.commander = RemnantQuestUtils.getOrCreateTowering()
        //params.flagshipVariantId = "nex_silverlight_restrained_Standard"
        var fleet = FleetFactoryV3.createFleet(params)
        fleet1 = fleet

        fleet.memoryWithoutUpdate.set("\$ignorePlayerCommRequests", true)
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_MAKE_ALWAYS_PURSUE] = true
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_PURSUE_PLAYER] = true
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE] = true
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_MAKE_HOSTILE] = true
        //fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE_ONE_BATTLE_ONLY] = true
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_LOW_REP_IMPACT] = true
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_NO_REP_IMPACT] = true
        fleet.memoryWithoutUpdate[MemFlags.FLEET_INTERACTION_DIALOG_CONFIG_OVERRIDE_GEN] = EnemyFIDConfigGen()
        fleet.memoryWithoutUpdate["\$nex_remSalvation_fleet1"] = true

        fleet.inflateIfNeeded()

        var flagship = fleet.flagship
        flagship.isFlagship = false

        flagship = fleet.fleetData.addFleetMember("nex_silverlight_restrained_Standard")
        var commander = RemnantQuestUtils.getOrCreateTowering()
        fleet.fleetData.setFlagship(flagship)
        flagship.setCaptain(commander)
        flagship.repairTracker.cr = flagship.repairTracker.maxCR
        setupSpecialVariant(flagship, false)
        flagship.shipName = RemnantQuestUtils.getString("salvation_shipName1")
        fleet.commander = commander

        fleet.fleetData.sort()
        fleet.fleetData.setSyncNeeded()
        fleet.fleetData.syncIfNeeded()

        makeImportant(fleet, "\$nex_remSalvation_fleet1_imp", Stage.INVESTIGATE_LEADS)
        //Misc.addDefeatTrigger(fleet, "Nex_RemSalvation_Fleet1Defeated")
        fleet.addEventListener(this)

        remnantSystem!!.addEntity(fleet)
        fleet.setLocation(toOrbit.location.x, toOrbit.location.y)
        fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, toOrbit, 99999f)

        return fleet
    }

    /**
     * Generates Towering's second fleet.
     */
    protected fun setupFleet2(): CampaignFleetAPI? {
        if (fleet2 != null) return null

        RemnantQuestUtils.enhanceTowering()

        val playerStr = NexUtilsFleet.calculatePowerLevel(Global.getSector().playerFleet).toFloat()
        val capBonus = Math.round(NexUtilsFleet.getPlayerLevelFPBonus())
        var fp = (playerStr + capBonus) * 0.6f// - 40
        fp += 150f

        var params = FleetParamsV3(target!!.locationInHyperspace, Factions.REMNANTS, 1.5f, FleetTypes.TASK_FORCE,
            fp, // combat
            0f, 0f, 0f, 0f, 0f,  // freighter, tanker, transport, liner, utility
            0f
        )
        params.aiCores = OfficerQuality.AI_MIXED
        params.averageSMods = 3
        //params.commander = RemnantQuestUtils.getOrCreateTowering()
        //params.flagshipVariantId = "nex_silverlight_Ascendant"
        var fleet = FleetFactoryV3.createFleet(params)
        fleet2 = fleet

        // add about two Facets
        //genRandom = Misc.random;
        val spParams = ShipPickParams(ShipPickMode.PRIORITY_THEN_ALL)
        var picks = Global.getSector().getFaction(Factions.OMEGA).pickShip(ShipRoles.COMBAT_MEDIUM, spParams)
        if (picks.isNotEmpty()) {
            val plugin = Misc.getAICoreOfficerPlugin(Commodities.ALPHA_CORE)

            var i = 0;
            while (i < 2) {
                var name = Global.getSector().getFaction(Factions.OMEGA).pickRandomShipName(genRandom)
                var member = fleet.fleetData.addFleetMember(picks.random().variantId)
                var person = plugin.createPerson(Commodities.ALPHA_CORE, Factions.REMNANTS, genRandom)
                member.captain = person
                member.repairTracker.cr = member.repairTracker.maxCR
                member.shipName = name
                setupSpecialVariant(member, true)
                i++
            }
        }

        fleet.name = RemnantQuestUtils.getString("salvation_fleetName")
        fleet.isNoFactionInName = true

        //fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_MAKE_ALWAYS_PURSUE] = true
        fleet.memoryWithoutUpdate["\$genericHail"] = true
        fleet.memoryWithoutUpdate["\$genericHail_openComms"] = "Nex_RemSalvationHail_Towering"
        fleet.memoryWithoutUpdate.set(MemFlags.MEMORY_KEY_PURSUE_PLAYER, true, 1f)
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE] = true
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_MAKE_HOSTILE] = true
        //fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE_ONE_BATTLE_ONLY] = true
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_LOW_REP_IMPACT] = true
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_NO_REP_IMPACT] = true
        fleet.memoryWithoutUpdate[MemFlags.FLEET_INTERACTION_DIALOG_CONFIG_OVERRIDE_GEN] = EnemyFIDConfigGen()
        fleet.memoryWithoutUpdate["\$nex_remSalvation_fleet2"] = true
        fleet.memoryWithoutUpdate[MemFlags.FLEET_IGNORES_OTHER_FLEETS] = true
        fleet.memoryWithoutUpdate[MemFlags.FLEET_DO_NOT_IGNORE_PLAYER] = true
        fleet.memoryWithoutUpdate.set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true, 1f)

        fleet.inflateIfNeeded()

        // janky stuff because specifying the flagship directly crashes
        var flagship = fleet.flagship
        flagship.isFlagship = false

        flagship = fleet.fleetData.addFleetMember("nex_silverlight_Ascendant")
        var commander = RemnantQuestUtils.getOrCreateTowering()
        fleet.fleetData.setFlagship(flagship)
        flagship.setCaptain(commander)
        flagship.repairTracker.cr = flagship.repairTracker.maxCR
        setupSpecialVariant(flagship, true)
        flagship.shipName = RemnantQuestUtils.getString("salvation_shipName2")
        fleet.commander = commander

        fleet.fleetData.sort()
        fleet.fleetData.setSyncNeeded()
        fleet.fleetData.syncIfNeeded()

        makeImportant(fleet, "\$nex_remSalvation_fleet2_imp", Stage.DEFEND_PLANET)
        //Misc.addDefeatTrigger(fleet, "Nex_RemSalvation_Fleet1Defeated")
        fleet.addEventListener(this)

        insertFleet2(fleet)

        return fleet
    }

    /**
     * Adds Towering's second fleet to the sector (in hyperspace above the target system and transverse jumps in).
     * Currently the jump animation is broken.
     */
    protected fun insertFleet2(fleet: CampaignFleetAPI) {
        // spawn in hyperspace
        Global.getSector().hyperspace.addEntity(fleet)
        fleet.setLocation(target!!.locationInHyperspace.x, target!!.locationInHyperspace.y)

        // Transverse jump code adapted from FractureJumpAbility
        fleet.addAbility(Abilities.TRANSVERSE_JUMP)
        var planet = target!!.primaryEntity
        val loc = Misc.getPointAtRadius(planet.location, planet.radius + 200f + fleet.radius)
        val token = planet.containingLocation.createToken(loc.x, loc.y)
        val dest = JumpDestination(token, null)
        Global.getSector().doHyperspaceTransition(fleet, null, dest)

        // chase player if player is near target, else chase the knight fleet
        var player = Global.getSector().playerFleet
        if (planet.containingLocation == player.containingLocation && Misc.getDistance(player, planet) < 400) {
            fleet.addAssignment(FleetAssignment.INTERCEPT, player, 2f)
        }
        else if (knightFleet != null && knightFleet!!.isAlive) {
            fleet.addAssignment(FleetAssignment.INTERCEPT, knightFleet, 2f)
        }
        fleet.addAssignment(FleetAssignment.DELIVER_FUEL, planet, 60f,
            StringHelper.getFleetAssignmentString("movingInToAttack", planet.name))
        fleet.addAssignment(FleetAssignment.HOLD, planet, 0.25f, StringHelper.getFleetAssignmentString("attacking", planet.name),
            GenericMissionScript(this, "pk"))
        Misc.giveStandardReturnToSourceAssignments(fleet, false)
    }

    /**
     * Generates the Knights of Ludd fleet.
     */
    protected fun setupKnightFleet(): CampaignFleetAPI {
        var fp = 100f

        var params = FleetParamsV3(target!!, target!!.locationInHyperspace, Factions.LUDDIC_CHURCH, null, FleetTypes.TASK_FORCE,
            fp, // combat
            fp/10, 0f, 0f, 0f, 5f,  // freighter, tanker, transport, liner, utility
            .5f
        )
        params.averageSMods = 1
        //params.commander = RemnantQuestUtils.getOrCreateM4LuddicKnight()  // add manualy later, else we'll have to reset their rank
        var fleet = FleetFactoryV3.createFleet(params)
        knightFleet = fleet

        fleet.memoryWithoutUpdate["\$genericHail"] = true
        fleet.memoryWithoutUpdate["\$genericHail_openComms"] = "Nex_RemSalvationHail_Knight"
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE] = true
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_PATROL_FLEET] = true  // try to make sure it joins the battle
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_PATROL_ALLOW_TOFF] = true
        fleet.memoryWithoutUpdate["\$nex_remSalvation_knightFleet"] = true
        fleet.memoryWithoutUpdate[MemFlags.MEMORY_KEY_WAR_FLEET] = true

        val commander = RemnantQuestUtils.getOrCreateM4LuddicKnight();
        fleet.flagship.captain = commander;
        fleet.commander = commander;

        //var flagship = fleet.flagship
        //flagship.captain = params.commander

        makeImportant(fleet, "\$nex_remSalvation_knightFleet_imp", Stage.DEFEND_PLANET)
        makeImportant(fleet, "\$nex_remSalvation_knightFleet_epilogue_imp", Stage.EPILOGUE)
        //Misc.addDefeatTrigger(fleet, "Nex_RemSalvation_Fleet1Defeated")
        fleet.addEventListener(this)

        target!!.containingLocation.addEntity(fleet)
        fleet.setLocation(target!!.primaryEntity.location.x, target!!.primaryEntity.location.y)
        fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, target!!.primaryEntity, 99999f)

        return fleet
    }

    protected fun setupSpecialVariant(flagship : FleetMemberAPI, consistentWeapons : Boolean) {
        flagship.setVariant(flagship.variant.clone(), false, false)
        flagship.variant.source = VariantSource.REFIT
        flagship.variant.addTag(Tags.SHIP_LIMITED_TOOLTIP)  // might be lost if done before inflating
        if (consistentWeapons) flagship.variant.addTag(Tags.VARIANT_CONSISTENT_WEAPON_DROPS)
        flagship.variant.addTag(Tags.UNRECOVERABLE)
    }

    protected fun reportWonBattle1() {
        if (defeatedFleet1) return
        defeatedFleet1 = true
        spawnFlagship1Wreck()
    }

    protected fun reportWonBattle2(dialog: InteractionDialogAPI?, memoryMap: Map<String, MemoryAPI>?) {
        if (defeatedFleet2) return
        defeatedFleet2 = true
        spawnFlagship2Wreck()
        if (targetPKed) completeMissionBadEnd(dialog, memoryMap)
        else {
            setCurrentStage(Stage.EPILOGUE, dialog, memoryMap)
            knightFleet!!.memoryWithoutUpdate["\$genericHail"] = true
        }

        // unfuck station's malfunction rate
        unapplyStationMalfunction()
    }

    protected fun spawnFlagship1Wreck() {
        val variantId = "nex_silverlight_restrained_Standard"
        val params = DerelictShipData(
            PerShipData(
                Global.getSettings().getVariant(variantId), ShipRecoverySpecial.ShipCondition.WRECKED,
                RemnantQuestUtils.getString("salvation_shipName1"), Factions.REMNANTS, 0f
            ), false
        )
        var flagship1 = BaseThemeGenerator.addSalvageEntity(
            remnantSystem, Entities.WRECK, Factions.NEUTRAL, params
        )
        flagship1.isDiscoverable = true
        flagship1.setLocation(fleet1!!.location.x, fleet1!!.location.y)
        makeImportant(flagship1, "\$nex_remSalvation_flagship1_impFlag", Stage.INVESTIGATE_LEADS)
        flagship1.memoryWithoutUpdate.set("\$nex_remSalvation_flagship1", true)
    }

    protected fun spawnFlagship2Wreck() {
        val variantId = "nex_silverlight_Ascendant"
        val params = DerelictShipData(
            PerShipData(
                Global.getSettings().getVariant(variantId), ShipRecoverySpecial.ShipCondition.WRECKED,
                RemnantQuestUtils.getString("salvation_shipName2"), Factions.REMNANTS, 0f
            ), false
        )

        var flagship2 = BaseThemeGenerator.addSalvageEntity(
            target!!.containingLocation, Entities.WRECK, Factions.NEUTRAL, params
        )
        val data = ShipRecoverySpecialData(null)
        data.notNowOptionExits = true
        data.noDescriptionText = true
        val copy = (flagship2.customPlugin as DerelictShipEntityPlugin).data.ship.clone()
        copy.variant.source = VariantSource.REFIT
        copy.variant.stationModules.clear()
        copy.variant.addTag(Tags.SHIP_CAN_NOT_SCUTTLE)
        copy.variant.addTag(Tags.SHIP_UNIQUE_SIGNATURE)
        data.addShip(copy)

        Misc.setSalvageSpecial(flagship2, data)

        flagship2.isDiscoverable = true
        flagship2.setLocation(fleet2!!.location.x, fleet2!!.location.y)
        Misc.makeImportant(flagship2, "\$nex_remSalvation_flagship2_imp")
        flagship2.memoryWithoutUpdate.set("\$nex_remSalvation_flagship2", true)
    }

    override fun acceptImpl(dialog: InteractionDialogAPI, memoryMap: Map<String, MemoryAPI>) {
        Misc.makeStoryCritical(base, "nex_remSalvation")
        Misc.makeStoryCritical(arroyoMarket, "nex_remSalvation")
        Misc.makeStoryCritical(target, "nex_remSalvation")
    }

    protected fun trashBase() {
        for (ind in base!!.industries) {
            val spec = ind.spec
            if (spec.hasTag(Industries.TAG_TACTICAL_BOMBARDMENT) || spec.hasTag(Industries.TAG_STATION)) {
                ind.setDisrupted(ind.disruptedDays + NexUtilsMarket.getIndustryDisruptTime(ind), true)
            }
        }
    }

    protected fun setupConvoWithRandomOperator(dialog : InteractionDialogAPI) {
        var person : PersonAPI = OfficerManagerEvent.createOfficer(Global.getSector().getFaction(Factions.PERSEAN), 1)
        person.setPersonality(Personalities.STEADY);    // just to fit the convo, even though no-one will see it lol
        person.rankId = Ranks.SPACE_ENSIGN

        dialog.getInteractionTarget().setActivePerson(person)
        (dialog.getPlugin() as RuleBasedDialog).notifyActivePersonChanged()
        dialog.getVisualPanel().showPersonInfo(person, false, true)
    }

    protected fun markBaseOfficialAsImportant() {
        var best: PersonAPI? = null
        var bestScore = 0
        for (entry in base!!.commDirectory.entriesCopy) {
            if (entry.isHidden) continue
            if (entry.entryData == null || entry.entryData !is PersonAPI) continue
            val pers = entry.entryData as PersonAPI
            if (pers.faction != base!!.faction) continue

            val score = getMilitaryPostScore(pers.postId);
            if (score > bestScore) {
                bestScore = score;
                best = pers;
            }
        }
        makeImportant(best, "\$nex_remSalvation_seniorMil_imp", Stage.GO_TO_BASE)
    }

    protected fun getMilitaryPostScore(postId: String?): Int {
        when (postId) {
            Ranks.POST_BASE_COMMANDER -> return 4
            Ranks.POST_STATION_COMMANDER -> return 3
            Ranks.POST_FLEET_COMMANDER -> return 2
            Ranks.POST_ADMINISTRATOR -> return 1
        }
        return 0
    }

    protected fun beginLeadsStage(dialog: InteractionDialogAPI, memoryMap: Map<String, MemoryAPI>) {
        setCurrentStage(Stage.INVESTIGATE_LEADS, dialog, memoryMap)
    }

    protected fun cleanup() {
        if (fleet1 != null) Misc.giveStandardReturnToSourceAssignments(fleet1, true)
        if (fleet2 != null) Misc.giveStandardReturnToSourceAssignments(fleet2, true)
        if (knightFleet != null) Misc.giveStandardReturnToSourceAssignments(knightFleet, true)
    }

    protected fun setupArroyoIfNeeded() {
        var arroyo = Global.getSector().importantPeople.getPerson(People.ARROYO);
        if (arroyo == null) {
            arroyo = Global.getFactory().createPerson()
            arroyo.id = People.ARROYO
            arroyo.setFaction(Factions.TRITACHYON)
            arroyo.gender = FullName.Gender.MALE
            arroyo.rankId = Ranks.CITIZEN
            arroyo.postId = Ranks.POST_SENIOR_EXECUTIVE
            arroyo.importance = PersonImportance.HIGH
            arroyo.name.first = "Rayan"
            arroyo.name.last = "Arroyo"
            arroyo.portraitSprite = Global.getSettings().getSpriteName("characters", arroyo.id)
            arroyo.stats.setSkillLevel(Skills.BULK_TRANSPORT, 1f)
            arroyo.stats.setSkillLevel(Skills.INDUSTRIAL_PLANNING, 1f)
            arroyo.addTag(Tags.CONTACT_TRADE)
            arroyo.addTag(Tags.CONTACT_MILITARY)
            arroyo.voice = Voices.BUSINESS

            arroyoMarket!!.getCommDirectory().addPerson(arroyo, 1) // second after Sun

            //arroyoMarket!!.getCommDirectory().getEntryForPerson(arroyo).setHidden(true)
            arroyoMarket!!.addPerson(arroyo)
            Global.getSector().importantPeople.addPerson(arroyo)
        } else {
            arroyo.market = arroyoMarket
            arroyoMarket!!.getCommDirectory().getEntryForPerson(arroyo).setHidden(false)
        }
        makeImportant(arroyo, "\$nex_remSalvation_arroyo_imp", Stage.INVESTIGATE_LEADS)
    }

    protected fun metArroyoBefore(): Boolean {
        if (Global.getSector().memoryWithoutUpdate.getBoolean("\$gaka_completed")) return true;
        val arroyo = Global.getSector().importantPeople.getPerson(People.ARROYO) ?: return false

        if (Global.getSector().intelManager.getIntel(ContactIntel::class.java).firstOrNull() {i -> (i as ContactIntel).person == arroyo } != null) {
            return true;
        }

        return false;
    }

    protected fun haveArroyoComms(): Boolean {
        val arroyo = Global.getSector().importantPeople.getPerson(People.ARROYO) ?: return false
        return !arroyoMarket!!.commDirectory.getEntryForPerson(arroyo).isHidden;
    }

    protected fun getCombatSkillLevel(): Float {
        var level = 0f
        for (skill : SkillLevelAPI in Global.getSector().playerStats.skillsCopy) {
            if (skill.skill.governingAptitudeId != Skills.APT_COMBAT) continue;
            if (skill.level >= 2) level += 2
            else if (skill.level >= 1) level++
        }
        return level;
    }

    protected fun checkInvestigateStageCompletion(dialog : InteractionDialogAPI, memoryMap: Map<String, MemoryAPI>) {
        if (talkedToArroyo && talkedToTowering1) {
            this.setCurrentStage(Stage.RETURN_TO_MIDNIGHT, dialog, memoryMap)
        } else {
            Global.getSector().intelManager.addIntelToTextPanel(this, dialog.textPanel)
        }
    }

    protected fun failMission(dialog : InteractionDialogAPI?, memoryMap: Map<String, MemoryAPI>?) {
        setCurrentStage(Stage.FAILED, dialog, memoryMap)
        Global.getSector().memoryWithoutUpdate["\$nex_remSalvation_missionDone"] = true
        Global.getSector().memoryWithoutUpdate["\$nex_remSalvation_missionFailed"] = true
    }

    protected fun completeMission(dialog : InteractionDialogAPI?, memoryMap: Map<String, MemoryAPI>?) {
        setCurrentStage(Stage.COMPLETED, dialog, memoryMap)
        Global.getSector().memoryWithoutUpdate["\$nex_remSalvation_missionDone"] = true
        Global.getSector().memoryWithoutUpdate["\$nex_remSalvation_missionCompleted"] = true
    }

    protected fun completeMissionBadEnd(dialog : InteractionDialogAPI?, memoryMap: Map<String, MemoryAPI>?) {
        setCreditReward(0)
        setRepFactionChangesNone()
        setRepPersonChangesNone()
        setCurrentStage(Stage.BAD_END, dialog, memoryMap)
        Global.getSector().memoryWithoutUpdate["\$nex_remSalvation_missionDone"] = true
        Global.getSector().memoryWithoutUpdate["\$nex_remSalvation_badEnd"] = true
    }

    protected fun setLuddicFleetsNonHostile() {
        for (fleet : CampaignFleetAPI in target!!.containingLocation.fleets) {
            if (fleet.isPlayerFleet) continue
            if (fleet.faction == target!!.faction) {
                Misc.setFlagWithReason(
                    fleet.memoryWithoutUpdate,
                    MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE,
                    "nex_remSalvation_def",
                    true,
                    1f
                )
            }
        }
    }

    protected fun applyStationMalfunction() {
        val desc = RemnantQuestUtils.getString("salvation_statDescSabotage")
        val fleet = Misc.getStationFleet(target)
        if (fleet != null) {
            for (member in fleet.fleetData.membersListCopy) {
                val stats = member.stats
                //stats.dynamic.getStat(Stats.CR_MALFUNCION_RANGE).modifyMult(STAT_MOD_ID, 10f, desc)
                fleet.removeFleetMemberWithDestructionFlash(member)
            }
        }
    }

    protected fun unapplyStationMalfunction() {
        val fleet = Misc.getStationFleet(target)
        if (fleet != null) {
            for (member in fleet.fleetData.membersListCopy) {
                val stats = member.stats
                //stats.dynamic.getStat(Stats.CR_MALFUNCION_RANGE).unmodify(STAT_MOD_ID)
                //stats.weaponMalfunctionChance.unmodify(STAT_MOD_ID)
            }
        }
    }

    protected fun fleet2AILoop() {
        if (fleet2 == null) return;

        val planet = target!!.primaryEntity
        if (fleet2!!.currentAssignment == null) {
            fleet2!!.addAssignment(FleetAssignment.DELIVER_FUEL, planet, 60f,
                StringHelper.getFleetAssignmentString("movingInToAttack", planet.name))
            fleet2!!.addAssignment(FleetAssignment.HOLD, planet, 0.25f, StringHelper.getFleetAssignmentString("attacking", planet.name),
                GenericMissionScript(this, "pk"))
        }
    }

    override fun advanceImpl(amount: Float) {
        super.advanceImpl(amount)
        val days = Global.getSector().clock.convertToDays(amount)
        if (currentStage == Stage.DEFEND_PLANET) {

            if (fleet2 != null) {
                // there used to be something here but now there isn't
            } else {
                setLuddicFleetsNonHostile()
            }

            if (!targetPKed && fleet2 == null) {
                this.timerToPK -= days;
                if (timerToPK > 0) return

                if (!Misc.isNear(Global.getSector().playerFleet, target!!.locationInHyperspace)) {
                    //setupFleet2()
                    deployPK()
                    failMission(null, null)
                } else {
                    setupFleet2()
                    applyStationMalfunction()
                }
            }
        }
    }

    override fun callAction(
        action: String,
        ruleId: String,
        dialog: InteractionDialogAPI,
        params: List<Misc.Token>,
        memoryMap: Map<String, MemoryAPI>
    ): Boolean {
        //log.info("wololo $action")
        when (action) {
            "makeOfficialImportant" -> { markBaseOfficialAsImportant(); return true}
            "setupConvoWithOperator" -> {setupConvoWithRandomOperator(dialog); return true}
            "beginLeadsStage" -> { beginLeadsStage(dialog, memoryMap); return true}
            "reportTalkedToArroyo" -> {
                talkedToArroyo = true;
                makeUnimportant(Global.getSector().importantPeople.getPerson(People.ARROYO), Stage.INVESTIGATE_LEADS)
                Global.getSector().characterData.memoryWithoutUpdate.set("\$nex_witnessed_arroyo_attempted_assassination", true)
                checkInvestigateStageCompletion(dialog, memoryMap)
                return true;
            }
            "reportTalkedToTowering1" -> {
                talkedToTowering1 = true;
                makeUnimportant(remnantSystem!!.hyperspaceAnchor, Stage.INVESTIGATE_LEADS)
                checkInvestigateStageCompletion(dialog, memoryMap)
                return true;
            }
            "setupArroyo" -> { setupArroyoIfNeeded(); return true}
            "explosionSound" -> {
                Global.getSoundPlayer().playUISound("nex_sfx_explosion", 1f, 1f)
                return true
            }
            "showPather" -> {
                var pather = Global.getSector().getFaction(Factions.LUDDIC_PATH).createRandomPerson(FullName.Gender.MALE, genRandom)
                dialog.visualPanel.showSecondPerson(pather)
                return true
            }
            "hireMerc" -> {
                var merc = MercContractIntel("endbringer")
                merc.init(dialog.interactionTarget.market, true);
                merc.accept(dialog.interactionTarget.market, dialog.textPanel)
                hiredEndbringer = true
                return true
            }
            "startDefendStage" -> {
                setCurrentStage(Stage.DEFEND_PLANET, dialog, memoryMap)
                return true
            }
            "floorChurchRep" -> {
                var customRepImpact = CoreReputationPlugin.CustomRepImpact()
                customRepImpact.delta = 0f
                customRepImpact.ensureAtWorst = RepLevel.INHOSPITABLE
                var envelope = RepActionEnvelope(
                    RepActions.CUSTOM, customRepImpact,
                    null, dialog.textPanel, true)
                Global.getSector().adjustPlayerReputation(envelope, Factions.LUDDIC_CHURCH)
                return true
            }
            "reportTalkedToKnight" -> {
                setupFleet2()
                //val currCR = Misc.getStationFleet(target).flagship.repairTracker.cr
                // can't change CR directly, it's auto-set
                // actually we can't change it at all
                applyStationMalfunction()
                return true
            }
            "complete" -> {completeMission(dialog, memoryMap); return true}
        }
        return false
    }

    override fun updateInteractionDataImpl() {

        set("\$nex_remSalvation_baseName", base!!.name);
        set("\$nex_remSalvation_baseNameAllCaps", base!!.name.uppercase());
        set("\$nex_remSalvation_baseOnOrAt", base!!.onOrAt);
        set("\$nex_remSalvation_arroyoMarketName", arroyoMarket!!.name);
        set("\$nex_remSalvation_arroyoMarketOnOrAt", arroyoMarket!!.onOrAt);
        set("\$nex_remSalvation_remnantSystem", remnantSystem!!.baseName);
        set("\$nex_remSalvation_targetName", target!!.name);
        set("\$nex_remSalvation_targetId", target!!.id);
        set("\$nex_remSalvation_leagueColor", Global.getSector().getFaction(Factions.PERSEAN).baseUIColor);
        set("\$nex_remSalvation_ttColor", Global.getSector().getFaction(Factions.TRITACHYON).baseUIColor);
        set("\$nex_remSalvation_churchColor", Global.getSector().getFaction(Factions.LUDDIC_CHURCH).baseUIColor);
        set("\$nex_remSalvation_remnantColor", factionForUIColors.baseUIColor);

        //val clock = Global.getSector().clock.createClock(Global.getSector().clock.timestamp - 1000000);
        //set("\$nex_remSalvation_attackDate", "" + clock.getDay() + " " + Global.getSector().clock.shortMonthString);

        val factionId = PlayerFactionStore.getPlayerFactionId()

        set("\$nex_remSalvation_playerFactionId", factionId)
        set("\$nex_remSalvation_isRepresentingState", SectorManager.isFactionAlive(factionId))
        set("\$nex_remSalvation_playerFaction", PlayerFactionStore.getPlayerFaction().displayName)
        set("\$nex_remSalvation_thePlayerFaction", PlayerFactionStore.getPlayerFaction().displayNameWithArticle)
        set("\$nex_remSalvation_playerFactionLeaderRank", PlayerFactionStore.getPlayerFaction().getRank(Ranks.FACTION_LEADER))
        set("\$nex_remSalvation_haveOwnFaction", PlayerFactionStore.getPlayerFaction().isPlayerFaction && SectorManager.isFactionAlive(Factions.PLAYER))

        val metSiyavong = Global.getSector().importantPeople.getPerson(People.SIYAVONG)?.memoryWithoutUpdate?.getBoolean("\$metAlready")
        set("\$nex_remSalvation_metSiyavongBefore", metSiyavong)
        set("\$nex_remSalvation_metArroyoBefore", metArroyoBefore())
        set("\$nex_remSalvation_haveArroyoComms", haveArroyoComms())
        set("\$nex_remSalvation_talkedToArroyo", talkedToArroyo)
        set("\$nex_remSalvation_targetPKed", targetPKed)
        set("\$nex_remSalvation_defeatedFleet1", defeatedFleet1)
        set("\$nex_remSalvation_defeatedFleet2", defeatedFleet2)

        set("\$nex_remSalvation_combatSkillLevel", getCombatSkillLevel())

        //var baseConvoType : String
        //if (factionId.equals(Factions.PERSEAN)) baseConvoType = ""
    }

    override fun addDescriptionForNonEndStage(info: TooltipMakerAPI, width: Float, height: Float) {
        val hl = Misc.getHighlightColor()
        val opad = 10f
        var str = RemnantQuestUtils.getString("salvation_boilerplateDesc")
        str = StringHelper.substituteToken(str, "\$name", person.name.fullName)
        str = StringHelper.substituteToken(str, "\$base", base!!.name)
        info.addPara(str, opad, base!!.faction.baseUIColor, base!!.name)

        if (currentStage == Stage.GO_TO_BASE) {
            info.addPara(RemnantQuestUtils.getString("salvation_startDesc"), opad, base!!.faction.baseUIColor, base!!.name)
        } else if (currentStage == Stage.INVESTIGATE_LEADS) {
            str = RemnantQuestUtils.getString("salvation_investigateLeadsDesc");
            str = StringHelper.substituteToken(str, "\$agentName", Global.getSector().importantPeople.getPerson(People.SIYAVONG).name.last)
            info.addPara(str, opad)

            bullet(info)
            val tt = Global.getSector().getFaction(Factions.TRITACHYON)
            if (!defeatedFleet1)
                info.addPara(RemnantQuestUtils.getString("salvation_investigateLeadsDesc1"), 0f, hl, remnantSystem!!.nameWithLowercaseTypeShort)
            if (!talkedToArroyo)
                info.addPara(RemnantQuestUtils.getString("salvation_investigateLeadsDesc2"), 0f, tt.baseUIColor, tt.displayName)
            unindent(info)
        } else if (currentStage == Stage.RETURN_TO_MIDNIGHT) {
            info.addPara(RemnantQuestUtils.getString("salvation_returnDesc"), opad, person.faction.baseUIColor, person.name.first)
        } else if (currentStage == Stage.DEFEND_PLANET) {
            var key = "salvation_defendPlanetDesc"
            if (targetPKed) key += "PKed"
            info.addPara(RemnantQuestUtils.getString(key), opad, target!!.faction.baseUIColor, target!!.name)
            if (Global.getSettings().isDevMode) {
                info.addPara("[debug] Time left: %s days", opad, hl, String.format("%.1f", this.timerToPK))
            }
        } else if (currentStage == Stage.EPILOGUE) {
            var knight = RemnantQuestUtils.getOrCreateM4LuddicKnight()
            var str = RemnantQuestUtils.getString("salvation_epilogueDesc")
            str = StringHelper.substituteToken(str, "\$system", target!!.containingLocation.name)
            info.addPara(str, opad, knight.faction.baseUIColor, knight.nameString)
        }
    }

    override fun getStageDescriptionText(): String? {
        var key = "salvation_endText"
        if (currentStage == Stage.FAILED) {
            key += "Fail"
        } else if (currentStage == Stage.BAD_END) {
            key += "BadEnd"
        } else if (currentStage == Stage.COMPLETED) {
            key += "Success"
        } else return null

        var str = RemnantQuestUtils.getString(key)
        str = StringHelper.substituteToken(str, "\$nex_remSalvation_targetName", target!!.name)
        str = StringHelper.substituteToken(str, "\$playerFirstName", Global.getSector().playerPerson.name.first)
        return str
    }

    override fun addNextStepText(info: TooltipMakerAPI, tc: Color?, pad: Float): Boolean {
        super.addNextStepText(info, tc, pad)
        val hl = Misc.getHighlightColor()
        //val col: Color = station.getStarSystem().getStar().getSpec().getIconColor()
        //val sysName: String = station.getContainingLocation().getNameWithLowercaseTypeShort()

        //info.addPara("[debug] Current stage: " + currentStage, tc, pad);
        if (currentStage == Stage.GO_TO_BASE) {
            info.addPara(
                RemnantQuestUtils.getString("salvation_startNextStep"), pad, tc, base!!.faction.baseUIColor, base!!.name
            )
        } else if (currentStage == Stage.INVESTIGATE_LEADS) {
            val tt = Global.getSector().getFaction(Factions.TRITACHYON)
            if (!defeatedFleet1)
                info.addPara(RemnantQuestUtils.getString("salvation_investigateLeadsNextStep1"), pad, tc, hl, remnantSystem!!.nameWithLowercaseTypeShort)
            if (!talkedToArroyo)
                info.addPara(RemnantQuestUtils.getString("salvation_investigateLeadsNextStep2"), 0f, tc, tt.baseUIColor, tt.displayName)
        } else if (currentStage == Stage.RETURN_TO_MIDNIGHT) {
            info.addPara(RemnantQuestUtils.getString("salvation_returnNextStep"), pad, tc, person.faction.baseUIColor, person.name.first)
        } else if (currentStage == Stage.DEFEND_PLANET) {
            var key = "salvation_defendPlanetNextStep"
            if (targetPKed) key += "PKed"
            info.addPara(RemnantQuestUtils.getString(key), pad, tc, target!!.faction.baseUIColor, target!!.name)
        } else if (currentStage == Stage.EPILOGUE) {
            var knight = RemnantQuestUtils.getOrCreateM4LuddicKnight()
            info.addPara(RemnantQuestUtils.getString("salvation_epilogueNextStep"), pad, tc, knight.faction.baseUIColor, knight.name.last)
        }
        return false
    }

    override fun getBaseName(): String? {
        return RemnantQuestUtils.getString("salvation_name")
    }

    override fun notifyEnding() {
        cleanup()
    }

    class EnemyFIDConfigGen : FIDConfigGen {
        override fun createConfig(): FIDConfig {
            val config = FIDConfig()
            config.delegate = object : BaseFIDDelegate() {
                override fun postPlayerSalvageGeneration(
                    dialog: InteractionDialogAPI,
                    context: FleetEncounterContext,
                    salvage: CargoAPI
                ) {
                }

                override fun notifyLeave(dialog: InteractionDialogAPI) {
                    // unreliable and won't work if fleet is killed with campaign console; just use the EveryFrameScript
                    /*
					dialog.setInteractionTarget(Global.getSector().getPlayerFleet());
					RuleBasedInteractionDialogPluginImpl plugin = new RuleBasedInteractionDialogPluginImpl();
					dialog.setPlugin(plugin);
					plugin.init(dialog);
					plugin.fireBest("Sunrider_Mission4_PostEncounterDialogStart");
					*/
                }

                override fun battleContextCreated(dialog: InteractionDialogAPI, bcc: BattleCreationContext) {
                    bcc.aiRetreatAllowed = false
                    bcc.fightToTheLast = true
                }
            }
            return config
        }
    }

    override fun reportFleetDespawnedToListener(
        fleet: CampaignFleetAPI?,
        reason: CampaignEventListener.FleetDespawnReason?,
        param: Any?
    ) {
        if (fleet == this.fleet1) {
            reportWonBattle1()
        }
        else if (fleet == this.knightFleet) {
            var knight = RemnantQuestUtils.getOrCreateM4LuddicKnight()
            target!!.addPerson(knight)
            target!!.commDirectory.addPerson(knight)
        }
        else if (fleet == this.fleet2) {
            if (reason == CampaignEventListener.FleetDespawnReason.REACHED_DESTINATION && targetPKed) {
                failMission(null, null)
            } else reportWonBattle2(null, null)
        }
    }

    override fun reportBattleOccurred(fleet: CampaignFleetAPI?, primaryWinner: CampaignFleetAPI?, battle: BattleAPI?) {

    }

    class GenericMissionScript(var intel : RemnantSalvation, val param : String) : Script {
        override fun run() {
            when (param) {
                "trashBase" -> intel.trashBase()
                "setupFleet1" -> {
                    val station = intel.setupFleet1Station()
                    intel.setupFleet1(station)
                }
                "setupKnightFleet" -> intel.setupKnightFleet()
                "pk" -> intel.checkPK()
                "setupFleet2" -> intel.setupFleet2()
            }
        }
    }
}