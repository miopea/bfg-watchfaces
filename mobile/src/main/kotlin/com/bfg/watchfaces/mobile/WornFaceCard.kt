package com.bfg.watchfaces.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bfg.watchfaces.appcore.WornFacePlan

/**
 * What is actually on the watch, above the list of what is saved here.
 *
 * ## Why it is worth the space
 *
 * Everything else on this screen is what the PHONE has. This one line is what
 * the WATCH has, and the two drift apart in ways a person notices and the app
 * previously could not explain: a send that failed after the transfer, a face
 * put there from another phone, or the phone app reinstalled with its saved
 * faces gone while the watch carries on wearing one.
 *
 * ## Silence shows NOTHING, deliberately
 *
 * [WornFacePlan.NoAnswer] renders no card at all. A watch charging in another
 * room is not an error worth a sentence, and a banner saying "could not reach
 * your watch" every time she opens a tab would be noise she learns to ignore —
 * the same judgement `WatchProviders` makes when its refresh loses the race.
 *
 * ## The unsaved case tells the truth rather than inventing a name
 *
 * When the watch wears a face this phone has no record of, all that exists is
 * the slug. `FaceLibrary.slugify` cannot be run backwards, so "trail_day" does
 * not become "Trail Day" — and a library entry with a made-up name, no
 * parameters, and no way to open or re-send it would be worse than saying
 * plainly that it is not saved here. Operator decision, 2026-10-03.
 */
@Composable
fun WornFaceCard(worn: WornFacePlan, modifier: Modifier = Modifier) {
    // No card at all. See the note above: this is the common case whenever the
    // watch is out of range, and it is not news.
    if (worn is WornFacePlan.NoAnswer) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("On your watch", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))

            when (worn) {
                // Unreachable above; the branch is here so the `when` stays
                // exhaustive and a new state cannot be added without a decision
                // about what it says.
                WornFacePlan.NoAnswer -> Unit

                WornFacePlan.NoneOfOurs -> Text(
                    "Your watch is not wearing a face you made here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is WornFacePlan.Saved -> Text(
                    if (worn.worn.active) {
                        "“${worn.saved.name}” is on your watch now."
                    } else {
                        // Installed but not the face being shown. Worth the
                        // distinction: "I sent it and it is not there" and "I
                        // sent it and I am not wearing it" are different
                        // problems and only one of them is ours.
                        "“${worn.saved.name}” is on your watch, but another face is showing."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is WornFacePlan.NotSaved -> Column {
                    Text(
                        "Your watch is wearing a face that is not saved on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    // The slug verbatim. It is the only thing the watch can
                    // tell us, and dressing it up as a name would be inventing
                    // one. Shown so she can recognise which face is meant.
                    Text(
                        worn.worn.slug.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "It was probably made on another phone, or before this app " +
                            "was reinstalled. The design itself is not stored on the " +
                            "watch, so it cannot be brought back here — but you can " +
                            "make a new one and send it over the top.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
