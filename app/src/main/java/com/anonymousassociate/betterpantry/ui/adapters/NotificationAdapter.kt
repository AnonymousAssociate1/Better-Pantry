package com.anonymousassociate.betterpantry.ui.adapters

import android.text.Html
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.models.NotificationData
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern

class NotificationAdapter(
    private var notifications: List<NotificationData>,
    private val onMarkAsReadClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit,
    private val onUndeleteClick: (String) -> Unit,
    private val onItemClick: (NotificationData) -> Unit,
    private val onTestClick: (NotificationData) -> Unit
) : RecyclerView.Adapter<NotificationAdapter.NotificationViewHolder>() {

    private var scheduleData: com.anonymousassociate.betterpantry.models.ScheduleData? = null

    fun updateScheduleData(newSchedule: com.anonymousassociate.betterpantry.models.ScheduleData) {
        scheduleData = newSchedule
        notifyDataSetChanged()
    }

    fun updateNotifications(newNotifications: List<NotificationData>) {
        notifications = newNotifications
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return NotificationViewHolder(view)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        holder.bind(notifications[position])
    }

    override fun getItemCount() = notifications.size

    private fun getWorkstationDisplayName(workstationId: String?, fallbackName: String?): String {
        val customNames = mapOf(
            "QC_2" to "QC 2",
            "1ST_CASHIER_1" to "Cashier 1",
            "SANDWICH_2" to "Sandwich 2",
            "SANDWICH_1" to "Sandwich 1",
            "SALAD_1" to "Salad 1",
            "SALAD_2" to "Salad 2",
            "DTORDERTAKER_1" to "DriveThru",
            "1ST_DR_1" to "Dining Room",
            "1st_Cashier" to "Cashier 1",
            "1st_Dr" to "Dining Room",
            "DtOrderTaker" to "DriveThru",
            "Sandwich_1" to "Sandwich 1",
            "Sandwich_2" to "Sandwich 2",
            "Qc_2" to "QC 2",
            "1ST_SANDWICH_1" to "Sandwich 1",
            "Bake" to "Baker",
            "BAKER" to "Baker",
            "SALAD" to "Salad 1",
            "SANDWICH" to "Sandwich 1",
            "1ST_CASHIER" to "Cashier 1",
            "QC_1" to "QC 1",
            "QC_2" to "QC 2",
            "DTORDERTAKER" to "DriveThru",
            "1ST_DR" to "Dining Room",
            "MANAGER_1" to "Manager",
            "MANAGER" to "Manager",
            "MANAGERADMIN_1" to "Manager",
            "MANAGERADMIN" to "Manager",
            "PEOPLEMANAGEMENT_1" to "Manager",
            "PEOPLEMANAGEMENT" to "Manager",
            "LABOR_MANAGEMENT" to "Manager",
            "LABORMANAGEMENT" to "Manager",
            "Labor Management" to "Manager"
        )
        if (workstationId != null) {
            val mapped = customNames[workstationId]
            if (mapped != null) return mapped
        }
        return fallbackName ?: workstationId ?: "Shift"
    }

    inner class NotificationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val subjectText: TextView = itemView.findViewById(R.id.notificationSubject)
        private val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
        private val dateText: TextView = itemView.findViewById(R.id.notificationDate)
        private val readStatusIcon: ImageView = itemView.findViewById(R.id.readStatusIcon)
        private val markAsReadButton: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.markAsReadButton)
        // private val testNotificationButton: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.testNotificationButton)
        private val deleteButton: ImageView = itemView.findViewById(R.id.deleteButton)
        private val undoButton: ImageView = itemView.findViewById(R.id.undoButton)

        fun bind(notification: NotificationData) {
            // Click listener for the card
            itemView.setOnClickListener {
                onItemClick(notification)
            }

            /*
            testNotificationButton.setOnClickListener {
                onTestClick(notification)
            }
            */
            // Hide the test button
            itemView.findViewById<View>(R.id.testNotificationButton)?.visibility = View.GONE

            subjectText.text = notification.subject ?: "No Subject"
            
            // Handle message content
            messageContainer.removeAllViews()
            var displayMessage = notification.message ?: ""
            var currentEventType: String? = null

            try {
                val appDataStr = notification.appData
                if (!appDataStr.isNullOrEmpty()) {
                    var json: JSONObject? = null
                    try {
                        val value = JSONTokener(appDataStr).nextValue()
                        if (value is JSONObject) {
                            json = value
                        } else if (value is String) {
                            val innerValue = JSONTokener(value).nextValue()
                            if (innerValue is JSONObject) json = innerValue
                        }
                    } catch (e: Exception) {}

                    if (json != null) {
                        val eventType = json.optString("eventType")
                        currentEventType = eventType
                        
                        if (eventType == "CALLFORHELP_INITIATED_EVENT") {
                            val initiatorShift = json.optJSONObject("initiatorShift")
                            val startStr = initiatorShift?.optString("startDateTime")
                            val endStr = initiatorShift?.optString("endDateTime")
                            
                            var workstationName = "Shift"
                            
                            // Try to get from JSON first
                            val jsonName = initiatorShift?.optString("workstationName")
                            val rawId = initiatorShift?.optString("workstationId")
                            val rawCode = initiatorShift?.optString("workstationCode")
                            
                            val lookupKey = if (!rawId.isNullOrEmpty()) rawId 
                                            else if (!rawCode.isNullOrEmpty()) rawCode 
                                            else jsonName

                            if (!jsonName.isNullOrEmpty()) {
                                workstationName = getWorkstationDisplayName(lookupKey, jsonName)
                            }
                            
                            val shiftId = initiatorShift?.optString("shiftId") ?: initiatorShift?.optLong("shiftId")?.toString()
                            if (shiftId != null) {
                                val trackItem = scheduleData?.track?.find { it.primaryShiftRequest?.shift?.shiftId == shiftId }
                                val shift = trackItem?.primaryShiftRequest?.shift
                                if (shift != null) {
                                    workstationName = getWorkstationDisplayName(shift.workstationId ?: shift.workstationCode, shift.workstationName)
                                }
                            }

                            if (!startStr.isNullOrEmpty() && !endStr.isNullOrEmpty()) {
                                val sTime = LocalDateTime.parse(startStr).format(DateTimeFormatter.ofPattern("h:mma", Locale.US))
                                val eTime = LocalDateTime.parse(endStr).format(DateTimeFormatter.ofPattern("h:mma", Locale.US))
                                val date = LocalDateTime.parse(startStr).format(DateTimeFormatter.ofPattern("M/d", Locale.US))
                                displayMessage = "Manager is calling for help for the $workstationName shift from $sTime - $eTime on $date"
                            }
                        } else if (eventType == "POST_INITIATED_EVENT") {
                            val initiatorShift = json.optJSONObject("initiatorShift")
                            val initiator = json.optJSONObject("initiatingAssociate") ?: json.optJSONObject("initiator")
                            val startStr = initiatorShift?.optString("startDateTime")
                            val endStr = initiatorShift?.optString("endDateTime")
                            
                            var workstationName = "Shift"
                            
                            // Try to get from JSON first
                            val jsonName = initiatorShift?.optString("workstationName")
                            val rawId = initiatorShift?.optString("workstationId")
                            val rawCode = initiatorShift?.optString("workstationCode")
                            
                            val lookupKey = if (!rawId.isNullOrEmpty()) rawId 
                                            else if (!rawCode.isNullOrEmpty()) rawCode 
                                            else jsonName

                            if (!jsonName.isNullOrEmpty()) {
                                workstationName = getWorkstationDisplayName(lookupKey, jsonName)
                            }

                            val shiftId = initiatorShift?.optString("shiftId") ?: initiatorShift?.optLong("shiftId")?.toString()
                            if (shiftId != null) {
                                val trackItem = scheduleData?.track?.find { it.primaryShiftRequest?.shift?.shiftId == shiftId }
                                val shift = trackItem?.primaryShiftRequest?.shift
                                if (shift != null) {
                                    workstationName = getWorkstationDisplayName(shift.workstationId ?: shift.workstationCode, shift.workstationName)
                                }
                            }
                            
                            if (!startStr.isNullOrEmpty() && !endStr.isNullOrEmpty()) {
                                val sTime = LocalDateTime.parse(startStr).format(DateTimeFormatter.ofPattern("h:mma", Locale.US))
                                val eTime = LocalDateTime.parse(endStr).format(DateTimeFormatter.ofPattern("h:mma", Locale.US))
                                val date = LocalDateTime.parse(startStr).format(DateTimeFormatter.ofPattern("M/d", Locale.US))
                                
                                val first = initiator?.optString("firstName") ?: ""
                                val last = initiator?.optString("lastName") ?: ""
                                val name = "$first $last".trim().ifEmpty { "Someone" }
                                
                                displayMessage = "$name's $workstationName shift from $sTime - $eTime on $date is available for pickup"
                            }
                        } else if (eventType == "POST_APPROVED_EVENT") {
                            val initiatorShift = json.optJSONObject("initiatorShift")
                            val initiator = json.optJSONObject("initiatingAssociate")
                            val startStr = initiatorShift?.optString("startDateTime")
                            val endStr = initiatorShift?.optString("endDateTime")
                            
                            var workstationName = "Shift"
                            
                            // Try to get from JSON first
                            val jsonName = initiatorShift?.optString("workstationName")
                            val rawId = initiatorShift?.optString("workstationId")
                            val rawCode = initiatorShift?.optString("workstationCode")
                            
                            val lookupKey = if (!rawId.isNullOrEmpty()) rawId 
                                            else if (!rawCode.isNullOrEmpty()) rawCode 
                                            else jsonName

                            if (!jsonName.isNullOrEmpty()) {
                                workstationName = getWorkstationDisplayName(lookupKey, jsonName)
                            }

                            val shiftId = initiatorShift?.optString("shiftId") ?: initiatorShift?.optLong("shiftId")?.toString()
                            if (shiftId != null) {
                                val trackItem = scheduleData?.track?.find { it.primaryShiftRequest?.shift?.shiftId == shiftId }
                                val shift = trackItem?.primaryShiftRequest?.shift
                                if (shift != null) {
                                    workstationName = getWorkstationDisplayName(shift.workstationId ?: shift.workstationCode, shift.workstationName)
                                }
                            }
                            
                            if (!startStr.isNullOrEmpty() && !endStr.isNullOrEmpty()) {
                                val sTime = LocalDateTime.parse(startStr).format(DateTimeFormatter.ofPattern("h:mma", Locale.US))
                                val eTime = LocalDateTime.parse(endStr).format(DateTimeFormatter.ofPattern("h:mma", Locale.US))
                                val date = LocalDateTime.parse(startStr).format(DateTimeFormatter.ofPattern("M/d", Locale.US))
                                
                                val first = initiator?.optString("firstName") ?: ""
                                val last = initiator?.optString("lastName") ?: ""
                                val name = "$first $last".trim().ifEmpty { "Someone" }
                                
                                displayMessage = "Your shift request for $name's $workstationName shift from $sTime - $eTime on $date was approved"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
                        if (displayMessage.contains("<table", ignoreCase = true)) {
                            parseAndRenderHtml(displayMessage, messageContainer)
                        } else {
                            val textView = TextView(itemView.context)
                            var processedMessage = displayMessage
                            // Remove trailing empty paragraphs with nbsp
                            processedMessage = processedMessage.replace(Regex("<p>\\s*&nbsp;\\s*</p>\\s*$"), "")
                            // Remove trailing &nbsp; (and variations)
                            processedMessage = processedMessage.replace(Regex("(&nbsp;?)+\\s*$"), "")
                            // Replace remaining &nbsp; with <br>
                            processedMessage = processedMessage.replace("&nbsp;", "<br>").replace("&nbsp", "<br>")
                            
                            val imageGetter = Base64ImageGetter(textView)
                            textView.text = trimTrailingWhitespace(Html.fromHtml(processedMessage, Html.FROM_HTML_MODE_COMPACT, imageGetter, null))
                            textView.textSize = 14f
                            textView.maxLines = 4
                            textView.ellipsize = TextUtils.TruncateAt.END
                            com.anonymousassociate.betterpantry.ui.LinkUtil.makeLinksClickable(textView)
                            messageContainer.addView(textView)
                        }
            
                        // Format date
                        notification.createDateTime?.let {
                            try {
                                val instant = Instant.parse(it)
                                val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")
                                    .withZone(ZoneId.systemDefault())
                                dateText.text = formatter.format(instant)
                            } catch (e: Exception) {
                                dateText.text = it
                            }
                        }
            
                                                // Status Logic
            
                                                val isDeleted = notification.deleted == true
            
                                                val isRead = notification.read == true
            
                                    
            
                                                // Read Indicator
            
                                                readStatusIcon.visibility = if (isRead) View.GONE else View.VISIBLE
            
                                    
            
                                                // Buttons
            
                                                if (isDeleted) {                            // Deleted Item: Show Undo, Hide Delete/Read
                            markAsReadButton.visibility = View.GONE
                            deleteButton.visibility = View.GONE
                            undoButton.visibility = View.VISIBLE
                            undoButton.setOnClickListener {
                                notification.notificationId?.let { id -> onUndeleteClick(id) }
                            }
                        } else {
                            // Active Item: Show Delete, Hide Undo
                            undoButton.visibility = View.GONE
                            deleteButton.visibility = View.VISIBLE
                            deleteButton.setOnClickListener {
                                notification.notificationId?.let { id -> onDeleteClick(id) }
                            }
            
                            // Mark as Read button only if unread
                            if (!isRead) {
                                markAsReadButton.visibility = View.VISIBLE
                                markAsReadButton.setOnClickListener {
                                    notification.notificationId?.let { id -> onMarkAsReadClick(id) }
                                }
                            } else {
                                markAsReadButton.visibility = View.GONE
                            }
                        }
                    }
            
                    private fun parseAndRenderHtml(html: String, container: LinearLayout) {
                        // Extract text before table (case-insensitive)
                        val tableIndex = html.indexOf("<table", ignoreCase = true)
                        if (tableIndex > 0) {
                            var preText = html.substring(0, tableIndex)
                            // Cleaning logic
                            preText = preText.replace(Regex("<p>\\s*&nbsp;\\s*</p>\\s*$"), "")
                            preText = preText.replace(Regex("(&nbsp;?)+\\s*$"), "")
                            preText = preText.replace("&nbsp;", "<br>").replace("&nbsp", "<br>")
                            preText = preText.replace(Regex("<head>.*?</head>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                            preText = preText.replace(Regex("</?html.*?>", RegexOption.IGNORE_CASE), "")
                            preText = preText.replace(Regex("</?body.*?>", RegexOption.IGNORE_CASE), "")
                            
                            val textView = TextView(itemView.context)
                            val imageGetter = Base64ImageGetter(textView)
                            val spannedText = trimTrailingWhitespace(Html.fromHtml(preText, Html.FROM_HTML_MODE_COMPACT, imageGetter, null))
                            if (spannedText.isNotEmpty()) {
                                textView.text = spannedText
                                textView.textSize = 14f
                                textView.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                                textView.setPadding(0, 0, 0, 16)
                                // No truncation for table headers/pre-text usually, but consistency might be good. 
                                // However, tables usually imply more complex data. 
                                // For now, I'll leave table layout as is or maybe truncate it too?
                                // User said "if a notification is longer than 4 lines...".
                                // Notifications with tables are likely long.
                                // But truncating a LinearLayout with a Table inside is harder than a TextView.
                                // Given the example was a simple HTML with Image, I'll focus on the TextView path.
                                com.anonymousassociate.betterpantry.ui.LinkUtil.makeLinksClickable(textView)
                                container.addView(textView)
                            }
                        }
            
                        // Extract table content using Pattern (Java regex) for robust HTML tag matching
                        val tablePattern = Pattern.compile("<table.*?>(.*?)</table>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
                        val matcher = tablePattern.matcher(html)
                        
                        if (matcher.find()) {
                            val tableContent = matcher.group(1) ?: ""
                            renderTable(tableContent, container)
                        }
                    }
            
                    private fun renderTable(tableHtml: String, container: LinearLayout) {
                        val context = itemView.context
                        val tableLayout = TableLayout(context)
                        tableLayout.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        tableLayout.isStretchAllColumns = true
                        
                        // Add border by setting background color
                        tableLayout.setBackgroundColor(ContextCompat.getColor(context, R.color.table_border_color))
                        tableLayout.setPadding(1, 1, 1, 1)
            
                        // Find rows
                        val rowPattern = Pattern.compile("<tr.*?>(.*?)</tr>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
                        val rowMatcher = rowPattern.matcher(tableHtml)
            
                        while (rowMatcher.find()) {
                            val rowContent = rowMatcher.group(1) ?: ""
                            val tableRow = TableRow(context)
                            
                            // Use border color for row background to show through cell margins
                            tableRow.setBackgroundColor(ContextCompat.getColor(context, R.color.table_border_color))
                            
                            tableRow.layoutParams = TableLayout.LayoutParams(
                                TableLayout.LayoutParams.MATCH_PARENT,
                                TableLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, 0, 0, 0)
                            }
                            
                            // Find cells (th or td)
                            val cellPattern = Pattern.compile("<(th|td).*?>(.*?)</\\1>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
                            val cellMatcher = cellPattern.matcher(rowContent)
                            
                            while (cellMatcher.find()) {
                                val tag = cellMatcher.group(1)
                                var cellRaw = cellMatcher.group(2) ?: ""
                                cellRaw = cellRaw.replace(Regex("<p>\\s*&nbsp;\\s*</p>\\s*$"), "")
                                cellRaw = cellRaw.replace(Regex("(&nbsp;?)+\\s*$"), "")
                                cellRaw = cellRaw.replace("&nbsp;", "<br>").replace("&nbsp", "<br>")
                                
                                val spannedText = trimTrailingWhitespace(Html.fromHtml(cellRaw, Html.FROM_HTML_MODE_COMPACT))
                                var cellString = spannedText.toString().trim()
                                var isDate = false
                                
                                // Attempt to format ISO dates in cells
                                if (cellString.contains("T") && cellString.contains("-")) {
                                    try {
                                        val instant = try {
                                            Instant.parse(cellString + "Z") // Try adding Z if missing
                                        } catch (e: Exception) {
                                            try {
                                                val ldt = java.time.LocalDateTime.parse(cellString)
                                                ldt.atZone(ZoneId.systemDefault()).toInstant()
                                            } catch (e2: Exception) {
                                                null
                                            }
                                        }
                                        
                                        if (instant != null) {
                                            val formatter = if (cellString.contains("00:00:00")) {
                                                DateTimeFormatter.ofPattern("M/d/yy")
                                            } else {
                                                DateTimeFormatter.ofPattern("M/d h:mma")
                                            }
                                            cellString = formatter.withZone(ZoneId.systemDefault()).format(instant)
                                            isDate = true
                                        }
                                    } catch (e: Exception) {
                                        // Leave as is
                                    }
                                }
            
                                val textView = TextView(context)
                                textView.text = if (isDate) cellString else spannedText
                                textView.setPadding(8, 16, 8, 16)
                                textView.textSize = 12f
                                textView.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                                com.anonymousassociate.betterpantry.ui.LinkUtil.makeLinksClickable(textView)
                                
                                if (tag.equals("th", ignoreCase = true)) {
                                    textView.setTypeface(null, android.graphics.Typeface.BOLD)
                                    textView.setBackgroundColor(ContextCompat.getColor(context, R.color.table_header_background))
                                } else {
                                    textView.setBackgroundColor(ContextCompat.getColor(context, R.color.table_row_background))
                                }
                                
                                // Add margins to create grid lines
                                val params = TableRow.LayoutParams(
                                    TableRow.LayoutParams.WRAP_CONTENT,
                                    TableRow.LayoutParams.MATCH_PARENT
                                )
                                params.setMargins(1, 1, 1, 1)
                                textView.layoutParams = params
                                
                                tableRow.addView(textView)
                            }
                            tableLayout.addView(tableRow)
                        }
                        container.addView(tableLayout)
                    }
                }
            
                inner class Base64ImageGetter(private val textView: TextView) : Html.ImageGetter {
                    override fun getDrawable(source: String): Drawable? {
                        if (source.startsWith("data:image/")) {
                            try {
                                val base64Source = source.substringAfter(",")
                                val decodedString = Base64.decode(base64Source, Base64.DEFAULT)
                                val bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                                val drawable = BitmapDrawable(textView.resources, bitmap)
                                
                                val displayMetrics = textView.resources.displayMetrics
                                // Calculate max width
                                // Priority 1: Use TextView width if laid out (and positive)
                                // Priority 2: Screen width - horizontal padding/margins (approx 16+16 start + 16+16 end = 64dp -> use 80dp for safety)
                                val viewWidth = textView.width
                                val maxWidth = if (viewWidth > 0) {
                                    viewWidth - (textView.paddingStart + textView.paddingEnd)
                                } else {
                                    displayMetrics.widthPixels - (80 * displayMetrics.density).toInt()
                                }
                                
                                var width = drawable.intrinsicWidth
                                var height = drawable.intrinsicHeight
                                
                                val ratio = maxWidth.toFloat() / width
                                val newHeight = (height * ratio).toInt()
                                
                                drawable.setBounds(0, 0, maxWidth, newHeight)
                                return drawable
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        return null
                    }
                }
            
                private fun trimTrailingWhitespace(source: CharSequence): CharSequence {        var i = source.length
        while (i > 0 && Character.isWhitespace(source[i - 1])) {
            i--
        }
        return source.subSequence(0, i)
    }
}
