package exerelin.campaign.intel.invasion;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FactionAPI.ShipPickMode;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.fleets.RouteLocationCalculator;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.intel.raid.RaidAssignmentAI;
import com.fs.starfarer.api.impl.campaign.intel.raid.RaidIntel.RaidDelegate;
import com.fs.starfarer.api.impl.campaign.procgen.themes.RouteFleetAssignmentAI;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.Nex_MarketCMD;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import exerelin.campaign.InvasionRound;
import exerelin.campaign.fleets.InvasionFleetManager;
import exerelin.campaign.intel.OffensiveFleetIntel;
import static exerelin.campaign.fleets.InvasionFleetManager.TANKER_FP_PER_FLEET_FP_PER_10K_DIST;
import exerelin.campaign.intel.fleets.NexReturnStage;
import exerelin.campaign.intel.fleets.NexTravelStage;
import exerelin.utilities.ExerelinConfig;
import exerelin.utilities.ExerelinUtilsMarket;
import exerelin.utilities.StringHelper;
import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

public class InvasionIntel extends OffensiveFleetIntel implements RaidDelegate {
	
	public static final boolean NO_STRIKE_FLEETS = true;
	public static final boolean USE_REAL_MARINES = false;
	public static final int MAX_MARINES = 2500;
	
	public static Logger log = Global.getLogger(InvasionIntel.class);
	
	protected int marinesPerFleet = 0;
		
	public InvasionIntel(FactionAPI attacker, MarketAPI from, MarketAPI target, float fp, float orgDur) {
		super(attacker, from, target, fp, orgDur);
	}
	
	@Override
	public void init() {
		log.info("Creating invasion intel");
		
		SectorEntityToken gather = from.getPrimaryEntity();
		
		addStage(new InvOrganizeStage(this, from, orgDur));
		
		float successMult = 0.4f;
		InvAssembleStage assemble = new InvAssembleStage(this, gather);
		assemble.addSource(from);
		assemble.setSpawnFP(fp);
		assemble.setAbortFP(fp * successMult);
		addStage(assemble);
		
		SectorEntityToken raidJump = RouteLocationCalculator.findJumpPointToUse(getFactionForUIColors(), target.getPrimaryEntity());

		NexTravelStage travel = new NexTravelStage(this, gather, raidJump, false);
		travel.setAbortFP(fp * successMult);
		addStage(travel);
		
		
		action = new InvActionStage(this, target);
		action.setAbortFP(fp * successMult);
		addStage(action);
		
		addStage(new NexReturnStage(this));
		
		float defenderStrength = InvasionRound.getDefenderStrength(target, 0.5f);
		marinesPerFleet = (int)(defenderStrength * InvasionFleetManager.DEFENDER_STRENGTH_MARINE_MULT);
		if (marinesPerFleet < 100) {
			marinesPerFleet = 100;
		}
		else if (marinesPerFleet > MAX_MARINES) {
			log.info("Capping marines at " + MAX_MARINES + " (was " + marinesPerFleet + ")");
			marinesPerFleet = MAX_MARINES;
		}
		
		/*
		if (shouldDisplayIntel())
			queueIntelIfNeeded();
		else if (DEBUG_MODE)
		{
			Global.getSector().getCampaignUI().addMessage("Invasion intel from " 
					+ from.getName() + " to " + target.getName() + " concealed due to lack of sniffer");
		}
		*/
		addIntelIfNeeded();
	}
		
	public int getMarinesPerFleet() {
		return marinesPerFleet;
	}
	
	public void setMarinesPerFleet(int marines) {
		marinesPerFleet = marines;
	}
	
	protected String getDescString() {
		return StringHelper.getString("exerelin_invasion", "intelDesc");
	}
	
	// intel long description in intel screen
	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		//super.createSmallDescription(info, width, height);
		
		Color h = Misc.getHighlightColor();
		Color g = Misc.getGrayColor();
		Color tc = Misc.getTextColor();
		float pad = 3f;
		float opad = 10f;
		
		info.addImage(getFactionForUIColors().getLogo(), width, 128, opad);
		
		FactionAPI attacker = getFaction();
		FactionAPI defender = targetFaction;
		String has = attacker.getDisplayNameHasOrHave();
		String is = attacker.getDisplayNameIsOrAre();
		String locationName = target.getContainingLocation().getNameWithLowercaseType();
		
		String strDesc = getRaidStrDesc();
		
		String string = getDescString();
		String attackerName = attacker.getDisplayNameWithArticle();
		String defenderName = defender.getDisplayNameWithArticle();
		int numFleets = (int) getOrigNumFleets();
				
		Map<String, String> sub = new HashMap<>();
		sub.put("$theFaction", attackerName);
		sub.put("$TheFaction", Misc.ucFirst(attackerName));
		sub.put("$theTargetFaction", defenderName);
		sub.put("$TheTargetFaction", Misc.ucFirst(defenderName));
		sub.put("$market", target.getName());
		sub.put("$isOrAre", attacker.getDisplayNameIsOrAre());
		sub.put("$location", locationName);
		sub.put("$strDesc", strDesc);
		sub.put("$numFleets", numFleets + "");
		sub.put("$fleetsStr", numFleets > 1 ? StringHelper.getString("fleets") : StringHelper.getString("fleet"));
		string = StringHelper.substituteTokens(string, sub);
		
