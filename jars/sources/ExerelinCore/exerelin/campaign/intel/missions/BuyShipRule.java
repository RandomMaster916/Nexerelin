package exerelin.campaign.intel.missions;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.DModManager;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import static exerelin.campaign.intel.missions.BuyShip.getString;

import java.awt.*;
import java.util.*;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j;

@Log4j
public abstract class BuyShipRule {

	@Getter	@Setter	protected BuyShip mission;

	abstract void init(CampaignFleetAPI fleet);
	abstract boolean isShipAllowed(FleetMemberAPI member);
	
	List<FleetMemberAPI> getShipsMeetingRule(CampaignFleetAPI fleet) {
		List<FleetMemberAPI> list = new ArrayList<>();
		for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
			if (isShipAllowed(member)) list.add(member);
		}
		return list;
	}
	
	/**
	 * Called before initializing the rule.
	 * @param fleet
	 * @return
	 */
	public boolean wantToUseRule(CampaignFleetAPI fleet) {
		return true;
	}
	
	/**
	 * Called after initializing the rule.
	 * @param fleet
	 * @return
	 */
	public boolean canUseRule(CampaignFleetAPI fleet) {
		return true;
	}
	
	abstract void printRule(TooltipMakerAPI tooltip, float pad);

	public boolean shouldCheckShipForRule(FleetMemberAPI member) {
		return isShipAllowedStatic(member, mission);
	}
	
	public static boolean isShipAllowedStatic(FleetMemberAPI member, BuyShip mission) {
		if (member.getVariant().hasTag(Tags.SHIP_CAN_NOT_SCUTTLE)) {
            return false;
        }
		// todo: maybe Cabal should allow even automated ships?
		MarketAPI market = mission.getPostingLocation().getMarket();
		if (Misc.isAutomated(member) && market != null && market.getFaction().getIllegalCommodities().contains(Commodities.AI_CORES)) {
			return false;
		}
        return !Misc.isUnremovable(member.getCaptain());
	}
	
	public static BuyShipRule instantiate(Class clazz, BuyShip mission) {
		BuyShipRule rule = null;
		try {
			rule = (BuyShipRule)clazz.newInstance();
			rule.setMission(mission);
		} catch (IllegalAccessException | InstantiationException ex) {
			log.error("Failed to load rule for buy ship mission " + clazz.getName(), ex);
		}
		return rule;
	}
	
	// =========================================================================
	
	public static class DesignTypeRule extends BuyShipRule {

		public String designType;
		
		@Override
		public void init(CampaignFleetAPI fleet) {
			Set<String> designTypes = new HashSet<>();
			
			WeightedRandomPicker<String> picker = new WeightedRandomPicker<>(mission.getGenRandom());
			for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
				if (!shouldCheckShipForRule(member)) continue;
				designTypes.add(member.getHullSpec().getManufacturer());
			}
			
			// yeet anything we could buy on a local store
			for (FleetMemberAPI forSale : getShipsAtMarket()) {
				designTypes.remove(forSale.getHullSpec().getManufacturer());
			}
			
			picker.addAll(designTypes);
			designType = picker.pick();
		}
		
		protected List<FleetMemberAPI> getShipsAtMarket() {
			List<FleetMemberAPI> ships = new ArrayList<>();
			try {
				MarketAPI market = Global.getSector().getCampaignUI().getCurrentInteractionDialog().getInteractionTarget().getMarket();
				for (SubmarketAPI sub : market.getSubmarketsCopy()) {
					if (sub.getPlugin().isFreeTransfer()) continue;
					for (FleetMemberAPI inSub : sub.getCargo().getMothballedShips().getMembersListCopy()) {
						ships.add(inSub);
					}
				}
			} catch (Exception ex) {
				log.error(ex);
			}
			return ships;
		}

		@Override
		public boolean isShipAllowed(FleetMemberAPI member) {
			return member.getHullSpec().getManufacturer().equals(designType);
		}
		
		@Override
		public boolean canUseRule(CampaignFleetAPI fleet) {
			return designType != null;
		}

		@Override
		public void printRule(TooltipMakerAPI tooltip, float pad) {
			Color color = Global.getSettings().getDesignTypeColor(designType);
			tooltip.addPara(getString("ruleDesignType"), pad, color, designType);
		}
	}
	
	public static class HullSizeRule extends BuyShipRule {

		public HullSize size;
		
		@Override
		public void init(CampaignFleetAPI fleet) {
			WeightedRandomPicker<HullSize> picker = new WeightedRandomPicker<>(mission.getGenRandom());
			for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
				if (!shouldCheckShipForRule(member)) continue;
				picker.add(member.getHullSpec().getHullSize());
			}
			size = picker.pick();
		}

		@Override
		public boolean isShipAllowed(FleetMemberAPI member) {
			return member.getHullSpec().getHullSize() == size;
		}

		@Override
		public void printRule(TooltipMakerAPI tooltip, float pad) {
			String sizeStr = size.toString().toLowerCase();
			tooltip.addPara(getString("ruleHullSize"), pad, Misc.getHighlightColor(), sizeStr);
		}
	}
	
	public static class DPRule extends BuyShipRule {
		
		public static final float CHANCE_TO_USE_RULE = 0.25f;
		public static final int[] MIN_DP = new int[] {0, 4, 5, 11, 22, 35};
		@Deprecated public int dp;		
		
		@Override
		public void init(CampaignFleetAPI fleet) {
			
			// median; gives too-low values
			/*
			List<Float> dpValues = new ArrayList<>();
			for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
				if (!shouldCheckShipForRule(member)) continue;
				dpValues.add(member.getBaseDeploymentCostSupplies());
				log.info("Added DP value: " + member.getBaseDeploymentCostSupplies());
			}
			
			Collections.sort(dpValues);
			int midIndex = (dpValues.size()/2) - 1;
			if (midIndex < 0) midIndex = 0;

			float dpRaw = (float)dpValues.get(midIndex);
			log.info(String.format("Index %s, DP %s", midIndex, dpRaw));
			dp = (int)dpRaw;
			*/
			
			// mean; becomes nonsensical when it asks for an 11 DP frigate
			/*
			float dpSum = 0;
			int count = 0;
			// mean
			for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
				if (!shouldCheckShipForRule(member)) continue;
				dpSum += member.getBaseDeploymentCostSupplies();
				count++;
			}
			dp = (int)dpSum/count;
			*/
		}

		@Override
		public boolean isShipAllowed(FleetMemberAPI member) {
			//return member.getBaseDeploymentCostSupplies() >= dp;
			int sizeIndex = member.getHullSpec().getHullSize().ordinal();
			return member.getBaseDeploymentCostSupplies() > MIN_DP[sizeIndex];
		}

		@Override
		public void printRule(TooltipMakerAPI tooltip, float pad) {
			//tooltip.addPara("[temp] DP at least: " + dp, pad, Misc.getHighlightColor(), dp + "");
			// TODO
			tooltip.addPara(getString("ruleDP"), pad, Misc.getHighlightColor(), 
					MIN_DP[2] + "", MIN_DP[3] + "", MIN_DP[4] + "", MIN_DP[5] + "");
		}
		
		@Override
		public boolean wantToUseRule(CampaignFleetAPI fleet) {
			Random random = mission.getGenRandom();
			if (random == null) random = new Random();
			return random.nextFloat() < CHANCE_TO_USE_RULE;
		}
	}

	public static class DModRule extends BuyShipRule {

		public static final float CHANCE_TO_USE_RULE = 0.4f;
		public int maxDMods = -1;

		@Override
		void init(CampaignFleetAPI fleet) {
			MarketAPI market = mission.getPostingLocation().getMarket();
			if (market == null) return;

			maxDMods = Math.round(1 - market.getShipQualityFactor() * 0.5f);
			if (maxDMods < 1) maxDMods = 1;
		}

		@Override
		boolean isShipAllowed(FleetMemberAPI member) {
			if (maxDMods < 0) return true;
			return DModManager.getNumDMods(member.getVariant()) <= maxDMods;
		}

		@Override
		void printRule(TooltipMakerAPI tooltip, float pad) {
			tooltip.addPara(getString("ruleDMods"), pad, Misc.getNegativeHighlightColor(), maxDMods + "");
		}

		@Override
		public boolean wantToUseRule(CampaignFleetAPI fleet) {
			Random random = mission.getGenRandom();
			if (random == null) random = new Random();
			return random.nextFloat() < CHANCE_TO_USE_RULE;
		}
	}
}