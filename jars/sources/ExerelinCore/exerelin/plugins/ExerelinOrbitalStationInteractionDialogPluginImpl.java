package exerelin.plugins;

import java.awt.Color;

import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.combat.WeaponAPI;
import exerelin.*;
import exerelin.fleets.AsteroidMiningFleet;
import exerelin.fleets.StationAttackFleet;
import exerelin.fleets.WarFleet;
import exerelin.fleets.GasMiningFleet;
import exerelin.utilities.ExerelinConfig;
import exerelin.utilities.ExerelinMessageManager;
import exerelin.utilities.ExerelinMessage;
import exerelin.utilities.ExerelinUtilsFaction;
import org.lwjgl.input.Keyboard;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

public class ExerelinOrbitalStationInteractionDialogPluginImpl implements InteractionDialogPlugin, CoreInteractionListener {

    private static enum OptionId {
        INIT,
        INIT_NO_TEXT,

        STATION_SERVICES,
        TRADE_CARGO,
        TRADE_SHIPS,
        REFIT,
        REPAIR_ALL,
        ACCESS_PLAYER_STORAGE,
        STORE_SHIP_PLAYER_HANGER,
        RETRIEVE_SHIP_PLAYER_HANGER,

        INTEL,
        DISPLAY_ALLIES,
        DISPLAY_ENEMIES,
        DISPLAY_STATION_STATUS,
        DISPLAY_MESSAGES,

        PLAYER_FLEET_COMMAND,
        GAS_MINING_FLEET,
        ASTEROID_MINING_FLEET,
        ATTACK_FLEET,
        DEFENSE_FLEET,
        STATION_ATTACK_FLEET,

        STATION_FLEET_COMMAND,
        STANCE_BALANCED,
        STANCE_ATTACK,
        STANCE_DEFEND,
        STANCE_PATROL,
        SET_ALL_IN_SYSTEM,

        PLANT_AGENT,
        DROP_OFF_PRISONER,
        PLANT_SABOTEUR,
        JOIN_FACTION,
        LEAVE_FACTION,
        LEAVE_FACTION_CONFIRM,

        BACK,
        LEAVE,
    }

    private InteractionDialogAPI dialog;
    private TextPanelAPI textPanel;
    private OptionPanelAPI options;
    private VisualPanelAPI visual;

    private CampaignFleetAPI playerFleet;
    private SectorEntityToken station;

    private static final Color HIGHLIGHT_COLOR = Global.getSettings().getColor("buttonShortcut");

    private CargoAPI restrictedItems;

    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        textPanel = dialog.getTextPanel();
        options = dialog.getOptionPanel();
        visual = dialog.getVisualPanel();

        playerFleet = Global.getSector().getPlayerFleet();
        station = (SectorEntityToken) dialog.getInteractionTarget();

        visual.setVisualFade(0.25f, 0.25f);

        dialog.setOptionOnEscape("Leave", OptionId.LEAVE);

        optionSelected(null, OptionId.INIT);

