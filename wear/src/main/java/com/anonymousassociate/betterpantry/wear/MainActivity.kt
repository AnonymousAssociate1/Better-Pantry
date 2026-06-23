package com.anonymousassociate.betterpantry.wear

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import java.time.LocalDateTime
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var cache: WearScheduleCache
    private val nextShiftState = mutableStateOf<WearScheduleCache.NextShiftSyncData?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cache = WearScheduleCache(this)
        nextShiftState.value = cache.getNextShiftData()

        // Listen for cache updates in real-time
        getSharedPreferences("wear_pantry_cache", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(this)

        setContent {
            BetterPantryTheme {
                WearAppScreen(nextShiftState.value)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("wear_pantry_cache", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "next_shift_data_json") {
            nextShiftState.value = cache.getNextShiftData()
        }
    }
}

@Composable
fun BetterPantryTheme(content: @Composable () -> Unit) {
    val wearColors = androidx.wear.compose.material.Colors(
        primary = Color(0xFF81C784),
        primaryVariant = Color(0xFF4CAF50),
        secondary = Color(0xFFB0BEC5),
        background = Color.Black,
        onPrimary = Color.Black,
        onSecondary = Color.Black,
        onError = Color.Red
    )

    MaterialTheme(
        colors = wearColors,
        content = content
    )
}

// UI state models for the Wear screen to precompute AnnotatedStrings on data load
data class CoworkerItemState(
    val syncId: String,
    val annotatedString: androidx.compose.ui.text.AnnotatedString
)

data class WearScreenUiState(
    val formattedDay: String?,
    val formattedTime: String?,
    val role: String?,
    val managerText: String?,
    val coworkers: List<CoworkerItemState>,
    val formattedSyncedTime: String?
)

@Composable
fun WearAppScreen(data: WearScheduleCache.NextShiftSyncData?) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val coworkerCardShape = remember { RoundedCornerShape(24.dp) }
    val detailCardShape = remember { RoundedCornerShape(12.dp) }
    val primaryColor = MaterialTheme.colors.primary
    val cardBgColor = remember { Color(0xFF2C2C2E) }
    val detailBgColor = remember { Color(0xFF1C1C1E) }

    // Pre-calculate and cache the entire screen UI state once when data changes,
    // so no string formatting or AnnotatedString building happens during scrolling!
    val uiState = remember(data, primaryColor) {
        if (data == null) null else {
            val now = LocalDateTime.now()
            val activeCoworkers = data.coworkers.filter { coworker ->
                if (coworker.endDateTime.isNullOrBlank()) true else {
                    try {
                        val end = LocalDateTime.parse(coworker.endDateTime)
                        end.isAfter(now)
                    } catch (e: Exception) {
                        true
                    }
                }
            }
            val coworkerStates = activeCoworkers.map { coworker ->
                val annotatedString = buildAnnotatedString {
                    withStyle(style = SpanStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)) {
                        append(coworker.name)
                    }
                    if (coworker.workstation.isNotBlank()) {
                        withStyle(style = SpanStyle(color = primaryColor, fontSize = 10.sp)) {
                            append("\n${coworker.workstation}")
                        }
                    }
                    withStyle(style = SpanStyle(color = Color.LightGray, fontSize = 10.sp)) {
                        append("\n${coworker.timeRange}")
                    }
                }
                CoworkerItemState(
                    syncId = coworker.syncId,
                    annotatedString = annotatedString
                )
            }
            WearScreenUiState(
                formattedDay = data.formattedDay,
                formattedTime = data.formattedTime,
                role = data.role,
                managerText = data.managerText,
                coworkers = coworkerStates,
                formattedSyncedTime = data.formattedSyncedTime
            )
        }
    }

    val contentPadding = remember {
        androidx.compose.foundation.layout.PaddingValues(
            top = 16.dp,
            bottom = 24.dp,
            start = 8.dp,
            end = 8.dp
        )
    }

    // Request focus for rotary events when screen is loaded
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 8.dp)
            .rotaryScrollable(
                behavior = RotaryScrollableDefaults.behavior(scrollableState = listState),
                focusRequester = focusRequester
            )
            .focusRequester(focusRequester)
            .focusable(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = contentPadding
    ) {
        // 1. App Header Title
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Next Shift",
                    color = MaterialTheme.colors.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        if (uiState == null || uiState.formattedDay == null || uiState.formattedTime == null) {
            // Empty State
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No upcoming shifts",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Update your schedule in the mobile app to sync.",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // 2. Next Shift Detail Card
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(detailBgColor, shape = detailCardShape)
                        .padding(8.dp)
                ) {
                    Text(
                        text = uiState.formattedDay,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = uiState.formattedTime,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colors.primary
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    
                    Text(
                        text = "Role: ${uiState.role ?: "Unassigned"}",
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )
                    
                    if (!uiState.managerText.isNullOrBlank()) {
                        Text(
                            text = uiState.managerText,
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                }
            }

            // 3. Coworkers List Header (Tighter spacing)
            item {
                Text(
                    text = if (uiState.coworkers.isEmpty()) "Working Alone" else "Coworkers (${uiState.coworkers.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }

            // 4. Coworker Row Items (Flat rendering directly on Text composable)
            items(
                items = uiState.coworkers,
                key = { coworker -> coworker.syncId }
            ) { coworker ->
                Text(
                    text = coworker.annotatedString,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBgColor, shape = coworkerCardShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center
                )
            }

            // 5. Synced Timestamp at bottom
            item {
                val syncText = uiState.formattedSyncedTime ?: "Synced"
                Text(
                    text = syncText,
                    fontSize = 9.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )
            }
        }
    }
}
