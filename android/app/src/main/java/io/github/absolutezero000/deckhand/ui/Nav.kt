package io.github.absolutezero000.deckhand.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.absolutezero000.deckhand.data.Repository
import io.github.absolutezero000.deckhand.data.SettingsState
import io.github.absolutezero000.deckhand.domain.Filter
import io.github.absolutezero000.deckhand.domain.Mode

@Composable
fun DeckhandNav(repo: Repository) {
    val settings by repo.settings.state.collectAsStateWithLifecycle(initialValue = null as SettingsState?)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val s = settings
        if (s == null) Box(Modifier.fillMaxSize()) else NavRoot(repo, startConfigured = s.configured)
    }
}

private fun optional(name: String) = navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }

@Composable
private fun NavRoot(repo: Repository, startConfigured: Boolean) {
    val nav = rememberNavController()
    val start = remember { if (startConfigured) "home" else "setup" }
    NavHost(
        nav, startDestination = start,
        enterTransition = { slideInHorizontally(tween(280)) { it / 6 } + fadeIn(tween(280)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(280)) },
        popExitTransition = { slideOutHorizontally(tween(240)) { it / 6 } + fadeOut(tween(240)) },
    ) {
        composable("setup") {
            SetupScreen(
                repo = repo,
                canGoBack = nav.previousBackStackEntry != null,
                onBack = { nav.popBackStack() },
                onDone = { nav.navigate("home") { popUpTo(0) } },
            )
        }
        composable("home") {
            HomeScreen(
                repo = repo,
                onDeck = { nav.navigate("choose/$it") },
                onBrowse = { nav.navigate("browse/$it") },
                onStudyAll = { nav.navigate("study/$ALL_DECKS?mode=${Mode.DUE.name}") },
                onKeepGoing = { nav.navigate("study/$ALL_DECKS?mode=${Mode.MORE.name}") },
                onSettings = { nav.navigate("settings") },
                onSearch = { nav.navigate("browse/$ALL_DECKS") },
            )
        }
        composable("choose/{deck}", arguments = listOf(navArgument("deck") { type = NavType.StringType })) { e ->
            val deck = e.arguments!!.getString("deck")!!
            ChooseScreen(
                repo = repo,
                deckId = deck,
                onBack = { nav.popBackStack() },
                onBrowse = { nav.navigate("browse/$deck") },
                onStart = { mode, f ->
                    val secs = f.sections.joinToString(",")
                    val tags = f.tags.joinToString(",")
                    nav.navigate("study/$deck?mode=${mode.name}&sections=$secs&tags=$tags")
                },
            )
        }
        composable(
            "study/{deck}?mode={mode}&sections={sections}&tags={tags}&q={q}",
            arguments = listOf(
                navArgument("deck") { type = NavType.StringType },
                navArgument("mode") { type = NavType.StringType; defaultValue = Mode.DUE.name },
                navArgument("sections") { type = NavType.StringType; defaultValue = "" },
                navArgument("tags") { type = NavType.StringType; defaultValue = "" },
                navArgument("q") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { e ->
            val a = e.arguments!!
            fun set(k: String) = a.getString(k).orEmpty().split(",").filter { it.isNotBlank() }.toSet()
            StudyScreen(
                repo = repo,
                deckId = a.getString("deck")!!,
                mode = Mode.valueOf(a.getString("mode")!!),
                filter = Filter(set("sections"), set("tags"), a.getString("q").orEmpty()),
                onClose = { nav.popBackStack() },
                onEdit = { nav.navigate("edit?card=$it") },
            )
        }
        composable("browse/{deck}", arguments = listOf(navArgument("deck") { type = NavType.StringType })) { e ->
            val deck = e.arguments!!.getString("deck")!!
            BrowseScreen(
                repo = repo,
                deckId = deck,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate("edit?card=$it") },
                onNew = { section -> nav.navigate("edit?deck=$deck" + (section?.let { "&section=$it" } ?: "")) },
                onPractice = { q, section ->
                    nav.navigate("study/$deck?mode=${Mode.PRACTICE.name}&sections=${section.orEmpty()}&q=${Uri.encode(q)}")
                },
            )
        }
        composable(
            "edit?card={card}&deck={deck}&section={section}",
            arguments = listOf(optional("card"), optional("deck"), optional("section")),
        ) { e ->
            val a = e.arguments!!
            EditScreen(
                repo = repo,
                cardId = a.getString("card"),
                deckId = a.getString("deck"),
                sectionId = a.getString("section"),
                onDone = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                repo = repo,
                onBack = { nav.popBackStack() },
                onChangeRepo = { nav.navigate("setup") },
                onDisconnected = { nav.navigate("setup") { popUpTo(0) } },
            )
        }
    }
}
