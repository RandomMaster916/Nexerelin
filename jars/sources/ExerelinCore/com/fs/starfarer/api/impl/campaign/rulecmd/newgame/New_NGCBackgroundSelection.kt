package com.fs.starfarer.api.impl.campaign.rulecmd.newgame

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.OptionPanelAPI
import com.fs.starfarer.api.campaign.TextPanelAPI
import com.fs.starfarer.api.campaign.VisualPanelAPI
import com.fs.starfarer.api.campaign.rules.MemKeys
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.characters.CharacterCreationData
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin
import com.fs.starfarer.api.ui.BaseTooltipCreator
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator
import com.fs.starfarer.api.util.Misc
import exerelin.campaign.backgrounds.AIWarEngineerCharacterBackground
import exerelin.campaign.backgrounds.BaseCharacterBackground
import exerelin.campaign.backgrounds.PirateCharacterBackground
import exerelin.campaign.backgrounds.StandardCharacterBackground
import exerelin.utilities.NexConfig
import exerelin.utilities.ui.NexLunaCheckbox
import exerelin.utilities.ui.NexLunaElement
import org.lwjgl.input.Keyboard

class New_NGCBackgroundSelection : BaseCommandPlugin() {

    lateinit var optionPanel: OptionPanelAPI
    lateinit var textPanel: TextPanelAPI
    lateinit var visualPanel: VisualPanelAPI

    override fun execute(ruleId: String?, dialog: InteractionDialogAPI, params: MutableList<Misc.Token>?, memoryMap: MutableMap<String, MemoryAPI>): Boolean {

        this.optionPanel = dialog.optionPanel
        this.visualPanel = dialog.visualPanel
        this.textPanel = dialog.textPanel

        val data = memoryMap.get(MemKeys.LOCAL)!!.get("\$characterData") as CharacterCreationData

        val factionId = memoryMap.get(MemKeys.LOCAL)!!.get("\$playerFaction") as String
        var factionSpec = Global.getSettings().getFactionSpec(factionId)
        val factionConfig = NexConfig.getFactionConfig(factionId)

        optionPanel.clearOptions()
        textPanel.addPara("Choose your Background", Misc.getBasePlayerColor(), Misc.getBasePlayerColor())
        textPanel.addPara("A background can determine an assortment of different things, be that starting cargo, skills or future interactions with other characters. You can also choose to begin as a nobody who has yet to make their name known.")

        optionPanel.addOption("Done", "nex_NGCDone")
        optionPanel.setShortcut("nex_NGCDone", Keyboard.KEY_RETURN, false, false, false, false)

        var width = 600f
        var height = 400f

        var panel = visualPanel.showCustomPanel(width, height, null)
        var element = panel.createUIElement(width, height, true)

        var backgrounds = ArrayList<BaseCharacterBackground>()
        backgrounds.add(StandardCharacterBackground())
        backgrounds.add(PirateCharacterBackground())
        backgrounds.add(AIWarEngineerCharacterBackground())

        var first = true
        var checkboxes = HashMap<NexLunaElement, NexLunaCheckbox>()
        element.addPara("", 0f).position.inTL(10f, 0f)
        for (background in backgrounds.sortedBy { it.order }) {
            element.addSpacer(20f)

            var title = background.title
            var description = background.shortDescription
            var imagePath = background.getIcon(factionSpec, factionConfig)

            var subelement = NexLunaElement(element, width - 50, 50f).apply {
                renderBackground = false
                renderBorder = false
            }

            var checkbox = NexLunaCheckbox(first, subelement.innerElement, 20f, 20f)
            first = false
            checkboxes.put(subelement, checkbox)

            var image = subelement.innerElement.beginImageWithText(imagePath, 50f)

            image.addPara(title, 0f, Misc.getHighlightColor(), Misc.getHighlightColor())
            image.addPara(description, 0f, Misc.getTextColor(), Misc.getTextColor())

            subelement.innerElement.addImageWithText(0f)
            var prev = subelement.innerElement.prev
            prev.position.rightOfMid(checkbox.elementPanel, 10f)


            subelement.onClick {
                checkbox.value = true
            }


            if (background.hasSelectionTooltip()) {
                element.addTooltipTo(object  : TooltipCreator {
                    override fun isTooltipExpandable(tooltipParam: Any?): Boolean {
                        return false
                    }

                    override fun getTooltipWidth(tooltipParam: Any?): Float {
                        return 400f
                    }

                    override fun createTooltip(tooltip: TooltipMakerAPI?, expanded: Boolean, tooltipParam: Any?) {
                        background.addTooltipForSelection(tooltip, factionSpec, factionConfig)
                    }

                },subelement.elementPanel, TooltipMakerAPI.TooltipLocation.BELOW)
            }

        }

        for ((element, checkbox) in checkboxes) {

            element.onClick {
                element.playClickSound()
                checkbox.value = true

                for (other in checkboxes) {
                    if (other.value == checkbox) continue
                    other.value.value = false
                }
            }


            checkbox.onClick {
                checkbox.value = true

                for (other in checkboxes) {
                    if (other.value == checkbox) continue
                    other.value.value = false
                }
            }
        }


        panel.addUIElement(element)

        return true
    }

}