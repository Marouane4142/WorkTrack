package fr.worktrack

import androidx.compose.ui.test.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import fr.worktrack.data.*
import fr.worktrack.domain.*
import fr.worktrack.export.DocumentService
import fr.worktrack.export.ImageImporter
import fr.worktrack.ui.*
import fr.worktrack.ui.theme.WorkTrackTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** The test host uses an in-memory repository, so it never changes the user's history. */
class WorkTrackComposeTest {
    @get:Rule val compose = createComposeRule()
    private val viewModels = ViewModelStore()

    @After fun clearViewModels() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModels.clear() }
    }

    private fun render(repository: WorkRepository): WorkTrackViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vm = WorkTrackViewModel(repository, DocumentService(context), SavedStateHandle(), ImageImporter(context))
        viewModels.put("worktrack", vm)
        compose.setContent { WorkTrackTheme { val state = vm.state.collectAsState().value; WorkTrackApp(vm, state) } }
        return vm
    }
    private fun launch(): WorkTrackViewModel {
        val vm = render(FakeRepository())
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Votre temps, au clair.").fetchSemanticsNodes().isNotEmpty() }
        return vm
    }
    @Test fun retryReconnectsToAnInterruptedRepositoryFlow() {
        val backing = FakeRepository()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val repository = object : WorkRepository by backing {
            override val entries: Flow<List<WorkEntry>> = flow {
                if (reads.incrementAndGet() == 1) error("Lecture temporairement indisponible")
                emitAll(backing.entries)
            }
        }
        render(repository)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Réessayer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Réessayer").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Votre temps, au clair.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Votre temps, au clair.").assertIsDisplayed()
    }
    @Test fun allFourDestinationsNavigateThroughNavigation3() {
        launch()
        compose.onNodeWithText("Historique").performClick()
        compose.onNodeWithText("Votre historique").assertIsDisplayed()
        compose.onNodeWithText("Exports").performClick()
        compose.onNodeWithText("Vos heures, à partager.").assertIsDisplayed()
        compose.onNodeWithText("Réglages").performClick()
        compose.onNodeWithText("À votre façon.").assertIsDisplayed()
        compose.onNodeWithText("Accueil").performClick()
        compose.onNodeWithText("Votre temps, au clair.").assertIsDisplayed()
    }
    @Test fun exportsDefaultToTheFiveWeekdayExcelTemplateAndRetainPdfAndCsv() {
        launch()
        compose.onNodeWithText("Exports").performClick()
        compose.onNodeWithText("Lundi → vendredi · 5 onglets dans le même fichier Excel").assertExists()
        compose.onNodeWithText("Feuille de pointage Excel").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ce mois").assertDoesNotExist()
        compose.onNodeWithText("Relevé PDF").performScrollTo().performClick()
        compose.onNodeWithText("Ce mois").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("CSV des activités").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("CSV journalier").performScrollTo().assertIsDisplayed()
    }
    @Test fun outboundTravelPreviewAndSavingDeductFortyFiveMinutes() {
        val vm = launch()
        compose.onNodeWithContentDescription("Nouvelle activité").performClick()
        compose.waitUntil { vm.draft.value != null }
        compose.runOnIdle { vm.updateDraft { it.copy(startMinute = 480, endMinute = 600, nextDay = false, type = ActivityType.TRAVEL,
            originType = PlaceType.HOME, destinationType = PlaceType.CUSTOMER_SITE, destination = "Site test") } }
        compose.onNodeWithText("1 h 15 comptabilisées").assertExists()
        compose.onNodeWithText("Enregistrer l'activité").performClick()
        compose.waitUntil(10_000) { vm.state.value.entries.size == 1 }
        compose.onNodeWithText("Votre temps, au clair.").assertExists()
        compose.runOnIdle {
            val entry = vm.state.value.entries.single()
            org.junit.Assert.assertEquals(120, entry.rawMinutes)
            org.junit.Assert.assertEquals(75, entry.nonEffectiveMinutes)
            org.junit.Assert.assertEquals(45, entry.deductedMinutes)
        }
    }
    @Test fun cancelingEditorKeepsDraftUntilExplicitConfirmation() {
        val vm = launch()
        compose.onNodeWithContentDescription("Nouvelle activité").performClick()
        compose.waitUntil { vm.draft.value != null }
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithText("Quitter la saisie ?").assertIsDisplayed()
        compose.onNodeWithText("Continuer la saisie").performClick()
        compose.onNodeWithText("Enregistrer l'activité").assertExists()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithText("Quitter", substring = false).performClick()
        compose.onNodeWithText("Votre temps, au clair.").assertIsDisplayed()
    }
    @Test fun editorShowsInterventionOnlyForClientAndOffersOneTravelCategory() {
        val vm = launch()
        compose.onNodeWithContentDescription("Nouvelle activité").performClick()
        compose.waitUntil { vm.draft.value != null }
        compose.onNodeWithText("L'intervention").assertExists()
        compose.onNodeWithText("Client", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Nom d'affaire").assertDoesNotExist()
        compose.runOnIdle { vm.selectType(ActivityType.OFFICE_WORK) }
        compose.onNodeWithText("L'intervention").assertDoesNotExist()
        compose.runOnIdle { vm.selectType(ActivityType.TRAVEL) }
        compose.onNodeWithText("L'intervention").assertDoesNotExist()
        compose.onNodeWithText("Trajet", substring = false).performClick()
        compose.onNodeWithText("Pause matin").assertDoesNotExist()
        compose.onNodeWithText("Pause après-midi").assertDoesNotExist()
        compose.onNodeWithText("Maison → Site client").assertDoesNotExist()
        compose.onNodeWithText("Intervention client").performClick()
        compose.onNodeWithText("L'intervention").assertExists()
    }
    @Test fun departurePrefillsFromClientSiteAndKeepsManualCorrection() {
        val date = LocalDate.now()
        val repository = FakeRepository()
        repository.entries.value = listOf(
            WorkCalculator.calculate(WorkEntry(start = "${date}T08:00", end = "${date}T10:00", location = "Site India"), WorkRules()),
            WorkCalculator.calculate(WorkEntry(start = "${date}T10:00", end = "${date}T11:00", type = ActivityType.LUNCH_BREAK), WorkRules()))
        val vm = render(repository)
        compose.waitUntil(10_000) { !vm.state.value.loading }
        compose.onNodeWithContentDescription("Nouvelle activité").performClick()
        compose.waitUntil { vm.draft.value != null }
        compose.runOnIdle { vm.selectType(ActivityType.TRAVEL) }
        compose.waitUntil { vm.draft.value?.type == ActivityType.TRAVEL }
        compose.runOnIdle {
            org.junit.Assert.assertEquals(PlaceType.CUSTOMER_SITE, vm.draft.value?.originType)
            org.junit.Assert.assertEquals("Site India", vm.draft.value?.origin)
            vm.updateDraft { it.copy(originType = PlaceType.OFFICE, origin = "Bureau", originAutomatic = false) }
        }
        compose.waitUntil { vm.draft.value?.originAutomatic == false }
        compose.runOnIdle { vm.setStart(630) }
        compose.waitUntil { vm.draft.value?.startMinute == 630 }
        compose.runOnIdle { org.junit.Assert.assertEquals(PlaceType.OFFICE, vm.draft.value?.originType) }
    }
    @Test fun timePickerCallbacksRejectAnEndBeforeStartWithoutRollingToTomorrow() {
        val vm = launch()
        compose.onNodeWithContentDescription("Nouvelle activité").performClick()
        compose.waitUntil { vm.draft.value != null }
        compose.runOnIdle { vm.updateDraft { it.copy(startMinute = 480, endMinute = 600, nextDay = false) } }
        compose.waitUntil { vm.draft.value?.endMinute == 600 }
        compose.runOnIdle { vm.setEnd(420) }
        compose.runOnIdle {
            org.junit.Assert.assertEquals(600, vm.draft.value?.endMinute)
            org.junit.Assert.assertEquals(false, vm.draft.value?.nextDay)
        }
        compose.onNodeWithText("10:00").assertExists()
        compose.runOnIdle { vm.updateDraft { it.copy(startMinute = 1380, endMinute = 60, nextDay = true) } }
        compose.waitUntil { vm.draft.value?.nextDay == true }
        compose.runOnIdle { vm.setNextDay(false) }
        compose.runOnIdle { org.junit.Assert.assertTrue(vm.draft.value!!.nextDay) }
    }
    @Test fun interventionPrefillsTheTripDestinationAndKeepsManualSiteCorrections() {
        val date = LocalDate.now()
        val repository = FakeRepository()
        repository.entries.value = listOf(
            WorkCalculator.calculate(WorkEntry(start = "${date}T08:00", end = "${date}T09:00", type = ActivityType.TRAVEL,
                originType = PlaceType.OFFICE, destinationType = PlaceType.CUSTOMER_SITE, destination = "Site India"), WorkRules()),
            WorkCalculator.calculate(WorkEntry(start = "${date}T09:00", end = "${date}T10:00", type = ActivityType.TRAVEL,
                originType = PlaceType.CUSTOMER_SITE, destinationType = PlaceType.CUSTOMER_SITE, origin = "Site India", destination = "Site Hotel"), WorkRules()))
        val vm = render(repository)
        compose.waitUntil(10_000) { !vm.state.value.loading }
        compose.onNodeWithContentDescription("Nouvelle activité").performClick()
        compose.waitUntil { vm.draft.value != null }
        compose.runOnIdle { org.junit.Assert.assertEquals("Site Hotel", vm.draft.value?.location) }
        compose.runOnIdle { vm.setStart(540) }
        compose.waitUntil { vm.draft.value?.location == "Site India" }
        compose.onNode(hasText("Lieu / site") and hasSetTextAction()).assertTextContains("Site India")
        compose.runOnIdle { vm.setLocation("Site corrigé") }
        compose.waitUntil { vm.draft.value?.locationAutomatic == false }
        compose.runOnIdle { vm.setStart(600) }
        compose.waitUntil { vm.draft.value?.startMinute == 600 }
        compose.runOnIdle { org.junit.Assert.assertEquals("Site corrigé", vm.draft.value?.location) }
    }
    @Test fun travelSiteFieldsDoNotOfferQuickHistorySuggestions() {
        val date = LocalDate.now()
        val repository = FakeRepository()
        repository.entries.value = listOf(WorkCalculator.calculate(WorkEntry(start = "${date}T08:00", end = "${date}T09:00",
            location = "Un site récent"), WorkRules()))
        val vm = render(repository)
        compose.waitUntil(10_000) { !vm.state.value.loading }
        compose.onNodeWithContentDescription("Nouvelle activité").performClick()
        compose.waitUntil { vm.draft.value != null }
        compose.runOnIdle {
            vm.selectType(ActivityType.TRAVEL)
            vm.updateDraft { it.copy(originType = PlaceType.CUSTOMER_SITE, destinationType = PlaceType.CUSTOMER_SITE,
                origin = "", destination = "", originAutomatic = false) }
        }
        compose.onNodeWithText("Départ · nom du site").assertExists()
        compose.onNodeWithText("Destination · nom du site").assertExists()
        compose.onNodeWithText("Un site récent", substring = false).assertDoesNotExist()
    }
}

private class FakeRepository : WorkRepository {
    override val entries = MutableStateFlow<List<WorkEntry>>(emptyList())
    override val cases = MutableStateFlow<List<BusinessCase>>(emptyList())
    override val audit = MutableStateFlow<List<AuditEvent>>(emptyList())
    override val settings = MutableStateFlow(AppSettings())
    override suspend fun initialize() = Unit
    override suspend fun save(entry: WorkEntry, allowOverlap: Boolean) { entries.value = entries.value.filter { it.id != entry.id } + WorkCalculator.calculate(entry, settings.value.rules) }
    override suspend fun delete(id: String) { entries.value = entries.value.filter { it.id != id } }
    override suspend fun duplicateDay(from: LocalDate, to: LocalDate) = Unit
    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) { settings.value = transform(settings.value) }
    override suspend fun recalculate(from: LocalDate, through: LocalDate) = Unit
    override suspend fun backup() = byteArrayOf()
    override suspend fun restore(backup: Backup, replace: Boolean) = Unit
    override suspend fun erase() { entries.value = emptyList() }
}
