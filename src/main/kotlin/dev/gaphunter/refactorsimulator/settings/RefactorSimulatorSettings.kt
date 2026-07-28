package dev.gaphunter.refactorsimulator.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * v0.1 has no licensing wired in yet (see future/v0.2-refactor-simulator-pro/)
 * -- isPro stays false until that's reactivated. Present now so the
 * settings XML shape doesn't change shape later (adding a field is
 * backward-compatible; the field not existing at all isn't).
 */
@Service
@State(name = "RefactorSimulatorSettings", storages = [Storage("refactorSimulator.xml")])
class RefactorSimulatorSettings : PersistentStateComponent<RefactorSimulatorSettings.State> {

    class State {
        var isPro: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        fun getInstance(): RefactorSimulatorSettings = service()
    }
}
