package exerelin.campaign.events.covertops;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.events.CampaignEventTarget;
import exerelin.campaign.ExerelinReputationAdjustmentResult;
import exerelin.utilities.NexUtilsReputation;
import java.util.HashMap;


public class AgentLowerRelationsEvent extends CovertOpsEventBase {

	public static Logger log = Global.getLogger(AgentLowerRelationsEvent.class);
	
	protected ExerelinReputationAdjustmentResult repResult2;
	protected FactionAPI thirdFaction;
		
	@Override
	public void init(String type, CampaignEventTarget eventTarget) {
		super.init(type, eventTarget);
		thirdFaction = null;
	}
	
	@Override
	public void setParam(Object param) {
		super.setParam(param);
		Map<String, Object> params = (Map<String, Object>)param;
		
		thirdFaction = (FactionAPI)params.get("thirdFaction");
		if (params.containsKey("repResult2"))
		{
			repResult2 = (ExerelinReputationAdjustmentResult)params.get("repResult2");
		}
	}
	
	/*
		To avoid future confusion:
		If successful, repResult is change between target faction and third faction
		If failed and caught, repResult is change between self and target faction,
		repResult2 is change between self and third faction
	*/
	@Override
	public Map<String, String> getTokenReplacements() {
		Map<String, String> map = super.getTokenReplacements();
		addFactionNameTokens(map, "third", thirdFaction);
		
		if (result.isSucessful())
		{
			map.put("$repEffectAbs", "" + (int)Math.ceil(Math.abs(repResult.delta*100f)));
			map.put("$newRelationStr", NexUtilsReputation.getRelationStr(faction, thirdFaction));
		}
		else if (result.isDetected())
		{
			map.put("$repEffectAbs", "" + (int)Math.ceil(Math.abs(repResult.delta*100f)));
			map.put("$newRelationStr", NexUtilsReputation.getRelationStr(agentFaction, faction));
			if (repResult2 != null)
				map.put("$repEffectAbs2", "" + (int)Math.ceil(Math.abs(repResult2.delta*100f)));
			map.put("$newRelationStr2", NexUtilsReputation.getRelationStr(agentFaction, thirdFaction));
		}
		return map;
	}
	
	@Override
	public String[] getHighlights(String stageId) {
		List<String> highlights = new ArrayList<>();
		addTokensToList(highlights, "$repEffectAbs");
		addTokensToList(highlights, "$newRelationStr");
		if (!result.isSucessful() && result.isDetected())
		{
			addTokensToList(highlights, "$repEffectAbs2");
			addTokensToList(highlights, "$newRelationStr2");
		}
		return highlights.toArray(new String[0]);
	}
	
	@Override
	public Color[] getHighlightColors(String stageId) {
		Color colorRepEffect = repResult.delta > 0 ?
				Global.getSettings().getColor("textFriendColor") : Global.getSettings().getColor("textEnemyColor");
		Color colorRepEffect2 = (repResult2 != null && repResult2.delta > 0) ?
				Global.getSettings().getColor("textFriendColor") : Global.getSettings().getColor("textEnemyColor");
		Color colorNew = agentFaction.getRelColor(faction.getId());
		Color colorNew2 = Color.WHITE;
		if (result.isSucessful())
		{
			colorNew = faction.getRelColor(thirdFaction.getId());
			return new Color[] {colorRepEffect, colorNew};
		}
		else if (result.isDetected())
		{
			colorNew2 = agentFaction.getRelColor(thirdFaction.getId());
		}
		return new Color[] {colorRepEffect, colorNew, colorRepEffect2, colorNew2};
	}
	
	@Override
	public String getCurrentMessageIcon() {
		int significance = 0;
		if (!result.isSucessful() || result.isDetected()) significance = 1;
		if (repResult.isHostile != repResult.wasHostile) significance = 2;
		if (repResult2 != null)
			if (repResult2.isHostile != repResult2.wasHostile) significance = 2;
		return EVENT_ICONS[significance];
	}
}