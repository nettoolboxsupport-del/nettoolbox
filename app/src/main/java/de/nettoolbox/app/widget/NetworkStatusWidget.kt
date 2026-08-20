package de.nettoolbox.app.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.nettoolbox.app.MainActivity
import de.nettoolbox.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget showing both radios at a glance.
 *
 * Two constraints shape it.
 *
 * **Widgets are not live.** Android refreshes them at most every 30 minutes,
 * and even that is a request rather than a promise. A signal reading presented
 * as current would mislead exactly the people this app is for, so the time of
 * the reading is always on screen and a tap re-reads on the spot.
 *
 * **Both radios, never one.** A technician wants to see Wi-Fi and cellular
 * together; which one currently carries traffic is a separate fact, shown in
 * the header. Choosing between them - as an earlier version did - throws away
 * half the answer.
 */
class NetworkStatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read directly for the first render. Deliberately NOT relying on the
        // state write landing before the composition starts: an earlier attempt
        // did exactly that and the widget sat on "loading" forever, because the
        // session reads its state when it begins, not afterwards.
        val initial = NetworkStatusReader.read(context)
        writeSnapshot(context, id, initial)

        provideContent {
            GlanceTheme {
                // Whichever reading is newer wins. After a tap the state holds
                // the fresh one; on a fresh session the direct read does. There
                // is no arrangement in which this shows nothing.
                val stored = SnapshotCodec.decode(currentState(SNAPSHOT_KEY))
                val snapshot = when {
                    stored == null -> initial
                    stored.readAtMillis >= initial.readAtMillis -> stored
                    else -> initial
                }
                WidgetBody(snapshot)
            }
        }
    }

    companion object {
        val SNAPSHOT_KEY = stringPreferencesKey("network_snapshot")

        /**
         * Puts a reading where the composition can see it.
         *
         * The snapshot is passed in rather than defaulted to a read: Kotlin
         * does not allow a suspend call in a default parameter value, and
         * requiring it makes the caller state which reading is being stored.
         */
        suspend fun writeSnapshot(
            context: Context,
            glanceId: GlanceId,
            snapshot: NetworkStatusSnapshot,
        ) {
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[SNAPSHOT_KEY] = SnapshotCodec.encode(snapshot)
            }
        }
    }
}

@Composable
private fun WidgetBody(snapshot: NetworkStatusSnapshot) {
    val context = LocalContext.current

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionRunCallback<RefreshWidgetAction>()),
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.getString(R.string.widget_active_prefix),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
            Spacer(modifier = GlanceModifier.width(4.dp))
            Text(
                text = snapshot.activeTransport,
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.primary,
                ),
            )
        }

        Section(title = context.getString(R.string.widget_transport_wifi), lines = snapshot.wifi)
        Section(
            title = context.getString(R.string.widget_transport_cellular),
            lines = snapshot.cellular,
        )

        Spacer(modifier = GlanceModifier.height(6.dp))
        Text(
            text = context.getString(
                R.string.widget_read_at,
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(snapshot.readAtMillis)),
            ),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

@Composable
private fun Section(title: String, lines: List<WidgetLine>) {
    val context = LocalContext.current

    Spacer(modifier = GlanceModifier.height(8.dp))
    Text(
        text = title,
        style = TextStyle(
            fontWeight = FontWeight.Medium,
            color = GlanceTheme.colors.secondary,
        ),
    )
    lines.forEach { line ->
        when {
            line.value != null -> LineRow(label = line.label, value = line.value)

            // Only stated when a permission is the reason. An empty field with
            // no explanation reads as "nothing there", which is a different and
            // wrong answer.
            line.blockedByPermission -> LineRow(
                label = line.label,
                value = context.getString(R.string.widget_needs_permission),
                emphasise = true,
            )

            else -> Unit
        }
    }
}

@Composable
private fun LineRow(label: String, value: String, emphasise: Boolean = false) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            text = "$label ",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        )
        Text(
            text = value,
            maxLines = 1,
            style = TextStyle(
                color = if (emphasise) {
                    GlanceTheme.colors.error
                } else {
                    GlanceTheme.colors.onSurface
                },
            ),
        )
    }
}

/** Re-reads both radios when the widget is tapped. */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        // Write first, then recompose. The other order would recompose against
        // the old state and look like the refresh did nothing - which is the
        // bug this whole arrangement exists to fix.
        runCatching {
            NetworkStatusWidget.writeSnapshot(
                context = context,
                glanceId = glanceId,
                snapshot = NetworkStatusReader.read(context),
            )
            NetworkStatusWidget().update(context, glanceId)
        }.onFailure {
            // Never swallowed: an exception in a widget callback leaves the
            // widget blank with no trace anywhere.
            Log.e("NetToolboxWidget", "refresh failed", it)
        }
    }
}

class NetworkStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NetworkStatusWidget()
}