		LabelAPI label = info.addPara(string, opad);
		label.setHighlight(attacker.getDisplayNameWithArticleWithoutArticle(), target.getName(), 
				defender.getDisplayNameWithArticleWithoutArticle(), strDesc, numFleets + "");
		label.setHighlightColors(attacker.getBaseUIColor(), h, defender.getBaseUIColor(), h, h);
		
		if (Global.getSettings().isDevMode()) {
			float fpRound = Math.round(fp);
			float baseFP = Math.round(InvasionFleetManager.getWantedFleetSize(getFaction(), target, 0, false));
			info.addPara("DEBUG: The invasion's starting FP is " + fpRound 
					+ ". At current strength, the base FP desired for the target is approximately " 
					+ baseFP + ".", opad, Misc.getHighlightColor(), fpRound + "", baseFP + "");
		}
		
		if (outcome == null) {
			addStandardStrengthComparisons(info, target, targetFaction, true, false, null, null);
		}
		
		info.addSectionHeading(StringHelper.getString("status", true), 
				   attacker.getBaseUIColor(), attacker.getDarkUIColor(), Alignment.MID, opad);
		
		// write our own status message for certain cancellation cases
		if (outcome == OffensiveOutcome.NO_LONGER_HOSTILE)
		{
			string = StringHelper.getString("nex_fleetIntel", "outcomeNoLongerHostile");
			string = StringHelper.substituteToken(string, "$target", target.getName());
			string = StringHelper.substituteToken(string, "$theAction", getActionNameWithArticle());
			//String factionName = target.getFaction().getDisplayName();
			//string = StringHelper.substituteToken(string, "$otherFaction", factionName);
			
			info.addPara(string, opad);
			return;
		}
		else if (outcome == OffensiveOutcome.MARKET_NO_LONGER_EXISTS)
		{
			string = StringHelper.getString("nex_fleetIntel", "outcomeNoLongerExists");
			string = StringHelper.substituteToken(string, "$target", target.getName());
			//string = StringHelper.substituteToken(string, "$theAction", getActionNameWithArticle());
			info.addPara(string, opad);
			return;
		}
		
