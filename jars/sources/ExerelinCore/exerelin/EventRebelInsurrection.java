package exerelin;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;

import exerelin.utilities.ExerelinUtilsMessaging;

import java.awt.*;
import java.util.List;

@SuppressWarnings("unchecked")
public class EventRebelInsurrection extends EventBase
{

	public EventRebelInsurrection()
	{
		setType(this.getClass().getName());
	}

	public void causeRebellionAgainstLeadingFaction(StarSystemAPI starSystemAPI)
	{
		// DEFAULTS
		FactionAPI rebelFAPI = Global.getSector().getFaction("rebel");
		String rebelAgainseFaction = "";

        // Get a target faction and declare war on them
        rebelAgainseFaction = SystemManager.getSystemManagerForAPI(starSystemAPI).getSystemStationManager().getFactionLeader();

        if(rebelAgainseFaction == null)
            return;

        if(SystemManager.getSystemManagerForAPI(starSystemAPI).getSystemOwnership(rebelAgainseFaction) != 1f)
            return; // If not total ownership, then don't rebel

        // Check if there are any rebel exerelin.fleets still in system
        for(int j = 0; j < starSystemAPI.getFleets().size(); j++)
        {
            CampaignFleetAPI fleet = (CampaignFleetAPI)starSystemAPI.getFleets().get(j);
            if(fleet.getFaction().getId().equalsIgnoreCase(rebelFAPI.getId()))
                return;
        }

		// Warn player
        if(ExerelinUtils.isPlayerInSystem(starSystemAPI))
        {
            if(rebelAgainseFaction.equalsIgnoreCase(SectorManager.getCurrentSectorManager().getPlayerFactionId()))
                ExerelinUtilsMessaging.addMessage("A number of " + SectorManager.getCurrentSectorManager().getPlayerFactionId() + " exerelin.fleets are attempting an insurrection!", Color.magenta);
            else
                ExerelinUtilsMessaging.addMessage("A number of " + rebelAgainseFaction + " exerelin.fleets are attempting an insurrection!");
        }

		List fleets = starSystemAPI.getFleets();

		for(int i = 0; i < fleets.size(); i++)
		{
			CampaignFleetAPI fleet = (CampaignFleetAPI)fleets.get(i);

			// Faction exerelin.fleets have a chance to rebel
			if(fleet.getFaction().getId().equalsIgnoreCase(rebelAgainseFaction) && ExerelinUtils.getRandomInRange(0, 1) == 0)
			{
				String fleetFullName = fleet.getFullName();
				if(fleetFullName.contains("Boarding") || fleetFullName.contains("Logistics") || fleetFullName.contains("Mining") || fleetFullName.contains("Command"))
					continue; // Skip non-combat exerelin.fleets

				if(fleet.getFullName().equalsIgnoreCase(Global.getSector().getPlayerFleet().getFullName()))
					continue; // Skip the players fleet

				fleet.setFaction("rebel");

				String fleetName = rebelAgainseFaction + " Insurrection Fleet";

				fleet.setName(fleetName);

				fleet.clearAssignments();
				SectorEntityToken station = ExerelinUtils.getRandomStationInSystemForFaction(rebelAgainseFaction, starSystemAPI);
				fleet.addAssignment(FleetAssignment.ATTACK_LOCATION, station, 30);
				fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, ExerelinUtils.getRandomOffMapPoint(starSystemAPI), 30);
			}
		}
	}
}






