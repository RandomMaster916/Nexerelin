package exerelin.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.ColonyPlayerHostileActListener;
import com.fs.starfarer.api.campaign.listeners.DiscoverEntityListener;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl.BaseFIDDelegate;
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl.FIDConfig;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.missions.DelayedFleetEncounter;
import com.fs.starfarer.api.impl.campaign.missions.DelayedFleetEncounter.EncounterType;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithTriggers;
import com.fs.starfarer.api.impl.campaign.missions.hub.MissionFleetAutoDespawn;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.Nex_MarketCMD;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.Nex_MarketCMD.NexTempData;
import com.fs.starfarer.api.loading.VariantSource;
import exerelin.campaign.diplomacy.DiplomacyTraits;
import exerelin.campaign.diplomacy.DiplomacyTraits.TraitIds;
import exerelin.campaign.intel.colony.ColonyExpeditionIntel;
import exerelin.plugins.ExerelinModPlugin;
import exerelin.utilities.NexConfig;
import exerelin.utilities.NexUtilsAstro;
import exerelin.utilities.NexUtilsFleet;
import exerelin.utilities.StringHelper;
import lombok.extern.log4j.Log4j;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

@Log4j
public class MiscEventsManager extends BaseCampaignEventListener implements 
		DiscoverEntityListener, ColonyPlayerHostileActListener {
	
	public static final boolean USE_OMEGA_DFE = true;
	
	public MiscEventsManager() {
		super(false);
	}
	
	public static MiscEventsManager create() {
		MiscEventsManager manager = new MiscEventsManager();
		Global.getSector().addTransientListener(manager);
		Global.getSector().getListenerManager().addListener(manager, true);
		return manager;
	}

	@Override
	public void reportEntityDiscovered(SectorEntityToken entity) {
		if (entity.hasTag(Tags.CORONAL_TAP)  && Global.getSettings().getBoolean("nex_spawnHypershuntComplication"))
			spawnShuntFleet(entity);
	}
	
	public void spawnShuntFleet(SectorEntityToken shunt) {
		FactionAPI faction = Global.getSector().getFaction(Factions.OMEGA);
		float maxPointsForFaction = faction.getApproximateMaxFPPerFleet(FactionAPI.ShipPickMode.PRIORITY_THEN_ALL);
		
		float playerStr = NexUtilsFleet.calculatePowerLevel(Global.getSector().getPlayerFleet());
		int capBonus = Math.round(NexUtilsFleet.getPlayerLevelFPBonus());

		int combat = Math.round((playerStr/5f + capBonus) * MathUtils.getRandomNumberInRange(0.6f, 0.7f));
		combat *= 0.45f;
		
		// don't spawn if player too weak
		if (combat < 12) {
			if (ExerelinModPlugin.isNexDev)
				Global.getSector().getCampaignUI().addMessage("Player too weak for shunt complication");
			return;
		}	
		
		Global.getLogger(this.getClass()).info("Player strength: " + playerStr);
		Global.getLogger(this.getClass()).info("Omega estimated desired combat points: " + combat);
		Global.getLogger(this.getClass()).info("Omega max combat points: " + maxPointsForFaction);
		combat = Math.min(70, combat);
		combat = Math.max(12, combat);	// at least a shard
		
		// preferred in most ways since it automates various behaviors
		// but has problems in that no way to set variant tags, needs to outsource to a listener
		if (USE_OMEGA_DFE) {
			DelayedFleetEncounter e = new DelayedFleetEncounter(null, "hist");
			e.setTypes(EncounterType.OUTSIDE_SYSTEM, EncounterType.JUMP_IN_NEAR_PLAYER, 
					EncounterType.IN_HYPER_EN_ROUTE, EncounterType.FROM_SOMEWHERE_IN_SYSTEM);
			e.setDelayNone();
			e.setLocationAnywhere(false, Factions.REMNANTS);
			e.setDoNotAbortWhenPlayerFleetTooStrong();
			e.beginCreate();
			e.triggerCreateFleet(HubMissionWithTriggers.FleetSize.SMALL, 
					HubMissionWithTriggers.FleetQuality.VERY_HIGH, 
					Factions.OMEGA, 
					FleetTypes.PATROL_SMALL, 
					new Vector2f());
			//e.triggerSetAdjustStrengthBasedOnQuality(false, 1);
			
			NexUtilsFleet.setTriggerFleetFP(faction, combat, e);
			
			e.triggerSetFleetMaxShipSize(2);
			e.triggerSetFleetFaction(Factions.REMNANTS);
			
			// behavior
			//e.triggerSetStandardAggroInterceptFlags();
			e.triggerMakeHostileAndAggressive();
			//e.triggerFleetAllowLongPursuit();
			//e.triggerSetFleetAlwaysPursue();
			e.triggerOrderFleetInterceptPlayer();
			e.triggerOrderFleetMaybeEBurn();
			
			e.triggerMakeNoRepImpact();
			e.triggerSetFleetGenericHailPermanent("Nex_HistorianOmegaHail");
			e.triggerSetFleetFlagPermanent("$nex_omega_hypershunt_complication");
			e.triggerSetFleetMemoryValue("$nex_omega_hypershunt_complication_shunt", shunt);
			e.endCreate();
		}
		else {
			CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
			Vector2f locInHyper = playerFleet.getLocationInHyperspace();
			
			FleetParamsV3 params = new FleetParamsV3(locInHyper,
					Factions.OMEGA,
					null,
					FleetTypes.PATROL_SMALL,
					combat, // combatPts
					0, // freighterPts
					0, // tankerPts
					0, // transportPts
					0, // linerPts
					0, // utilityPts
					0);
			params.ignoreMarketFleetSizeMult = true;
			params.qualityOverride = 1.2f;
			params.maxShipSize = 2;
			
			float delay = MathUtils.getRandomNumberInRange(0.75f, 3f);
			//DelayedActionScript script;
			CampaignFleetAPI fleet = NexUtilsFleet.customCreateFleet(faction, params);

			if (fleet == null)
				return;

			String targetName = StringHelper.getString("yourFleet");
			
			fleet.getMemoryWithoutUpdate().set("$genericHail", true);
			fleet.getMemoryWithoutUpdate().set("$genericHail_openComms", "Nex_HistorianOmegaHail");
			//fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_ALLOW_LONG_PURSUIT, true);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_ALWAYS_PURSUE, true);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_PURSUE_PLAYER, true);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_HOSTILE, true);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE_ONE_BATTLE_ONLY, true);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_LOW_REP_IMPACT, true);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_NO_REP_IMPACT, true);

			Vector2f pos = MathUtils.getPointOnCircumference(playerFleet.getLocation(), 
					playerFleet.getMaxSensorRangeToDetect(fleet) * MathUtils.getRandomNumberInRange(1.25f, 1.6f),
					NexUtilsAstro.getRandomAngle());
			fleet.setLocation(pos.x, pos.y);

			fleet.addAssignment(FleetAssignment.ATTACK_LOCATION, playerFleet, 0.5f);	// make it get a little closer
			fleet.addAssignment(FleetAssignment.INTERCEPT, playerFleet, 15,
					StringHelper.getFleetAssignmentString("intercepting", targetName));
			fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, shunt, 999999);
			fleet.setLocation(pos.getX(), pos.getY());
			playerFleet.getContainingLocation().addEntity(fleet);
			
		}
		
		//Global.getLogger(this.getClass()).info("Creating Omega complication");
		//Global.getSector().getCampaignUI().addMessage("Creating Omega complication");
	}

	@Override
	public void reportFleetSpawned(CampaignFleetAPI fleet) {
		//Global.getLogger(this.getClass()).info("Fleet spawned: " + fleet.getNameWithFactionKeepCase());
		if (fleet.getMemoryWithoutUpdate().contains("$nex_omega_hypershunt_complication")) {
			if (ExerelinModPlugin.isNexDev)
				Global.getSector().getCampaignUI().addMessage("Omega fleet spawned");
			
			for (FleetMemberAPI member : fleet.getFleetData().getMembersListWithFightersCopy()) {
				member.setVariant(member.getVariant().clone(), false, false);
				member.getVariant().setSource(VariantSource.REFIT);
				member.getVariant().addTag(Tags.SHIP_LIMITED_TOOLTIP);
				member.getVariant().addTag(Tags.VARIANT_CONSISTENT_WEAPON_DROPS);
			}
			SectorEntityToken shunt = fleet.getMemoryWithoutUpdate().getEntity("$nex_omega_hypershunt_complication_shunt");
			if (shunt != null) {
				fleet.addAssignment(FleetAssignment.DEFEND_LOCATION, shunt, 999999);
			}
			fleet.removeScriptsOfClass(MissionFleetAutoDespawn.class);
			
			FIDConfig conf = new FIDConfig();
			conf.delegate = new ShuntEncounterFIDDelegate();
			fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_INTERACTION_DIALOG_CONFIG_OVERRIDE_GEN, conf);
		}
	}

	@Override
	public void reportRaidForValuablesFinishedBeforeCargoShown(InteractionDialogAPI dialog, 
			MarketAPI market, MarketCMD.TempData actionData, CargoAPI cargo) {}

	@Override
	public void reportRaidToDisruptFinished(InteractionDialogAPI dialog, MarketAPI market, 
			MarketCMD.TempData actionData, Industry industry) {}

	@Override
	public void reportTacticalBombardmentFinished(InteractionDialogAPI dialog, 
			MarketAPI market, MarketCMD.TempData actionData) {}
	
	// Permanent rep penalty for sat bomb
	@Override
	public void reportSaturationBombardmentFinished(InteractionDialogAPI dialog, 
			MarketAPI market, MarketCMD.TempData actionData) {
		
		if (actionData == null || actionData.willBecomeHostile == null) return;
		
		log.info("Sat bomb reported");
		
		if (market.isHidden()) return;
		if (NexConfig.permaHateFromPlayerSatBomb <= 0) return;
		FactionAPI target = market.getFaction();
		
		if (actionData instanceof NexTempData) {
			NexTempData nd = (Nex_MarketCMD.NexTempData)actionData;
			boolean suppress = nd.satBombLimitedHatred;
			if (suppress) {
				log.info("do not rage");
				return;
			}
			if (target.isNeutralFaction()) target = nd.targetFaction;
		}
		
		log.info("Inflicting relationship cap from sat bomb");
		for (FactionAPI faction: actionData.willBecomeHostile) {
			if (faction.isNeutralFaction()) continue;
			float change = -NexConfig.permaHateFromPlayerSatBomb;
			if (faction == target) change *= 2;
			DiplomacyManager.getManager().modifyMaxRelationshipMod("satbomb", change, 
					faction.getId(), Factions.PLAYER, StringHelper.getString("saturationBombardment"));
		}
	}
	
	public static class ShuntEncounterFIDDelegate extends BaseFIDDelegate {		
		@Override
		public void battleContextCreated(InteractionDialogAPI dialog, BattleCreationContext bcc) {
			bcc.aiRetreatAllowed = false;
			bcc.fightToTheLast = true;
		}
	}
}