		for (RaidStage stage : stages) {
			stage.showStageInfo(info);
			if (getStageIndex(stage) == failStage) break;
		}
	}
	
	@Override
	public CampaignFleetAPI createFleet(String factionId, RouteManager.RouteData route, MarketAPI market, Vector2f locInHyper, Random random) {
		if (random == null) random = new Random();
				
		RouteManager.OptionalFleetData extra = route.getExtra();
		
		// only one fleet is the actual invasion fleet; rest are strike fleets supporting it
		// not sure that'll even work given the spawn/despawn behavior
		boolean isInvasionFleet = extra.fleetType.equals("exerelinInvasionFleet");
		float distance = ExerelinUtilsMarket.getHyperspaceDistance(market, target);
		
		float myFP = extra.fp;
		if (!isInvasionFleet) myFP *= 0.75f;
		if (!InvasionFleetManager.USE_MARKET_FLEET_SIZE_MULT)
			myFP *= InvasionFleetManager.getFactionDoctrineFleetSizeMult(faction);
		
		float combat = myFP;
		float tanker = myFP * (0.1f + random.nextFloat() * 0.05f)
				+ TANKER_FP_PER_FLEET_FP_PER_10K_DIST * distance/10000;
		float transport = isInvasionFleet ? marinesPerFleet/100 : 0;
		float freighter = myFP * (0.1f + random.nextFloat() * 0.05f);
		
		if (isInvasionFleet) freighter *= 2;
		
		float totalFp = combat + tanker + transport + freighter;
		
		FleetParamsV3 params = new FleetParamsV3(
				market, 
				locInHyper,
				factionId,
				route == null ? null : route.getQualityOverride(),
				extra.fleetType,
				combat, // combatPts
				freighter, // freighterPts 
				tanker, // tankerPts
				transport, // transportPts
				0f, // linerPts
				0f, // utilityPts
				0f // qualityMod, won't get used since routes mostly have quality override set
				);
		
		// we don't need the variability involved in this
		if (!InvasionFleetManager.USE_MARKET_FLEET_SIZE_MULT)
			params.ignoreMarketFleetSizeMult = true;
		
		params.modeOverride = ShipPickMode.PRIORITY_THEN_ALL;
		
		if (route != null) {
			params.timestamp = route.getTimestamp();
		}
		params.random = random;
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		
		if (fleet == null || fleet.isEmpty()) return null;
		
		fleet.setName(InvasionFleetManager.getFleetName(extra.fleetType, factionId, totalFp));
		
		if (USE_REAL_MARINES) {
			fleet.getCargo().addMarines(marinesPerFleet);
			log.info("Adding marines to cargo: " + marinesPerFleet);
		}
		
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
		if (isInvasionFleet)
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_RAIDER, true);	// needed to do raids
		
		if (fleet.getFaction().getCustomBoolean(Factions.CUSTOM_PIRATE_BEHAVIOR)) {
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_PIRATE, true);
		}
		
		String postId = Ranks.POST_FLEET_COMMANDER;
		String rankId = Ranks.SPACE_CAPTAIN;	//isInvasionFleet ? Ranks.SPACE_ADMIRAL : Ranks.SPACE_COMMANDER;
		
		fleet.getCommander().setPostId(postId);
		fleet.getCommander().setRankId(rankId);
		
		log.info("Created fleet " + fleet.getName() + " of strength " + fleet.getFleetPoints() + "/" + totalFp);
		
		return fleet;
	}
	
	@Override
	public RouteFleetAssignmentAI createAssignmentAI(CampaignFleetAPI fleet, RouteManager.RouteData route) {
		RaidAssignmentAI raidAI = new RaidAssignmentAI(fleet, route, (InvActionStage)action);
		return raidAI;
	}
	
	@Override
	public String getSortString() {
		return StringHelper.getString("exerelin_invasion", "invasion", true);
	}
	
	@Override
	public String getActionName() {
		return StringHelper.getString("exerelin_invasion", "invasion");
	}
	
	@Override
	public String getActionNameWithArticle() {
		return StringHelper.getString("exerelin_invasion", "theInvasion");
	}
	
	@Override
	public String getForceType() {
		return StringHelper.getString("exerelin_invasion", "invasionForce");
	}
	
	@Override
	public String getForceTypeWithArticle() {
		return StringHelper.getString("exerelin_invasion", "theInvasionForce");
	}
	
	@Override
	public String getForceTypeHasOrHave() {
		return StringHelper.getString("exerelin_invasion", "forceHasOrHave");
	}
	
	@Override
	public String getForceTypeIsOrAre() {
		return StringHelper.getString("exerelin_invasion", "forceIsOrAre");
	}
	
	@Override
	public void addStandardStrengthComparisons(TooltipMakerAPI info, 
									MarketAPI target, FactionAPI targetFaction, 
									boolean withGround, boolean withBombard,
									String raid, String raids) {
		Color h = Misc.getHighlightColor();
		float opad = 10f;
		
		float raidFP = getRaidFPAdjusted() / getNumFleets();
		float raidStr = getRaidStr();
		
		//float defenderStr = WarSimScript.getEnemyStrength(getFaction(), system);
		float defenderStr = WarSimScript.getFactionStrength(targetFaction, system);
		float defensiveStr = defenderStr + WarSimScript.getStationStrength(targetFaction, system, target.getPrimaryEntity());
		
		float invasionGroundStr = marinesPerFleet * (1 + ExerelinConfig.getExerelinFactionConfig(faction.getId())
				.invasionStrengthBonusAttack);
		invasionGroundStr *= 1 + (getNumFleets() - 1)/2;
		
		float re = Nex_MarketCMD.getRaidEffectiveness(target, invasionGroundStr);
		
		String spaceStr = "";
		String groundStr = "";
		
		int spaceWin = 0;
		int groundWin = 0;
		
		if (raidStr < defensiveStr * 0.75f) {
			spaceStr = StringHelper.getString("outmatched");
			spaceWin = -1;
		} else if (raidStr < defensiveStr * 1.25f) {
			spaceStr = StringHelper.getString("evenlyMatched");
		} else {
			spaceStr = StringHelper.getString("superior");
			spaceWin = 1;
		}
		
		if (re < 0.33f) {
			groundStr = StringHelper.getString("outmatched");
			groundWin = -1;
		} else if (re < 0.66f) {
			groundStr = StringHelper.getString("evenlyMatched");
		} else {
			groundStr = StringHelper.getString("superior");
			groundWin = 1;
		}
		
		String key = "Successful";
		if (spaceWin == -1)
			key = "DefeatInOrbit";
		else if (groundWin == -1)
			key = "DefeatOnGround";
		else if (spaceWin < 1 || groundWin < 1)
			key = "Uncertain";
		String outcomeDesc = StringHelper.getString("nex_fleetIntel", "prediction" + key);
		outcomeDesc = StringHelper.substituteToken(outcomeDesc, "$theAction", getActionNameWithArticle(), true);
		/*
		if (groundWin == -1)
			outcomeDesc = StringHelper.getString("exerelin_invasion", "intelPredictionBombard") 
					+ " " + outcomeDesc;
		*/
		
		String compare = StringHelper.getString("nex_fleetIntel", "strCompare");
		compare = StringHelper.substituteToken(compare, "$theAction", getActionNameWithArticle(), true);
		info.addPara(compare + " " + outcomeDesc, opad, h, spaceStr, groundStr);
	}
		
	@Override
	public String getIcon() {
		return Global.getSettings().getSpriteName("intel", "nex_invasion");
		//return faction.getCrest();
	}
	
	@Override
	protected float getBaseDaysAfterEnd() {
		if (outcome == OffensiveOutcome.SUCCESS) return 15;
		return 7;
	}
}
