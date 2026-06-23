package com.anonymousassociate.betterpantry.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.LocalDateTime

class NextShiftTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val context = applicationContext
        val cache = WearScheduleCache(context)
        val data = cache.getNextShiftData()

        val rootLayout = createTileLayout(context, data)

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(rootLayout)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<androidx.wear.protolayout.ResourceBuilders.Resources> {
        val resources = androidx.wear.protolayout.ResourceBuilders.Resources.Builder()
            .setVersion("1")
            .addIdToImageMapping(
                "app_icon",
                androidx.wear.protolayout.ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId.Builder()
                            .setResourceId(R.drawable.app_logo_full)
                            .build()
                    )
                    .build()
            )
            .build()
        return Futures.immediateFuture(resources)
    }

    private fun createTileLayout(context: Context, data: WearScheduleCache.NextShiftSyncData?): LayoutElementBuilders.LayoutElement {
        val packageName = context.packageName
        
        // Setup app launch clickable modifier
        val launchIntent = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName("$packageName.wear.MainActivity")
                    .build()
            )
            .build()

        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId("launch_main_activity")
            .setOnClick(launchIntent)
            .build()

        val rootModifiers = ModifiersBuilders.Modifiers.Builder()
            .setClickable(clickable)
            .build()

        // Root Box that expands to full screen size
        val rootBox = LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(rootModifiers)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_TOP)

        val columnBuilder = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // Top padding/margin (12dp spacer to position the title high but safe)
        columnBuilder.addContent(createSpacer(12f))

        // 1. Title "Next Shift" (Green, bold)
        columnBuilder.addContent(
            createText("Next Shift", 12f, 0xFF81C784.toInt(), isBold = true)
        )
        columnBuilder.addContent(createSpacer(4f)) // Slightly larger space so the long date line is pushed to a wider area of the circular screen

        if (data == null || data.formattedDay == null || data.formattedTime == null) {
            columnBuilder.addContent(createSpacer(12f))
            columnBuilder.addContent(
                createText("No upcoming shifts", 12f, 0xFFE0E0E0.toInt(), isBold = true)
            )
            columnBuilder.addContent(createSpacer(4f))
            columnBuilder.addContent(
                createText("Tap to open app", 10f, 0xFF9E9E9E.toInt())
            )
        } else {
            // 2. User's own shift time in format "Tue 6/23, 2:00pm - 10:00pm" (White, bold)
            val compactShiftTime = formatUserShiftForTile(data.formattedDay, data.formattedTime)
            columnBuilder.addContent(
                createText(compactShiftTime, 11f, 0xFFFFFFFF.toInt(), isBold = true)
            )
            columnBuilder.addContent(createSpacer(2f))

            // 3. Role (e.g. "Role: QC 1, DriveThru, Dining Room")
            val roleText = if (!data.role.isNullOrBlank()) "Role: ${data.role}" else "Role: Unassigned"
            columnBuilder.addContent(
                createText(roleText, 10f, 0xFFB0BEC5.toInt())
            )
            columnBuilder.addContent(createSpacer(2f))

            // 4. Manager (e.g. "Manager: Firstname Lastname")
            val managerText = data.managerText ?: "Manager: None scheduled"
            columnBuilder.addContent(
                createText(managerText, 10f, 0xFFB0BEC5.toInt())
            )
            columnBuilder.addContent(createSpacer(4f))

            // Subtle divider line to make it look visually clean and appealing
            val divider = LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(120f))
                .setHeight(DimensionBuilders.dp(1f))
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setBackground(
                            ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.ColorProp.Builder().setArgb(0xFF37474F.toInt()).build())
                                .build()
                        )
                        .build()
                )
                .build()
            columnBuilder.addContent(divider)
            columnBuilder.addContent(createSpacer(4f))

            // 5. Coworkers list (individual lines, filtering out ended shifts)
            val now = LocalDateTime.now()
            val coworkers = data.coworkers.filter { coworker ->
                if (coworker.endDateTime.isNullOrBlank()) true else {
                    try {
                        val end = LocalDateTime.parse(coworker.endDateTime)
                        end.isAfter(now)
                    } catch (e: Exception) {
                        true
                    }
                }
            }
            if (coworkers.isEmpty()) {
                columnBuilder.addContent(createSpacer(4f))
                columnBuilder.addContent(
                    createText("Working alone", 10f, 0xFFCFD8DC.toInt())
                )
            } else {
                // Show up to 8 coworkers. If size is > 8, show 7 coworkers + "+X more" text
                val maxVisible = 8
                val visibleCount = if (coworkers.size > maxVisible) 7 else coworkers.size
                val visibleCoworkers = coworkers.take(visibleCount)
                
                visibleCoworkers.forEach { cw ->
                    val displayTime = formatCoworkerTimeForTile(cw.timeRange)
                    val rowBuilder = LayoutElementBuilders.Row.Builder()
                        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    
                    if (!cw.workstation.isNullOrBlank()) {
                        // "Firstname: " (green & bold)
                        rowBuilder.addContent(
                            createText("${cw.name}: ", 9.5f, 0xFF81C784.toInt(), isBold = true)
                        )
                        // "Position" (white)
                        rowBuilder.addContent(
                            createText(cw.workstation, 9.5f, 0xFFFFFFFF.toInt())
                        )
                    } else {
                        // "Firstname" (green & bold) if workstation is blank
                        rowBuilder.addContent(
                            createText(cw.name, 9.5f, 0xFF81C784.toInt(), isBold = true)
                        )
                    }
                    
                    // Bullet dot separator (subtle grey)
                    rowBuilder.addContent(
                        createText(" • ", 9.5f, 0xFF78909C.toInt())
                    )
                    
                    // Time range
                    rowBuilder.addContent(
                        createText(displayTime, 9.5f, 0xFFCFD8DC.toInt())
                    )
                    
                    columnBuilder.addContent(rowBuilder.build())
                    columnBuilder.addContent(createSpacer(2f)) // 2dp spacer between coworker rows
                }
                
                if (coworkers.size > maxVisible) {
                    columnBuilder.addContent(
                        createText("+${coworkers.size - visibleCount} more", 9f, 0xFF78909C.toInt())
                    )
                }
            }
        }

        rootBox.addContent(columnBuilder.build())
        return rootBox.build()
    }

    private fun formatUserShiftForTile(day: String?, timeRange: String?): String {
        if (day == null || timeRange == null) return ""
        val shortDay = day
            .replace("Monday", "Mon")
            .replace("Tuesday", "Tue")
            .replace("Wednesday", "Wed")
            .replace("Thursday", "Thu")
            .replace("Friday", "Fri")
            .replace("Saturday", "Sat")
            .replace("Sunday", "Sun")
        
        val cleanTime = timeRange
            .replace(" - ", "-")
            .replace("-", " - ")
        
        return "$shortDay, $cleanTime"
    }

    private fun formatCoworkerTimeForTile(range: String): String {
        return range
            .replace(":00", "")
            .replace(" ", "")
    }

    private fun createText(text: String, sizeSp: Float, colorArgb: Int, isBold: Boolean = false): LayoutElementBuilders.Text {
        val fontStyleBuilder = LayoutElementBuilders.FontStyle.Builder()
            .setSize(DimensionBuilders.sp(sizeSp))
            .setColor(ColorBuilders.ColorProp.Builder().setArgb(colorArgb).build())

        if (isBold) {
            fontStyleBuilder.setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
        }

        return LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setFontStyle(fontStyleBuilder.build())
            .build()
    }

    private fun createSpacer(heightDp: Float): LayoutElementBuilders.Spacer {
        return LayoutElementBuilders.Spacer.Builder()
            .setHeight(DimensionBuilders.dp(heightDp))
            .build()
    }
}