        restrictedItems = Global.getFactory().createCargo(true);
        restrictedItems.initMothballedShips("neutral");
        this.removeRestrictedCargo();
    }

    private EngagementResultAPI lastResult = null;
    public void backFromEngagement(EngagementResultAPI result) {
        // no combat here, so this won't get called
    }

    public void optionSelected(String text, Object optionData) {
        if (optionData == null) return;

        OptionId option = (OptionId) optionData;

        if (text != null) {
            textPanel.addParagraph(text, Global.getSettings().getColor("buttonText"));
        }

        StationRecord stationRecord = SystemManager.getSystemManagerForAPI((StarSystemAPI)this.station.getContainingLocation()).getSystemStationManager().getStationRecordForToken(this.station);

        switch (option) {
            case INIT:
                addText(getString("approach"));
                this.displayStationStatus();
            case INIT_NO_TEXT:
                createInitialOptions();
                if (station.getCustomInteractionDialogImageVisual() != null) {
                    visual.showImageVisual(station.getCustomInteractionDialogImageVisual());
                } else {
                    visual.showImagePortion("illustrations", "hound_hangar", 800, 800, 0, 0, 400, 400);
                }
                break;
            case STATION_SERVICES:
                options.clearOptions();
                this.createStationServicesOptions();
                break;
            case TRADE_CARGO:
                addText(getString("tradeCargo"));
                visual.showCore(CoreUITabId.CARGO, station, this);
                options.clearOptions();
                this.createStationServicesOptions();
                break;
            case TRADE_SHIPS:
                addText(getString("tradeShips"));
                visual.showCore(CoreUITabId.FLEET, station, this);
                options.clearOptions();
                this.createStationServicesOptions();
                break;
            case REFIT:
                addText(getString("refit"));
                visual.showCore(CoreUITabId.REFIT, station, this);
                options.clearOptions();
                this.createStationServicesOptions();
                break;
            case REPAIR_ALL:
                performRepairs();
                break;
            case ACCESS_PLAYER_STORAGE:
                visual.showLoot("Personal Storage", stationRecord.getPlayerStorage(), this);
                break;
            case STORE_SHIP_PLAYER_HANGER:
                this.storeShips();
                break;
            case RETRIEVE_SHIP_PLAYER_HANGER:
                this.retrieveShips(stationRecord.getPlayerStorage());
                break;
            case PLANT_AGENT:
                Global.getSector().getPlayerFleet().getCargo().removeItems(CargoAPI.CargoItemType.RESOURCES, "agent", 1);
                station.getCargo().addItems(CargoAPI.CargoItemType.RESOURCES, "agent", 1);
                options.clearOptions();
                createInitialOptions();
                break;
            case DROP_OFF_PRISONER:
                Global.getSector().getPlayerFleet().getCargo().removeItems(CargoAPI.CargoItemType.RESOURCES, "prisoner", 1);
                station.getCargo().addItems(CargoAPI.CargoItemType.RESOURCES, "prisoner", 1);
                options.clearOptions();
                createInitialOptions();
                break;
            case PLANT_SABOTEUR:
                Global.getSector().getPlayerFleet().getCargo().removeItems(CargoAPI.CargoItemType.RESOURCES, "saboteur", 1);
                station.getCargo().addItems(CargoAPI.CargoItemType.RESOURCES, "saboteur", 1);
                options.clearOptions();
                createInitialOptions();
                break;
            case INTEL:
                options.clearOptions();
                this.createIntelOptions();
                break;
            case DISPLAY_ALLIES:
                this.displayRleationships(1);
                break;
            case DISPLAY_ENEMIES:
                this.displayRleationships(-1);
                break;
            case DISPLAY_STATION_STATUS:
                this.displayStationStatus();
                break;
            case DISPLAY_MESSAGES:
                this.displayMessages();
                break;
            case STATION_FLEET_COMMAND:
                options.clearOptions();
                createStationFleetCommandOptions();
                break;
            case STANCE_BALANCED:
                this.setStationStance(StationRecord.StationFleetStance.BALANCED);
                options.clearOptions();
                createStationFleetCommandOptions();
                break;
            case STANCE_ATTACK:
                this.setStationStance(StationRecord.StationFleetStance.ATTACK);
                options.clearOptions();
                createStationFleetCommandOptions();
                break;
            case STANCE_DEFEND:
                this.setStationStance(StationRecord.StationFleetStance.DEFENSE);
                options.clearOptions();
                createStationFleetCommandOptions();
                break;
            case STANCE_PATROL:
                this.setStationStance(StationRecord.StationFleetStance.PATROL);
                options.clearOptions();
                createStationFleetCommandOptions();
                break;
            case SET_ALL_IN_SYSTEM:
                this.setAllStationsInSystemStance();
                break;
            case PLAYER_FLEET_COMMAND:
                options.clearOptions();
                this.createPlayerFleetCommandOptions();
                break;
            case DEFENSE_FLEET:
                createPlayerCommandedDefenseFleet();
                options.clearOptions();
                this.createPlayerFleetCommandOptions();
                break;
            case ASTEROID_MINING_FLEET:
                createPlayerCommandedAsteroidMiningFleet();
                options.clearOptions();
                this.createPlayerFleetCommandOptions();
                break;
            case GAS_MINING_FLEET:
                createPlayerCommandedGasMiningFleet();
                options.clearOptions();
                this.createPlayerFleetCommandOptions();
                break;
            case ATTACK_FLEET:
                createPlayerCommandedAttackFleet();
                options.clearOptions();
                this.createPlayerFleetCommandOptions();
                break;
            case STATION_ATTACK_FLEET:
                createPlayerCommandedBoardingFleet();
                options.clearOptions();
                this.createPlayerFleetCommandOptions();
                break;
            case JOIN_FACTION:
                SectorManager.getCurrentSectorManager().getDiplomacyManager().playerJoinFaction(this.station.getFaction().getId());
                options.clearOptions();
                createInitialOptions();
                break;
            case LEAVE_FACTION:
                createLeaveFactionOptions();
                break;
            case LEAVE_FACTION_CONFIRM:
                SectorManager.getCurrentSectorManager().getDiplomacyManager().playerLeaveFaction();
                options.clearOptions();
                createInitialOptions();
                break;
            case BACK:
                options.clearOptions();
                createInitialOptions();
                break;
            case LEAVE:
                this.restoreRestrictedCargo();
                Global.getSector().setPaused(false);
                dialog.dismiss();
                break;
        }
    }

    private void createInitialOptions() {
        options.clearOptions();

        int influenceWithFaction;

        if(this.station.getFaction().getId().equalsIgnoreCase("independent")
                || this.station.getFaction().getId().equalsIgnoreCase("neutral"))
            influenceWithFaction = 25;
        else
            influenceWithFaction = SectorManager.getCurrentSectorManager().getDiplomacyManager().getRecordForFaction(this.station.getFaction().getId()).getPlayerInfluence();

        options.addOption("Station Services", OptionId.STATION_SERVICES);

        if(influenceWithFaction < 25)
        {
            options.setEnabled(OptionId.STATION_SERVICES, false);
            options.setTooltip(OptionId.STATION_SERVICES, "Influence not high enough. Requires 25.");
        }

        if(Global.getSector().getPlayerFleet().getCargo().getQuantity(CargoAPI.CargoItemType.RESOURCES, "agent") > 0
                && !station.getFaction().getId().equalsIgnoreCase(Global.getSector().getPlayerFleet().getFaction().getId()))
            options.addOption("Plant agent on station", OptionId.PLANT_AGENT);

        if(Global.getSector().getPlayerFleet().getCargo().getQuantity(CargoAPI.CargoItemType.RESOURCES, "prisoner") > 0
                && !station.getFaction().getId().equalsIgnoreCase(Global.getSector().getPlayerFleet().getFaction().getId()))
            options.addOption("Drop prisoner off at station", OptionId.DROP_OFF_PRISONER);

        if(Global.getSector().getPlayerFleet().getCargo().getQuantity(CargoAPI.CargoItemType.RESOURCES, "saboteur") > 0
                && !station.getFaction().getId().equalsIgnoreCase(Global.getSector().getPlayerFleet().getFaction().getId()))
            options.addOption("Plant saboteur on station", OptionId.PLANT_SABOTEUR);


        //display diplomacy reports and message history
        if(!station.getFaction().getId().equalsIgnoreCase("abandoned") && !station.getFaction().getId().equalsIgnoreCase("rebel"))
            options.addOption("Intel Reports", OptionId.INTEL);

        if(station.getFaction().getId().equalsIgnoreCase(Global.getSector().getPlayerFleet().getFaction().getId()))
            options.addOption("Station Fleet Command", OptionId.STATION_FLEET_COMMAND);

        if(this.station.getFaction().getRelationship(Global.getSector().getPlayerFleet().getFaction().getId()) < 0 || this.station.getFaction().getId().equalsIgnoreCase(Global.getSector().getPlayerFleet().getFaction().getId()))
            options.addOption("Strategic Fleet Command", OptionId.PLAYER_FLEET_COMMAND);

        if(influenceWithFaction < 75) {
            options.setEnabled(OptionId.PLAYER_FLEET_COMMAND, false);
            options.setTooltip(OptionId.PLAYER_FLEET_COMMAND, "Influence not high enough. Requires 75.");
        }

        if(influenceWithFaction < 100) {
            options.setEnabled(OptionId.STATION_FLEET_COMMAND, false);
            options.setTooltip(OptionId.STATION_FLEET_COMMAND, "Influence not high enough. Requires 100.");
        }

        if(SectorManager.getCurrentSectorManager().isPlayerInPlayerFaction())
            options.addOption("Request to join " + this.station.getFaction().getDisplayName(), OptionId.JOIN_FACTION);

        if(SectorManager.getCurrentSectorManager().getPlayerFactionId().equalsIgnoreCase(this.station.getFaction().getId()))
            options.addOption("Leave " + this.station.getFaction().getDisplayName(), OptionId.LEAVE_FACTION);

        if(influenceWithFaction < 50){
            options.setEnabled(OptionId.JOIN_FACTION, false);
            options.setTooltip(OptionId.JOIN_FACTION, "Influence not high enough. Requires 50.");
        }

        options.addOption("Leave Station", OptionId.LEAVE);
    }

    private void createStationServicesOptions()
    {
        if (station.getFaction().isNeutralFaction() || station.getFullName().contains("Omnifactory")) {
            options.addOption("Transfer cargo or personnel", OptionId.TRADE_CARGO);
            options.setShortcut(OptionId.TRADE_CARGO, Keyboard.KEY_I, false, false, false, true);
            options.addOption("Transfer ships to or from this station", OptionId.TRADE_SHIPS);
            options.setShortcut(OptionId.TRADE_SHIPS, Keyboard.KEY_F, false, false, false, true);
            options.addOption("Make use of the dockyard's refitting facilities", OptionId.REFIT);
            options.setShortcut(OptionId.REFIT, Keyboard.KEY_R, false, false, false, true);
        }
        else
        {
            options.addOption("Trade, or hire personnel", OptionId.TRADE_CARGO);
            options.setShortcut(OptionId.TRADE_CARGO, Keyboard.KEY_I, false, false, false, true);
            options.addOption("Buy or sell ships", OptionId.TRADE_SHIPS, null);
            options.setShortcut(OptionId.TRADE_SHIPS, Keyboard.KEY_F, false, false, false, true);
            options.addOption("Make use of the dockyard's refitting facilities", OptionId.REFIT);
            options.setShortcut(OptionId.REFIT, Keyboard.KEY_R, false, false, false, true);
        }

        if (station.getFaction().getRelationship(Global.getSector().getPlayerFleet().getFaction().getId()) >= 1
                || station.getFaction().getId().equalsIgnoreCase(Global.getSector().getPlayerFleet().getFaction().getId())
                || station.getFaction().isNeutralFaction()) {
            float needed = playerFleet.getLogistics().getTotalRepairSupplyCost();
            float supplies = playerFleet.getCargo().getSupplies();
            options.addOption("Repair your ships at the station's dockyard", OptionId.REPAIR_ALL);
            options.setShortcut(OptionId.REPAIR_ALL, Keyboard.KEY_A, false, false, false, true);

            if (needed <= 0) {
                options.setEnabled(OptionId.REPAIR_ALL, false);
                options.setTooltip(OptionId.REPAIR_ALL, getString("repairTooltipAlreadyRepaired"));
            } else if (supplies < needed) {
                options.setEnabled(OptionId.REPAIR_ALL, false);
                options.setTooltip(OptionId.REPAIR_ALL, getString("repairTooltipNotEnough"));
                options.setTooltipHighlightColors(OptionId.REPAIR_ALL, HIGHLIGHT_COLOR, HIGHLIGHT_COLOR);
                options.setTooltipHighlights(OptionId.REPAIR_ALL, "" + (int) needed, "" + (int) supplies);
            } else {
                options.setTooltip(OptionId.REPAIR_ALL, getString("repairTooltip"));
                options.setTooltipHighlightColors(OptionId.REPAIR_ALL, HIGHLIGHT_COLOR, HIGHLIGHT_COLOR);
                options.setTooltipHighlights(OptionId.REPAIR_ALL, "" + (int) needed, "" + (int) supplies);
            }
        }

        options.addOption("Personal Storage", OptionId.ACCESS_PLAYER_STORAGE);
        options.setShortcut(OptionId.ACCESS_PLAYER_STORAGE, Keyboard.KEY_P, false, false, false, true);
        options.addOption("Store Ships", OptionId.STORE_SHIP_PLAYER_HANGER);
        options.setTooltip(OptionId.STORE_SHIP_PLAYER_HANGER, "Mothball ships in your personal hanger.");
        options.addOption("Retrieve Ships", OptionId.RETRIEVE_SHIP_PLAYER_HANGER);

        if(playerFleet.getFleetData().getMembersListCopy().size() == 1) {
            options.setEnabled(OptionId.STORE_SHIP_PLAYER_HANGER, false);
            options.setTooltip(OptionId.STORE_SHIP_PLAYER_HANGER, "Not enough ships in fleet.");
        }

        if(SystemManager.getSystemManagerForAPI((StarSystemAPI)this.station.getContainingLocation()).getSystemStationManager().getStationRecordForToken(this.station).getPlayerStorage().getMothballedShips().getMembersListCopy().size() == 0){
            options.setEnabled(OptionId.RETRIEVE_SHIP_PLAYER_HANGER, false);
            options.setTooltip(OptionId.RETRIEVE_SHIP_PLAYER_HANGER, "No ships in hanger.");
        }

        options.addOption("Back", OptionId.BACK);
    }

    private void createIntelOptions()
    {
        options.addOption("Alliance Report", OptionId.DISPLAY_ALLIES);
        options.addOption("Enemy Report", OptionId.DISPLAY_ENEMIES);
        options.addOption("Station Status", OptionId.DISPLAY_STATION_STATUS);
        options.addOption("Recent Messages", OptionId.DISPLAY_MESSAGES);
        options.addOption("Back", OptionId.BACK);
    }

    private void createStationFleetCommandOptions()
    {
        String currentStance = SystemManager.getSystemManagerForAPI((StarSystemAPI)this.station.getContainingLocation()).getSystemStationManager().getStationRecordForToken(this.station).getStationFleetStance().toString();

        options.addOption("Set behavior to Balanced", OptionId.STANCE_BALANCED, "1 ATTACK, 1 DEFENSE, 1 PATROL");
        options.addOption("Set behavior to Attack", OptionId.STANCE_ATTACK, "2 ATTACK, 1 DEFENSE");
        options.addOption("Set behavior to Defend", OptionId.STANCE_DEFEND, "3 DEFENSE");
        options.addOption("Set behavior to Patrol", OptionId.STANCE_PATROL, "1 DEFENSE, 2 PATROL");
        options.addOption("Set all stations in system to " + currentStance, OptionId.SET_ALL_IN_SYSTEM, "Set all stations in system to this stations current behaviour");
        options.addOption("Back", OptionId.BACK);
    }

    private void createPlayerFleetCommandOptions()
    {
        if(this.station.getFaction().getRelationship(Global.getSector().getPlayerFleet().getFaction().getId()) < 0)
        {
            options.addOption("Organise Attack Fleet ($80000)", OptionId.ATTACK_FLEET, "Mobilise an Attack Fleet from your faction's closest station.");
            options.addOption("Organise Station Boarding Fleet ($80000)", OptionId.STATION_ATTACK_FLEET, "Mobilise a Boarding Fleet from your faction's closest station.");
        }
        else if(this.station.getFaction().getId().equalsIgnoreCase(Global.getSector().getPlayerFleet().getFaction().getId()))
        {
            options.addOption("Organise Defense Fleet ($80000)", OptionId.DEFENSE_FLEET);
            options.addOption("Organise Asteroid Mining Fleet ($15000)", OptionId.ASTEROID_MINING_FLEET);
            options.addOption("Organise Gas Mining Fleet ($15000", OptionId.GAS_MINING_FLEET);
        }

        if(Global.getSector().getPlayerFleet().getCargo().getCredits().get() < 15000f)
        {
            options.setEnabled(OptionId.ATTACK_FLEET, false);
            options.setEnabled(OptionId.STATION_ATTACK_FLEET, false);
            options.setEnabled(OptionId.DEFENSE_FLEET, false);
            options.setEnabled(OptionId.ASTEROID_MINING_FLEET, false);
            options.setEnabled(OptionId.GAS_MINING_FLEET, false);
        }
        else if(Global.getSector().getPlayerFleet().getCargo().getCredits().get() < 80000f)
        {
            options.setEnabled(OptionId.ATTACK_FLEET, false);
            options.setEnabled(OptionId.STATION_ATTACK_FLEET, false);
            options.setEnabled(OptionId.DEFENSE_FLEET, false);
            options.setEnabled(OptionId.ASTEROID_MINING_FLEET, true);
            options.setEnabled(OptionId.GAS_MINING_FLEET, true);
        }
        else
        {
            options.setEnabled(OptionId.ATTACK_FLEET, true);
            options.setEnabled(OptionId.STATION_ATTACK_FLEET, true);
            options.setEnabled(OptionId.DEFENSE_FLEET, true);
            options.setEnabled(OptionId.ASTEROID_MINING_FLEET, true);
            options.setEnabled(OptionId.GAS_MINING_FLEET, true);
        }

        options.addOption("Back", OptionId.BACK);
    }

    private void createLeaveFactionOptions()
    {
        options.addOption("Confirm Leave " + this.station.getFaction().getDisplayName(), OptionId.LEAVE_FACTION);
        options.addOption("Back", OptionId.BACK);
    }

    private void performRepairs() {
        addText(getString("repair"));
        float supplies = playerFleet.getCargo().getSupplies();
        float needed = playerFleet.getLogistics().getTotalRepairSupplyCost();

        textPanel.highlightLastInLastPara("" + (int) needed, HIGHLIGHT_COLOR);

        for (FleetMemberAPI member : playerFleet.getFleetData().getMembersListCopy()) {
            member.getStatus().repairFully();
            float max = member.getRepairTracker().getMaxCR();
            float curr = member.getRepairTracker().getBaseCR();
            if (max > curr) {
                member.getRepairTracker().applyCREvent(max - curr, "Repaired at station");
            }
        }
        if (needed > 0) {
            playerFleet.getCargo().removeSupplies(needed);
        }

        options.clearOptions();
        this.createStationServicesOptions();
    }

    private OptionId lastOptionMousedOver = null;
    public void optionMousedOver(String optionText, Object optionData) {

    }

    public void advance(float amount) {

    }

    private void addText(String text) {
        textPanel.addParagraph(text);
    }

    private void appendText(String text) {
        textPanel.appendToLastParagraph(" " + text);
    }

    private String getString(String id) {
        String str = Global.getSettings().getString("stationInteractionDialog", id);

        String fleetOrShip = "fleet";
        if (playerFleet.getFleetData().getMembersListCopy().size() == 1) {
            fleetOrShip = "ship";
            if (playerFleet.getFleetData().getMembersListCopy().get(0).isFighterWing()) {
                fleetOrShip = "fighter wing";
            }
        }
        str = str.replaceAll("\\$fleetOrShip", fleetOrShip);
        str = str.replaceAll("\\$stationName", station.getFullName());

        float needed = playerFleet.getLogistics().getTotalRepairSupplyCost();
        float supplies = playerFleet.getCargo().getSupplies();
        str = str.replaceAll("\\$supplies", "" + (int) supplies);
        str = str.replaceAll("\\$repairSupplyCost", "" + (int) Math.ceil(needed));

        return str;
    }


    public Object getContext() {
        return null;
    }

    public void coreUIDismissed() {
        optionSelected(null, OptionId.INIT_NO_TEXT);
    }

    private void displayRleationships(int value)
    {
        if(value == 1)
        {
            // Display allies
            List<String> allies = ExerelinUtilsFaction.getFactionsAlliedWithFaction(this.station.getFaction().getId());
            if(allies.size() > 0)
                textPanel.addParagraph("Current allies of " + this.station.getFaction().getDisplayName() + ":");
            else
                textPanel.addParagraph(this.station.getFaction().getDisplayName() + " has no allies currently.");

            for(int i = 0; i < allies.size(); i++)
            {
                if(!allies.get(i).equalsIgnoreCase(this.station.getFaction().getId()))
                    textPanel.addParagraph(Global.getSector().getFaction(allies.get(i)).getDisplayName());
            }
        }
        else if(value == -1)
        {
            // Display enemies
            List<String> enemies = ExerelinUtilsFaction.getFactionsAtWarWithFaction(this.station.getFaction().getId(), true);
            if(enemies.size() > 0) // Always at war with Rebel, Abandoned, Pirates
                textPanel.addParagraph(this.station.getFaction().getDisplayName() + " is currently at war with:");
            else
                textPanel.addParagraph(this.station.getFaction().getDisplayName() + " has no enemies currently.");

            for(int i = 0; i < enemies.size(); i++)
            {
                    textPanel.addParagraph(Global.getSector().getFaction(enemies.get(i)).getDisplayName());
            }
        }
    }

    private void displayMessages()
    {
        List<ExerelinMessage> messages = ((ExerelinMessageManager)Global.getSector().getPersistentData().get("ExerelinMessageManager")).getMessages();

        for(ExerelinMessage message : messages)
        {
            if(message.color == null)
                textPanel.addParagraph(message.message);
            else
                textPanel.addParagraph(message.message, message.color);
        }
    }

    private void displayStationStatus()
    {
        StationRecord stationRecord = SystemManager.getSystemManagerForAPI((StarSystemAPI)this.station.getContainingLocation()).getSystemStationManager().getStationRecordForToken(this.station);

        if(stationRecord == null)
            return;

        //if(!Global.getSector().getPlayerFleet().getFaction().getId().equalsIgnoreCase(this.station.getFaction().getId()))
        //    return;

        float stationEfficiency = stationRecord.getEfficiency(false);
        int numAttacking = stationRecord.getNumAttacking();

        String threatLevel = "None";
        Color threatColor = Color.white;

        String stationEfficiencyPercentage = "%";
        Color stationEfficiencyColor = Color.green;

        if(stationEfficiency < 0.5)
            stationEfficiencyColor = Color.red;
        else if(stationEfficiency < 1.0)
            stationEfficiencyColor = Color.orange;
        stationEfficiencyPercentage = (stationEfficiency*100) + stationEfficiencyPercentage;

        if(numAttacking >= 3)
        {
            threatLevel = "High";
            threatColor = Color.red;
        }
        else if(numAttacking == 2)
        {
            threatLevel = "Medium";
            threatColor = Color.orange;
        }
        else if(numAttacking == 1)
        {
            threatLevel = "Low";
            threatColor = Color.yellow;
        }


        textPanel.addParagraph("Station Efficiency: " + stationEfficiencyPercentage, stationEfficiencyColor);
        textPanel.addParagraph("Station Threat Level: " + threatLevel, threatColor);
        textPanel.addParagraph("Current Station Fleet Behaviour: " + stationRecord.getStationFleetStance().toString());
        textPanel.addParagraph("Influence with " + this.station.getFaction().getDisplayName() + ": " + SectorManager.getCurrentSectorManager().getDiplomacyManager().getRecordForFaction(this.station.getFaction().getId()).getPlayerInfluence());
    }

    private void setStationStance(StationRecord.StationFleetStance stationFleetStance)
    {
        SystemManager.getSystemManagerForAPI((StarSystemAPI)this.station.getContainingLocation()).getSystemStationManager().getStationRecordForToken(this.station).setStationFleetStance(stationFleetStance);
        textPanel.addParagraph("Station fleet behaviour changed.");
    }

    private void setAllStationsInSystemStance()
    {
        StationRecord.StationFleetStance stationFleetStance = SystemManager.getSystemManagerForAPI((StarSystemAPI)this.station.getContainingLocation()).getSystemStationManager().getStationRecordForToken(this.station).getStationFleetStance();

        SystemStationManager systemStationManager = SystemManager.getSystemManagerForAPI((StarSystemAPI)this.station.getContainingLocation()).getSystemStationManager();
        for(StationRecord stationRecord : systemStationManager.getStationRecords())
        {
            if(stationRecord.getOwner() != null && stationRecord.getOwner().getFactionId().equalsIgnoreCase(this.station.getFaction().getId()))
                stationRecord.setStationFleetStance(stationFleetStance);
        }

        textPanel.addParagraph("All stations in system fleet behaviours changed.");
    }

    private void createPlayerCommandedDefenseFleet()
    {
        WarFleet warFleet = new WarFleet(this.station.getFaction().getId(), this.station, null, null, null, WarFleet.FleetStance.DEFENSE, false);
        SectorManager.getCurrentSectorManager().addPlayerCommandedFleet(warFleet);
        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(80000f);
    }

    private void createPlayerCommandedAsteroidMiningFleet()
    {
        SectorEntityToken targetAsteroid = SystemManager.getSystemManagerForAPI((StarSystemAPI)this.station.getContainingLocation()).getSystemStationManager().getStationRecordForToken(this.station).getTargetAsteroid();

        AsteroidMiningFleet asteroidMiningFleet = new AsteroidMiningFleet(this.station.getFaction().getId(), this.station, targetAsteroid);
        SectorManager.getCurrentSectorManager().addPlayerCommandedFleet(asteroidMiningFleet);
        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(15000f);
    }

    private void createPlayerCommandedGasMiningFleet()
    {
        SectorEntityToken targetGasGiant = SystemManager.getSystemManagerForAPI((StarSystemAPI)this.station.getContainingLocation()).getSystemStationManager().getStationRecordForToken(this.station).getTargetGasGiant();

        GasMiningFleet gasMiningFleet = new GasMiningFleet(this.station.getFaction().getId(), this.station, targetGasGiant);
        SectorManager.getCurrentSectorManager().addPlayerCommandedFleet(gasMiningFleet);
        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(15000f);
    }

    private void createPlayerCommandedAttackFleet()
    {
        SectorEntityToken spawnStation = ExerelinUtils.getClosestStationForFaction(Global.getSector().getPlayerFleet().getFaction().getId(), (StarSystemAPI)this.station.getContainingLocation(), this.station);

        if(spawnStation == null)
        {
            StarSystemAPI spawnSystem = ExerelinUtils.getClosestSystemWithFaction((StarSystemAPI)this.station.getContainingLocation(), Global.getSector().getPlayerFleet().getFaction().getId());
            if(spawnSystem == null)
            {
                return;
            }
            else
            {
                spawnStation = ExerelinUtils.getRandomStationInSystemForFaction(Global.getSector().getPlayerFleet().getFaction().getId(), spawnSystem);
                if(spawnStation == null)
                {
                    return;
                }
            }
        }

        WarFleet warFleet = new WarFleet(Global.getSector().getPlayerFleet().getFaction().getId(), spawnStation, this.station, null, spawnStation, WarFleet.FleetStance.ATTACK, false);
        SectorManager.getCurrentSectorManager().addPlayerCommandedFleet(warFleet);
        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(80000f);
    }

    private void createPlayerCommandedBoardingFleet()
    {
        SectorEntityToken spawnStation = ExerelinUtils.getClosestStationForFaction(Global.getSector().getPlayerFleet().getFaction().getId(), (StarSystemAPI)this.station.getContainingLocation(), this.station);

        if(spawnStation == null)
        {
            StarSystemAPI spawnSystem = ExerelinUtils.getClosestSystemWithFaction((StarSystemAPI)this.station.getContainingLocation(), Global.getSector().getPlayerFleet().getFaction().getId());
            if(spawnSystem == null)
            {
                return;
            }
            else
            {
                spawnStation = ExerelinUtils.getRandomStationInSystemForFaction(Global.getSector().getPlayerFleet().getFaction().getId(), spawnSystem);
                if(spawnStation == null)
                {
                    return;
                }
            }
        }

        StationAttackFleet stationAttackFleet = new StationAttackFleet(Global.getSector().getPlayerFleet().getFaction().getId(), spawnStation, this.station, spawnStation, false);
        SectorManager.getCurrentSectorManager().addPlayerCommandedFleet(stationAttackFleet);
        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(80000f);
    }

    private void storeShips()
    {
        List<FleetMemberAPI> members = playerFleet.getFleetData().getMembersListCopy();

        if(members.size() > 1)
        {
            dialog.showFleetMemberPickerDialog("Store Ships in Personal Hanger", "Store", "Cancel", 3, 7, 58f, true, true, members,
                    new FleetMemberPickerListener() {
                        public void pickedFleetMembers(List<FleetMemberAPI> members) {

                            StationRecord stationRecord = SystemManager.getSystemManagerForAPI((StarSystemAPI)station.getContainingLocation()).getSystemStationManager().getStationRecordForToken(station);
                            CargoAPI cargo = stationRecord.getPlayerStorage();

                            if (members != null && !members.isEmpty()) {
                                for (FleetMemberAPI member : members) {
                                    cargo.getMothballedShips().addFleetMember(member);
                                    playerFleet.getFleetData().removeFleetMember(member);
                                    member.getRepairTracker().setCR(1);
                                }
                            }

                            textPanel.addParagraph("You leave your hanger.");
                            options.clearOptions();
                            createStationServicesOptions();
                        }
                        public void cancelledFleetMemberPicking() {
                            textPanel.addParagraph("You leave your hanger.");
                        }
                    });
        }
    }

    private void retrieveShips(CargoAPI cargo)
    {
        List<FleetMemberAPI> hangerMembers = cargo.getMothballedShips().getMembersListCopy();

        // Create empty dummy fleet
        CampaignFleetAPI dummyFleet = Global.getSector().createFleet("player", "shuttle");
        List<FleetMemberAPI> members = dummyFleet.getFleetData().getMembersListCopy();
        for(FleetMemberAPI member : members) {
            dummyFleet.getFleetData().removeFleetMember(member);
        }

        // Move hanger ships into it
        float numCrewRequired = 0;
        for(FleetMemberAPI member : hangerMembers) {
            dummyFleet.getFleetData().addFleetMember(member);
            member.getRepairTracker().setMothballed(false);
            member.getRepairTracker().setCR(1);
            numCrewRequired = numCrewRequired + member.getMinCrew();
        }

        // Extract hanger list from dummy fleet to clear mothballed flag
        members = dummyFleet.getFleetData().getMembersListCopy();
        cargo.addCrew(CargoAPI.CrewXPLevel.GREEN, (int)numCrewRequired);

        if(!members.isEmpty())
        {
            dialog.showFleetMemberPickerDialog("Retrieve Ships from Personal Hanger", "Retrieve", "Cancel", 3, 7, 58f, true, true, members,
                    new FleetMemberPickerListener() {
                        public void pickedFleetMembers(List<FleetMemberAPI> members) {

                            StationRecord stationRecord = SystemManager.getSystemManagerForAPI((StarSystemAPI)station.getContainingLocation()).getSystemStationManager().getStationRecordForToken(station);
                            CargoAPI cargo = stationRecord.getPlayerStorage();

                            if (members != null && !members.isEmpty()) {
                                for (FleetMemberAPI member : members) {
                                    cargo.getMothballedShips().removeFleetMember(member);
                                    playerFleet.getFleetData().addFleetMember(member);
                                    member.getRepairTracker().setCR(1);
                                }
                            }

                            textPanel.addParagraph("You leave your hanger.");
                            options.clearOptions();
                            createStationServicesOptions();
                        }
                        public void cancelledFleetMemberPicking() {
                            textPanel.addParagraph("You leave your hanger.");
                        }
                    });
        }
        cargo.removeCrew(CargoAPI.CrewXPLevel.GREEN, (int)numCrewRequired);
    }

    private void removeRestrictedCargo()
    {
        if(this.station.getFaction().getId().equalsIgnoreCase("independent")
                || this.station.getFaction().getId().equalsIgnoreCase("neutral"))
            return; // Ignore for independant/neutral station

         int influenceWithFaction = SectorManager.getCurrentSectorManager().getDiplomacyManager().getRecordForFaction(this.station.getFaction().getId()).getPlayerInfluence();

        if(influenceWithFaction < 60
                && station.getFaction().getId().equalsIgnoreCase(Global.getSector().getPlayerFleet().getFaction().getId()))
            influenceWithFaction = 60; // If member of faction then treat as if 60 influence

        if(influenceWithFaction < 30)
        {
            // Remove destroyers
            List<FleetMemberAPI> members = station.getCargo().getMothballedShips().getMembersListCopy();
            for(FleetMemberAPI member : members)
            {
                if(member.isDestroyer())
                {
                    station.getCargo().getMothballedShips().removeFleetMember(member);
                    restrictedItems.getMothballedShips().addFleetMember(member);
                }
            }
        }

        if(influenceWithFaction < 40)
        {
            // Remove fighters
            List<FleetMemberAPI> members = station.getCargo().getMothballedShips().getMembersListCopy();
            for(FleetMemberAPI member : members)
            {
                if(member.isFighterWing())
                {
                    station.getCargo().getMothballedShips().removeFleetMember(member);
                    restrictedItems.getMothballedShips().addFleetMember(member);
                }
            }
        }

        if(influenceWithFaction < 60)
        {
            // Remove large weapons
            // NOT SURE HOW TO DO THIS
        }

        if(influenceWithFaction < 80)
        {
            // Remove cruisers
            List<FleetMemberAPI> members = station.getCargo().getMothballedShips().getMembersListCopy();
            for(FleetMemberAPI member : members)
            {
                if(member.isCruiser())
                {
                    station.getCargo().getMothballedShips().removeFleetMember(member);
                    restrictedItems.getMothballedShips().addFleetMember(member);
                }
            }
        }

        if(influenceWithFaction < 120)
        {
            // Remove capitals
            List<FleetMemberAPI> members = station.getCargo().getMothballedShips().getMembersListCopy();
            for(FleetMemberAPI member : members)
            {
                if(member.isCapital())
                {
                    station.getCargo().getMothballedShips().removeFleetMember(member);
                    restrictedItems.getMothballedShips().addFleetMember(member);
                }
            }
        }
    }

    private void restoreRestrictedCargo()
    {
        List<FleetMemberAPI> members = restrictedItems.getMothballedShips().getMembersListCopy();
        for(FleetMemberAPI member : members)
        {
            restrictedItems.getMothballedShips().removeFleetMember(member);
            station.getCargo().getMothballedShips().addFleetMember(member);
        }
    }
}



