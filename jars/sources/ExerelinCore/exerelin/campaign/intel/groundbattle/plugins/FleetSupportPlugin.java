package exerelin.campaign.intel.groundbattle.plugins;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import exerelin.campaign.intel.groundbattle.GBConstants;
import exerelin.campaign.intel.groundbattle.GroundBattleIntel;
import exerelin.campaign.intel.groundbattle.GroundUnit;
import exerelin.campaign.intel.groundbattle.IndustryForBattle;
import exerelin.utilities.NexUtilsGUI;
import exerelin.utilities.StringHelper;
import java.awt.Color;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FleetSupportPlugin extends BaseGroundBattlePlugin {
	
	protected float atkBonus = 0;
	protected float defBonus = 0;
	protected transient Set<GroundUnit> atkUnits;
	protected transient Set<GroundUnit> defUnits;
	protected transient float atkStrSum = 0;
	protected transient float defStrSum = 0;
	
	protected float getBonusFromFleets(List<CampaignFleetAPI> fleets) {
		float increment = 0;
		for (CampaignFleetAPI fleet : fleets) {
			 increment += Misc.getFleetwideTotalMod(fleet, Stats.FLEET_GROUND_SUPPORT, 0f);
		}
		if (increment < 0) return 0;
		return increment;
	}
		
	@Override
	public void advance(float days) {
		List<CampaignFleetAPI> atkFleets = intel.getSupportingFleets(true);
		List<CampaignFleetAPI> defFleets = intel.getSupportingFleets(false);
		
		float atkBonusIncrement = getBonusFromFleets(atkFleets);
		atkBonus += atkBonusIncrement * days;
		
		float defBonusIncrement = getBonusFromFleets(defFleets);
		defBonus += defBonusIncrement * days;
		
		Global.getSector().addPing(intel.getMarket().getPrimaryEntity(), "nex_invasion_support_range");
	}
	
	protected void getEligibleUnits(Set<GroundUnit> collection, boolean attacker,
			Collection<IndustryForBattle> contestedLocations) 
	{
		for (GroundUnit unit : intel.getSide(attacker).getUnits()) {
			if (unit.getLocation() == null) continue;
			if (unit.isAttackPrevented()) continue;
			if (!contestedLocations.contains(unit.getLocation())) continue;
			
			collection.add(unit);
			if (attacker) atkStrSum += unit.getBaseStrength();
			else defStrSum += unit.getBaseStrength();
		}
	}
	
	protected float getUnitAttackBonus(GroundUnit unit) {
		if (atkUnits == null || defUnits == null) return 0;
		
		float bonus = 0;
		float shareMult = 1;
		
		if (unit.isAttacker()) {
			if (!atkUnits.contains(unit)) {
				return 0;
			}
			if (atkStrSum == 0) return 0;
			shareMult = unit.getBaseStrength()/atkStrSum;
			bonus = atkBonus * shareMult * 0.5f;
		} else {
			if (!defUnits.contains(unit)) {
				return 0;
			}
			if (defStrSum == 0) return 0;
			shareMult = unit.getBaseStrength()/defStrSum;
			bonus = defBonus * shareMult * 0.5f;
		}
		if (bonus != 0) {
			
			Global.getLogger(this.getClass()).info(String.format(
					"    Unit %s receiving %s bonus damage from ground support (share %s)", 
					unit.getName(), bonus, StringHelper.toPercent(shareMult)));
		}
		return bonus;
	}
	
	@Override
	public void beforeCombatResolve(int turn, int numThisTurn) {
		Set<IndustryForBattle> contestedLocations = new HashSet<>();
		for (IndustryForBattle ifb : intel.getIndustries()) {
			if (ifb.isContested()) contestedLocations.add(ifb);
		}
		
		atkUnits = new HashSet<>();
		defUnits = new HashSet<>();
		atkStrSum = 0;
		defStrSum = 0;
		
		if (atkBonus > 0) {
			getEligibleUnits(atkUnits, true, contestedLocations);
		}
		if (defBonus > 0) {
			getEligibleUnits(defUnits, false, contestedLocations);
		}
	}
	
	@Override
	public MutableStat modifyDamageDealt(GroundUnit unit, MutableStat dmg) {
		float bonus = getUnitAttackBonus(unit);
		if (bonus != 0)
			dmg.modifyFlat("groundSupport", bonus, StringHelper.getString("nex_invasion2", "modifierGroundSupport"));
		return dmg;
	}
	
	@Override
	public void afterTurnResolve(int turn) {
		atkBonus = 0;
		defBonus = 0;
		atkStrSum = 0;
		defStrSum = 0;
	}
	
	protected boolean hasTooltip(boolean isAttacker) {
		if (isAttacker && atkBonus > 0) return true;
		if (!isAttacker && defBonus > 0) return true;
		
		List<CampaignFleetAPI> fleets = intel.getSupportingFleets(isAttacker);
		float increment = getBonusFromFleets(fleets);
		return increment > 0;
	}
	
	public void addModifierEntry(TooltipMakerAPI info, CustomPanelAPI outer, 
			float width, float pad, Boolean isAttacker) {
		
		if (isAttacker == null) return;
		
		if (!hasTooltip(isAttacker)) return;
		
		String icon = "graphics/hullmods/ground_support.png";
		
		NexUtilsGUI.CustomPanelGenResult gen = NexUtilsGUI.addPanelWithFixedWidthImage(outer, 
				null, width, GroundBattlePlugin.MODIFIER_ENTRY_HEIGHT, GroundBattleIntel.getString("modifierGroundSupport"), 
				width - GroundBattlePlugin.MODIFIER_ENTRY_HEIGHT - 8, 8, 
				icon, GroundBattlePlugin.MODIFIER_ENTRY_HEIGHT, 3, 
				Misc.getPositiveHighlightColor(), true, getModifierTooltip(isAttacker));
		
		info.addCustom(gen.panel, pad);
	}
	
	public TooltipMakerAPI.TooltipCreator getModifierTooltip(final boolean isAttacker) {
		return new TooltipMakerAPI.TooltipCreator() {
				@Override
				public boolean isTooltipExpandable(Object tooltipParam) {
					return false;
				}

				@Override
				public float getTooltipWidth(Object tooltipParam) {
					return 360;
				}

				@Override
				public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
					processTooltip(tooltip, expanded, tooltipParam, isAttacker);
				}
		};
	}
	
	public void processTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam, boolean isAttacker) 
	{
		Color h = Misc.getHighlightColor();
		
		String str = GroundBattleIntel.getString("modifierGroundSupportDesc1");
		tooltip.addPara(str, 0, h, (int)GBConstants.MAX_SUPPORT_DIST + "");
		
		str = GroundBattleIntel.getString("modifierGroundSupportDesc2");
		tooltip.addPara(str, 3, h, String.format("%.0f", isAttacker ? atkBonus : defBonus));
	}
}